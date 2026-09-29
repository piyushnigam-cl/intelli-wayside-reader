package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.wheel.WheelMessage.Heartbeat;
import com.intelli.rfid.wayside.wheel.WheelMessage.Hello;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SAMD21 in software: heartbeats at 1 Hz and plays {@link SimulatedTrain} timelines, all as
 * encoded frames through the same decoder the serial link uses.
 */
public class SimulatedWheelSource extends FramedWheelSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedWheelSource.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wayside-wheel-sim");
        thread.setDaemon(true);
        return thread;
    });
    private final long startNanos = System.nanoTime();
    private int seq;

    public SimulatedWheelSource(long linkTimeoutMs) {
        super("SIMULATED", linkTimeoutMs, System::nanoTime);
    }

    @Override
    protected void open() {
        scheduler.execute(() -> emit(WheelMessage.HELLO, WheelMessages.hello(
                new Hello(1, 20260929L, 4, 5000, 1_000_000, 0x40))));
        scheduler.scheduleAtFixedRate(() -> emit(WheelMessage.HEARTBEAT, WheelMessages.heartbeat(
                new Heartbeat(tickNow(), (System.nanoTime() - startNanos) / 1_000_000_000L, 0,
                        new int[] {2100, 2100, 2100, 2100}))), 0, 1, TimeUnit.SECONDS);
    }

    @Override
    protected void close() {
        scheduler.shutdownNow();
    }

    @Override
    protected void write(byte[] wire) {
        // Commands are accepted and ignored; the simulator has no thresholds to set.
    }

    /** The tick this simulated SAMD21 reads now. */
    public long tickNow() {
        return ((System.nanoTime() - startNanos) / 1000) & 0xFFFFFFFFL;
    }

    /**
     * Plays a train, starting {@code leadMs} from now.
     *
     * @return the host nanos at which the timeline's offset 0 falls
     */
    public long play(SimulatedTrain.Spec spec, SimulatedTrain.Geometry geometry, long leadMs) {
        long zeroNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leadMs);
        long tickZero = (zeroNanos - startNanos) / 1000;
        SimulatedTrain.Timeline timeline = SimulatedTrain.timeline(spec, geometry, tickZero);
        for (SimulatedTrain.TimedMessage message : timeline.messages()) {
            long delay = zeroNanos + message.offsetNanos() - System.nanoTime();
            scheduler.schedule(() -> emit(message.type(), message.payload()),
                    Math.max(0, delay), TimeUnit.NANOSECONDS);
        }
        log.info("Simulating a {} train at {} km/h: {} axles over {} ms", spec.direction(),
                spec.speedKmh(), spec.axleOffsetsM().size(),
                TimeUnit.NANOSECONDS.toMillis(timeline.durationNanos()));
        return zeroNanos;
    }

    private void emit(int type, byte[] payload) {
        byte[] wire;
        synchronized (this) {
            wire = FrameCodec.encode(new Frame(Frame.VERSION, type, seq, payload));
            seq = (seq + 1) & 0xFFFF;
        }
        onBytes(wire, 0, wire.length);
    }
}
