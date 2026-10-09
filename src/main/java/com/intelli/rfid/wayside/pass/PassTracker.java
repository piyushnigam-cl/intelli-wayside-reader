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
    /** J26 IN1/IN2 bound the pass instead of the wheels (a stand-in until the sensors are fitted). */
    private final boolean gpioTrigger;
    private final boolean in1IsUp;
    /** GPIO mode: which input started the current train (1 or 2), and the direction that gives. */
    private int startedBy;
    private Direction gpioDirection;
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
        this.gpioTrigger = properties.getTrigger().getSource() == WaysideProperties.TriggerSource.GPIO;
        this.in1IsUp = properties.getTrigger().getGpio().isIn1IsUp();
        WaysideProperties.Wheel wheel = properties.getWheel();
        this.axleConfig = new AxleBuilder.Config(wheel.effectiveSensorSpacingM(),
                wheel.getElementSpacingM(), TimeUnit.MILLISECONDS.toMicros(wheel.getSystemPairMaxMs()));
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

    // ---------------------------------------------------------------- GPIO trigger (IN1 / IN2)

    /**
     * A pulse on J26 IN1 ({@code input} 1) or IN2 (2). Operator's rule, 2026-09-29:
     * <ul>
     *   <li>No train: <b>either input starts one</b>, and the one that did gives the direction
     *       (IN1 first = UP by default, {@code in1-is-up}).
     *   <li>A train passing: <b>the other input ends it</b>; the carrier is held rfid-tail-ms for the
     *       rear tag, then it is published. The same input again is a repeat and is ignored.
     *   <li>During that tail: either input is a new train. The previous one is published first.
     * </ul>
     */
    public void onGpioInput(int input, long atNanos) {
        long now = clocks.nanos();
        switch (state) {
            case IDLE -> startGpioTrain(input, atNanos, now);
            case TAIL -> {
                close(StopReason.CLEARED, now);
                startGpioTrain(input, atNanos, now);
            }
            case OCCUPIED -> {
                if (degraded) {
                    log.info("IN{} during an RFID-only pass; ignored", input);
                } else if (input == startedBy) {
                    log.info("IN{} again while its own train is passing; ignored", input);
                } else {
                    state = State.TAIL;
                    tailStartNanos = now;
                    log.info("IN{}: train END; holding the carrier {} ms for the rear tag", input,
                            config.getRfidTailMs());
                }
            }
        }
    }

    private void startGpioTrain(int input, long atNanos, long now) {
        startedBy = input;
        gpioDirection = (input == 1) == in1IsUp ? Direction.UP : Direction.DOWN;
        log.info("IN{}: train START, direction {}", input, gpioDirection);
        open(Math.min(atNanos, now), false);
    }

    /** The GPIO trigger's health, standing in for the wheel link in GPIO mode. */
    public void onTriggerHealth(boolean up) {
        onLink(up);
    }

    private void onLink(boolean up) {
        linkUp = up;
        if (!up) {
            java.util.Arrays.fill(covered, false);
            if (state != State.IDLE && !degraded) {
                linkLost = true;
                if (!gpioTrigger) {
                    passFaults.add("wheel link lost during the pass");
                }
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
        if (!gpioTrigger && state == State.OCCUPIED && now - lastWheelNanos >= ms(config.getAxleGapMs())
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
        log.info("Pass opened ({})", degradedPass
                ? (gpioTrigger ? "RFID only: GPIO trigger down" : "RFID only: wheel link down")
                : (gpioTrigger ? "J26 IN1" : "wheel"));
        carrier.passOpen(true);
    }

    private void close(StopReason reason, long now) {
        int ignored = (int) tags.keySet().stream().filter(decoder::ignores).count();
        List<PassResult.Tag> tagList = tags.values().stream()
                .filter(t -> !decoder.ignores(t.epc))
                .sorted(Comparator.comparing(t -> t.first))
                .map(t -> {
                    String trainId = decoder.decode(t.epc);
                    return new PassResult.Tag(t.epc, t.tid, trainId != null, trainId,
                            decoder.car(t.epc), t.first, t.last, t.reads, t.bestRssi);
                })
                .toList();
        ClosedPass pass = new ClosedPass(clocks.wall(openedNanos), clocks.wall(now), reason,
                train(tagList, ignored), tagList, wheels());
        state = State.IDLE;
        degraded = false;
        carrier.passOpen(false);
        log.info("Pass closed {}: {} tag(s), train {}, direction {}, axles {}", reason,
                tagList.size(), pass.train().id(), pass.wheels().direction(),
                pass.wheels().axleCount() == null ? "-" : pass.wheels().axleCount().wheel1() + "/"
                        + pass.wheels().axleCount().wheel2());
        publisher.accept(pass);
    }

    /**
     * Complete = at least {@code tagsExpected} tags that all decode to the same train. In RAW mode
     * nothing decodes, so nothing is ever complete — the honest answer while the encoding is unknown.
     */
    /**
     * Complete = every decoded tag names ONE train, and at least {@code tagsExpected} distinct
     * positions of it were read. For car tags a position is the car position, so both ends
     * (DMC-1 and DMC-2) are needed; the same DMC read twice is still one end.
     */
    private PassResult.Train train(List<PassResult.Tag> tagList, int ignored) {
        List<PassResult.Tag> decodedTags = tagList.stream().filter(PassResult.Tag::decoded).toList();
        List<String> ids = decodedTags.stream().map(PassResult.Tag::trainId).distinct().toList();
        String id = ids.size() == 1 ? ids.get(0) : null;
        long positions = decodedTags.stream()
                .map(t -> t.car() != null ? "pos" + t.car().position() : t.epc())
                .distinct().count();
        boolean complete = id != null && positions >= tagsExpected;
        String line = id == null ? null : decodedTags.stream()
                .map(t -> t.epc().substring(3, 5)).findFirst().orElse(null);
        return new PassResult.Train(id, line, id != null, tagsExpected, tagList.size(), ignored,
                complete);
    }

    private PassResult.Wheels wheels() {
        List<String> faultList = List.copyOf(new java.util.LinkedHashSet<>(passFaults));
        if (degraded && pulses.isEmpty()) {
            return new PassResult.Wheels("DOWN", null, null, null, null, faultList);
        }
        if (gpioTrigger) {
            // Boundaries and direction from J26 IN1/IN2 (which fired first). No axles or speed
            // exist to report, so none are invented.
            List<String> all = new ArrayList<>(faultList);
            if (linkLost) {
                all.add("GPIO trigger lost during the pass");
            }
            return new PassResult.Wheels("GPIO", gpioDirection, null, null, null, all);
        }
        AxleBuilder.Analysis analysis = AxleBuilder.analyse(pulses, axleConfig, clocks::wall);
        List<String> all = new ArrayList<>(faultList);
        all.addAll(analysis.notes());
        String link = degraded ? "DOWN" : linkLost ? "LOST" : "OK";
        return new PassResult.Wheels(link, analysis.direction(),
                new PassResult.AxleCount(analysis.wheel2(), analysis.wheel1(), analysis.consistent()),
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
        return WheelEvent.name(channel);
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
