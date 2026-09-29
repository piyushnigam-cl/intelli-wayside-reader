package com.intelli.rfid.wayside.pass;

import com.intelli.rfid.core.model.TagRead;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import com.intelli.rfid.wayside.wheel.WheelMessage.FaultKind;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One train, one pass (design sec.5.1).
 *
 * <pre>
 * IDLE ──first covered edge──► OCCUPIED ──no wheel event for axle-gap-ms, nothing covered──► TAIL
 *   ▲                          (carrier on)                                   (carrier on rfid-tail-ms)
 *   └──────────────────── publish, carrier off ◄──────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Degraded</b>: with the wheel link down a pass opens on the first tag and closes on
 * {@code tag-gap-ms} of silence since the last read — silence since the last read, not the last new
 * tag, because a train is a moving population and quiet means it has gone. Identity is still
 * delivered; axles, direction and speed are null.
 *
 * <p><b>Not thread-safe, by design.</b> Every input — wheel events, tag batches, the tick — must
 * arrive on one thread; {@code PassService} owns that thread. That keeps the state machine free of
 * locks and makes it testable by calling it directly with a fake clock.
 */
public class PassTracker {

    private static final Logger log = LoggerFactory.getLogger(PassTracker.class);

    /** Host time: monotonic nanos, and the wall-clock instant for any nanos value. */
    public interface Clocks {
        long nanos();

        Instant wall(long nanos);
    }

    /** Told when a pass wants the carrier, and when it no longer does. */
    public interface Carrier {
        void passOpen(boolean open);
    }

    /** A closed pass, before the service stamps id, sequence and reader identity on it. */
    public record ClosedPass(Instant startedAt, Instant endedAt, StopReason stopReason,
                             PassResult.Train train, List<PassResult.Tag> tags,
                             PassResult.Wheels wheels) {}

    public enum State { IDLE, OCCUPIED, TAIL }

    private record Seen(TagRead read, long nanos) {}

    private static final class TagAgg {
        final String epc;
        String tid;
        Instant first;
        Instant last;
        int reads;
        int bestRssi = Integer.MIN_VALUE;

        TagAgg(String epc) {
            this.epc = epc;
        }

        void add(TagRead read) {
            if (tid == null) {
                tid = read.tid();
            }
            Instant at = read.seenAt();
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || at.isAfter(last)) {
                last = at;
            }
            reads += Math.max(1, read.readCount());
            bestRssi = Math.max(bestRssi, read.rssi());
        }
    }

    private final WaysideProperties.Pass config;
    private final AxleBuilder.Config axleConfig;
    private final int tagsExpected;
    private final TrainIdDecoder decoder;
    private final Clocks clocks;
    private final Carrier carrier;
    private final Consumer<ClosedPass> publisher;

    private final Deque<Seen> recent = new ArrayDeque<>();
    private final boolean[] covered = new boolean[4];
    private final Map<Integer, FaultKind> faults = new TreeMap<>();

    private State state = State.IDLE;
    private boolean linkUp;
    private boolean degraded;
    private boolean linkLost;
    private long openedNanos;
    private long lastWheelNanos;
    private long lastTagNanos;
    private long tailStartNanos;
    private final List<WheelEvent.Pulse> pulses = new ArrayList<>();
    private final Map<String, TagAgg> tags = new LinkedHashMap<>();
    private final List<String> passFaults = new ArrayList<>();

    public PassTracker(WaysideProperties properties, TrainIdDecoder decoder, Clocks clocks,
                       Carrier carrier, Consumer<ClosedPass> publisher) {
        this.config = properties.getPass();
        WaysideProperties.Wheel wheel = properties.getWheel();
        this.axleConfig = new AxleBuilder.Config(wheel.isUpIsAToB(), wheel.getHeadSpacingM(),
                wheel.getSystemSpacingM(), TimeUnit.MILLISECONDS.toMicros(wheel.getSystemPairMaxMs()));
        this.tagsExpected = properties.getTrain().getTagsExpected();
        this.decoder = decoder;
        this.clocks = clocks;
        this.carrier = carrier;
        this.publisher = publisher;
    }

    // ---------------------------------------------------------------- inputs

    public void onWheel(WheelEvent event) {
        long now = clocks.nanos();
        switch (event) {
            case WheelEvent.Link link -> onLink(link.up());
            case WheelEvent.Edge edge -> {
                covered[edge.channel()] = edge.covered();
                if (edge.covered()) {
                    if (state == State.IDLE) {
                        open(Math.min(edge.atNanos(), now), false);
                    } else if (state == State.TAIL) {
                        log.debug("Wheel again during the tail; the same train is still passing");
                        state = State.OCCUPIED;
                    }
                }
                lastWheelNanos = now;
            }
            case WheelEvent.Pulse pulse -> {
                covered[pulse.channel()] = false;
                if (state == State.IDLE) {
                    // Its covered edge was lost. The pulse is authoritative, so it still opens.
                    open(Math.min(pulse.onNanos(), now), false);
                } else if (state == State.TAIL) {
                    state = State.OCCUPIED;
                }
                pulses.add(pulse);
                lastWheelNanos = now;
            }
            case WheelEvent.Fault fault -> {
                if (fault.fault() == FaultKind.NONE) {
                    faults.remove(fault.channel());
                } else {
                    faults.put(fault.channel(), fault.fault());
                    if (state != State.IDLE) {
                        passFaults.add(channelName(fault.channel()) + ": " + fault.fault());
                    }
                }
            }
        }
    }

    private void onLink(boolean up) {
        linkUp = up;
        if (!up) {
            java.util.Arrays.fill(covered, false);
            if (state != State.IDLE && !degraded) {
                linkLost = true;
                passFaults.add("wheel link lost during the pass");
            }
        }
    }

    public void onTags(List<TagRead> reads) {
        long now = clocks.nanos();
        for (TagRead read : reads) {
            recent.addLast(new Seen(read, now));
        }
        trimRecent(now);
        if (state == State.IDLE) {
            if (linkUp || reads.isEmpty()) {
                // With the wheels up, only a wheel opens a pass. Tags heard while idle (carrier
                // ALWAYS) wait in the lead buffer in case a wheel follows.
                return;
            }
            open(now, true);
            return;
        }
        for (TagRead read : reads) {
            tags.computeIfAbsent(read.epc(), TagAgg::new).add(read);
        }
        lastTagNanos = now;
    }

    /** Called every ~100 ms. Every timeout lives here. */
    public void tick() {
        long now = clocks.nanos();
        trimRecent(now);
        if (state == State.IDLE) {
            return;
        }
        if (now - openedNanos >= ms(config.getMaxPassMs())) {
            for (int c = 0; c < covered.length; c++) {
                if (covered[c]) {
                    passFaults.add(channelName(c) + ": still covered when the pass timed out");
                }
            }
            close(StopReason.TIMEOUT, now);
            return;
        }
        if (degraded) {
            if (now - lastTagNanos >= ms(config.getTagGapMs())) {
                close(StopReason.TAG_GAP, now);
            }
            return;
        }
        if (state == State.OCCUPIED && now - lastWheelNanos >= ms(config.getAxleGapMs())
                && (!anyCovered() || !linkUp)) {
            state = State.TAIL;
            tailStartNanos = now;
            log.debug("Wheels clear; holding the carrier {} ms for the trailing tag",
                    config.getRfidTailMs());
        } else if (state == State.TAIL && now - tailStartNanos >= ms(config.getRfidTailMs())) {
            close(StopReason.CLEARED, now);
        }
    }

    // ---------------------------------------------------------------- transitions

    private void open(long atNanos, boolean degradedPass) {
        state = State.OCCUPIED;
        degraded = degradedPass;
        linkLost = false;
        openedNanos = atNanos;
        lastWheelNanos = clocks.nanos();
        lastTagNanos = clocks.nanos();
        pulses.clear();
        tags.clear();
        passFaults.clear();
        faults.forEach((channel, fault) -> passFaults.add(channelName(channel) + ": " + fault));
        long from = atNanos - ms(config.getRfidLeadMs());
        for (Seen seen : recent) {
            if (seen.nanos() >= from) {
                tags.computeIfAbsent(seen.read().epc(), TagAgg::new).add(seen.read());
            }
        }
        log.info("Pass opened ({})", degradedPass ? "RFID only: wheel link down" : "wheel");
        carrier.passOpen(true);
    }

    private void close(StopReason reason, long now) {
        List<PassResult.Tag> tagList = tags.values().stream()
                .sorted(Comparator.comparing(t -> t.first))
                .map(t -> {
                    String trainId = decoder.decode(t.epc);
                    return new PassResult.Tag(t.epc, t.tid, trainId != null, trainId, t.first,
                            t.last, t.reads, t.bestRssi);
                })
                .toList();
        ClosedPass pass = new ClosedPass(clocks.wall(openedNanos), clocks.wall(now), reason,
                train(tagList), tagList, wheels());
        state = State.IDLE;
        degraded = false;
        carrier.passOpen(false);
        log.info("Pass closed {}: {} tag(s), train {}, direction {}, axles {}", reason,
                tagList.size(), pass.train().id(), pass.wheels().direction(),
                pass.wheels().axleCount() == null ? "-" : pass.wheels().axleCount().headA() + "/"
                        + pass.wheels().axleCount().headB());
        publisher.accept(pass);
    }

    /**
     * Complete = at least {@code tagsExpected} tags that all decode to the same train. In RAW mode
     * nothing decodes, so nothing is ever complete — the honest answer while the encoding is unknown.
     */
    private PassResult.Train train(List<PassResult.Tag> tagList) {
        List<String> ids = tagList.stream().filter(PassResult.Tag::decoded)
                .map(PassResult.Tag::trainId).distinct().toList();
        long decodedTags = tagList.stream().filter(PassResult.Tag::decoded).count();
        String id = ids.size() == 1 ? ids.get(0) : null;
        boolean complete = id != null && decodedTags >= tagsExpected;
        return new PassResult.Train(id, id != null, tagsExpected, tagList.size(), complete);
    }

    private PassResult.Wheels wheels() {
        List<String> faultList = List.copyOf(new java.util.LinkedHashSet<>(passFaults));
        if (degraded && pulses.isEmpty()) {
            return new PassResult.Wheels("DOWN", null, null, null, null, faultList);
        }
        AxleBuilder.Analysis analysis = AxleBuilder.analyse(pulses, axleConfig, clocks::wall);
        List<String> all = new ArrayList<>(faultList);
        all.addAll(analysis.notes());
        String link = degraded ? "DOWN" : linkLost ? "LOST" : "OK";
        return new PassResult.Wheels(link, analysis.direction(),
                new PassResult.AxleCount(analysis.headA(), analysis.headB(), analysis.consistent()),
                new PassResult.Speed(analysis.speedMin(), analysis.speedMean(), analysis.speedMax()),
                analysis.axles(), all);
    }

    // ---------------------------------------------------------------- helpers

    private void trimRecent(long now) {
        long keep = ms(Math.max(config.getRfidLeadMs(), 0));
        while (!recent.isEmpty() && now - recent.peekFirst().nanos() > keep) {
            recent.removeFirst();
        }
    }

    private boolean anyCovered() {
        for (boolean c : covered) {
            if (c) {
                return true;
            }
        }
        return false;
    }

    private static String channelName(int channel) {
        return "head " + (channel < 2 ? "A" : "B") + " system " + (channel % 2 + 1);
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    public State state() {
        return state;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean isLinkUp() {
        return linkUp;
    }
}
