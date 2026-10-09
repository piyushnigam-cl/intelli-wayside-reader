package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.wheel.WheelMessage.Ack;
import com.intelli.rfid.wayside.wheel.WheelMessage.ChannelFault;
import com.intelli.rfid.wayside.wheel.WheelMessage.FaultKind;
import com.intelli.rfid.wayside.wheel.WheelMessage.Heartbeat;
import com.intelli.rfid.wayside.wheel.WheelMessage.Hello;
import com.intelli.rfid.wayside.wheel.WheelMessage.SystemEdge;
import com.intelli.rfid.wayside.wheel.WheelMessage.SystemPulse;
import com.intelli.rfid.wayside.wheel.WheelMessage.Unknown;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything between received bytes and {@link WheelEvent}s, shared by the serial link and the
 * simulator so that the simulator exercises the same codec the SAMD21 will.
 *
 * <p>The link is UP while valid frames keep arriving: the firmware heartbeats at 1 Hz, so
 * {@code link-timeout-ms} of silence means the SAMD21 is gone, reset, or the cable is out.
 */
public abstract class FramedWheelSource implements WheelSource {

    private static final Logger log = LoggerFactory.getLogger(FramedWheelSource.class);

    private final String name;
    private final long linkTimeoutNanos;
    protected final LongSupplier nanos;
    private final FrameCodec codec = new FrameCodec();
    private final TickClock tickClock = new TickClock(60);

    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wayside-wheel-link");
        thread.setDaemon(true);
        return thread;
    });

    private volatile WheelListener listener = event -> {};
    private volatile boolean up;
    private volatile String reason = "no frame received yet";
    private volatile long lastFrameNanos = Long.MIN_VALUE;
    private volatile Hello hello;
    private final int[] meanUa = new int[WheelMessages.CHANNELS];
    private final boolean[] meanKnown = new boolean[WheelMessages.CHANNELS];
    private final boolean[] covered = new boolean[WheelMessages.CHANNELS];
    private final FaultKind[] faults = new FaultKind[WheelMessages.CHANNELS];
    private int lastSeq = -1;
    private long framesReceived;
    private long framesLost;
    private long protocolErrors;
    private int txSeq;

    protected FramedWheelSource(String name, long linkTimeoutMs, LongSupplier nanos) {
        this.name = name;
        this.linkTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(linkTimeoutMs);
        this.nanos = nanos;
        java.util.Arrays.fill(faults, FaultKind.NONE);
    }

    @Override
    public void start(WheelListener listener) {
        this.listener = listener;
        monitor.scheduleWithFixedDelay(this::checkLink, 500, 500, TimeUnit.MILLISECONDS);
        open();
    }

    @Override
    public void stop() {
        monitor.shutdownNow();
        close();
    }

    /** Starts whatever produces bytes. Must not block. */
    protected abstract void open();

    protected abstract void close();

    /** Writes an encoded frame towards the SAMD21. */
    protected abstract void write(byte[] wire);

    protected void setDown(String why) {
        reason = why;
        if (up) {
            up = false;
            listener.onWheelEvent(new WheelEvent.Link(false, nanos.getAsLong()));
        }
    }

    /** Sends a command frame. Sequence numbers are per direction. */
    protected synchronized void send(int type, byte[] payload) {
        Frame frame = new Frame(Frame.VERSION, type, txSeq, payload);
        txSeq = (txSeq + 1) & 0xFFFF;
        write(FrameCodec.encode(frame));
    }

    /** Called with whatever bytes arrived. Decodes and dispatches on the caller's thread. */
    protected void onBytes(byte[] data, int offset, int length) {
        List<Frame> frames;
        synchronized (this) {
            frames = codec.feed(data, offset, length);
        }
        long now = nanos.getAsLong();
        for (Frame frame : frames) {
            handle(frame, now);
        }
    }

    private void handle(Frame frame, long now) {
        List<WheelEvent> events = new ArrayList<>(2);
        synchronized (this) {
            framesReceived++;
            if (lastSeq >= 0) {
                int gap = (frame.seq() - lastSeq - 1) & 0xFFFF;
                if (gap > 0 && gap < 0x8000) {
                    framesLost += gap;
                }
            }
            lastSeq = frame.seq();
            lastFrameNanos = now;
            if (frame.version() != Frame.VERSION) {
                protocolErrors++;
                log.warn("Wheel frame with protocol version {} (this app speaks {}); dropped",
                        frame.version(), Frame.VERSION);
                return;
            }
            WheelMessage message;
            try {
                message = WheelMessages.decode(frame);
            } catch (IllegalArgumentException e) {
                protocolErrors++;
                log.warn("Wheel frame rejected: {}", e.getMessage());
                return;
            }
            toEvents(message, now, events);
        }
        if (!up) {
            up = true;
            reason = null;
            log.info("Wheel link UP ({})", name);
            listener.onWheelEvent(new WheelEvent.Link(true, now));
        }
        for (WheelEvent event : events) {
            listener.onWheelEvent(event);
        }
    }

    private void toEvents(WheelMessage message, long now, List<WheelEvent> out) {
        switch (message) {
            case Hello h -> {
                hello = h;
                log.info("SAMD21 HELLO: protocol {}, firmware {}, {} channels, {} Hz sampling, "
                                + "{} Hz tick, reset cause 0x{}", h.protocolVersion(),
                        h.firmwareVersion(), h.channels(), h.sampleRateHz(), h.tickHz(),
                        Integer.toHexString(h.resetCause()));
                onHello(h);
            }
            case Heartbeat hb -> {
                tickClock.sync(tickClock.unwrap(hb.tick()), now);
                for (int i = 0; i < WheelMessages.CHANNELS; i++) {
                    meanUa[i] = hb.meanUa()[i];
                    meanKnown[i] = true;
                }
            }
            case SystemEdge e -> {
                if (!validChannel(e.channel())) {
                    return;
                }
                long tick = tickClock.unwrap(e.tick());
                covered[e.channel()] = e.covered();
                out.add(new WheelEvent.Edge(e.channel(), e.covered(), tick, nanosFor(tick, now),
                        e.levelUa()));
            }
            case SystemPulse p -> {
                if (!validChannel(p.channel())) {
                    return;
                }
                long on = tickClock.unwrap(p.tickOn());
                long off = tickClock.unwrap(p.tickOff());
                // The pulse is the authoritative record, so it also settles the covered state
                // should the uncover edge have been lost.
                covered[p.channel()] = false;
                out.add(new WheelEvent.Pulse(p.channel(), on, off, nanosFor(on, now),
                        nanosFor(off, now), p.peakUa(), p.area()));
            }
            case ChannelFault f -> {
                if (!validChannel(f.channel())) {
                    return;
                }
                if (faults[f.channel()] != f.fault()) {
                    log.warn("Wheel channel {} fault: {} at {} µA", f.channel(), f.fault(), f.levelUa());
                }
                faults[f.channel()] = f.fault();
                out.add(new WheelEvent.Fault(f.channel(), f.fault(), f.levelUa(), now));
            }
            case Ack a -> {
                if (!a.ok()) {
                    log.warn("SAMD21 refused command seq {} with code {}", a.ackedSeq(), a.result());
                }
            }
            case Unknown u -> log.debug("Wheel frame type 0x{} ({} bytes) not handled",
                    Integer.toHexString(u.type()), u.length());
        }
    }

    /** Hook for the serial link to push its detection thresholds once the firmware is known. */
    protected void onHello(Hello hello) {}

    private boolean validChannel(int channel) {
        if (channel < 0 || channel >= WheelMessages.CHANNELS) {
            protocolErrors++;
            log.warn("Wheel event on channel {}, which does not exist", channel);
            return false;
        }
        return true;
    }

    /**
     * Before the first heartbeat there is no fit, so an event is placed at its receipt time. That
     * only affects which tags a pass claims in its first second after a SAMD21 reset.
     */
    private long nanosFor(long tick, long receivedNanos) {
        return tickClock.isSynced() ? tickClock.toNanos(tick) : receivedNanos;
    }

    private void checkLink() {
        try {
            long last = lastFrameNanos;
            if (up && (last == Long.MIN_VALUE || nanos.getAsLong() - last > linkTimeoutNanos)) {
                log.warn("Wheel link DOWN ({}): no frame for over {} ms", name,
                        TimeUnit.NANOSECONDS.toMillis(linkTimeoutNanos));
                java.util.Arrays.fill(covered, false);
                setDown("no frame for over " + TimeUnit.NANOSECONDS.toMillis(linkTimeoutNanos) + " ms");
            }
        } catch (RuntimeException e) {
            log.error("Wheel link check failed", e);
        }
    }

    @Override
    public boolean isUp() {
        return up;
    }

    @Override
    public synchronized WheelLinkStatus status() {
        List<WheelLinkStatus.Channel> channels = new ArrayList<>();
        for (int i = 0; i < WheelMessages.CHANNELS; i++) {
            channels.add(new WheelLinkStatus.Channel(i, "Wheel " + WheelEvent.wheel(i), i % 2 + 1,
                    meanKnown[i] ? meanUa[i] : null, covered[i], faults[i].name()));
        }
        long last = lastFrameNanos;
        Hello h = hello;
        return new WheelLinkStatus(name, up ? "UP" : "DOWN", up ? null : reason,
                h == null ? null : h.firmwareVersion(), h == null ? null : h.protocolVersion(),
                h == null ? null : h.resetCause(), framesReceived, framesLost, codec.crcErrors(),
                codec.malformed(), protocolErrors,
                last == Long.MIN_VALUE ? null
                        : TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - last),
                channels);
    }
}
