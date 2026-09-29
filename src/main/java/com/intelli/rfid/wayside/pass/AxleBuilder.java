package com.intelli.rfid.wayside.pass;

import com.intelli.rfid.wayside.wheel.WheelEvent;
import com.intelli.rfid.wayside.wheel.WheelEvent.Pulse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Pairs each head's two systems into axles and derives direction, speed and the per-head counts
 * (design sec.5.3). Pure: a list of pulses in, one {@link Analysis} out.
 *
 * <p>Channels are numbered in the A→B direction along the rail (see {@link WheelEvent}), so at a
 * head, system 1 before system 2 means the wheel was travelling A→B.
 */
public final class AxleBuilder {

    /**
     * @param upIsAToB        whether A→B is UP
     * @param headSpacingM    rail distance head A → head B; 0 = no head-to-head speed
     * @param systemSpacingM  distance between a head's two systems; 0 = no single-head fallback
     * @param systemPairMaxUs longest gap between a head's two systems seeing one wheel
     */
    public record Config(boolean upIsAToB, double headSpacingM, double systemSpacingM,
                         long systemPairMaxUs) {}

    /** One axle as the pass result reports it. Nulls where a head did not see it. */
    public record Axle(int n, Instant atA, Instant atB, Double speedKmh, List<Integer> peakUa) {}

    public record Analysis(Direction direction, int headA, int headB, boolean consistent,
                           Double speedMin, Double speedMean, Double speedMax, List<Axle> axles,
                           List<String> notes) {}

    /** One wheel seen by both systems of one head. */
    record HeadAxle(long centreTick, long centreNanos, boolean aToB, long systemGapTicks,
                    int peak1, int peak2) {}

    private AxleBuilder() {}

    public static Analysis analyse(List<Pulse> pulses, Config config, LongFunction<Instant> wall) {
        List<String> notes = new ArrayList<>();
        List<HeadAxle> a = pair(pulses, 0, config, notes);
        List<HeadAxle> b = pair(pulses, 1, config, notes);
        boolean consistent = a.size() == b.size();
        Direction direction = direction(a, b, config, notes);

        int n = Math.max(a.size(), b.size());
        List<Axle> axles = new ArrayList<>(n);
        List<Double> speeds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            HeadAxle atA = i < a.size() ? a.get(i) : null;
            HeadAxle atB = i < b.size() ? b.get(i) : null;
            Double speed = speed(atA, atB, a, b, direction, consistent, config);
            if (speed != null) {
                speeds.add(speed);
            }
            axles.add(new Axle(i + 1,
                    atA == null ? null : wall.apply(atA.centreNanos()),
                    atB == null ? null : wall.apply(atB.centreNanos()),
                    speed == null ? null : round1(speed),
                    Arrays.asList(atA == null ? null : atA.peak1(), atA == null ? null : atA.peak2(),
                            atB == null ? null : atB.peak1(), atB == null ? null : atB.peak2())));
        }
        if (!consistent) {
            notes.add("axle count disagrees between heads: A " + a.size() + ", B " + b.size());
        }
        Double min = speeds.stream().min(Double::compare).map(AxleBuilder::round1).orElse(null);
        Double max = speeds.stream().max(Double::compare).map(AxleBuilder::round1).orElse(null);
        Double mean = speeds.isEmpty() ? null
                : round1(speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        return new Analysis(direction, a.size(), b.size(), consistent, min, mean, max, axles, notes);
    }

    /** Greedy, in time order: each pulse takes the first later pulse on the other system in range. */
    static List<HeadAxle> pair(List<Pulse> pulses, int head, Config config, List<String> notes) {
        List<Pulse> mine = new ArrayList<>();
        for (Pulse p : pulses) {
            if (WheelEvent.head(p.channel()) == head) {
                mine.add(p);
            }
        }
        mine.sort(Comparator.comparingLong(Pulse::centreTick));
        boolean[] used = new boolean[mine.size()];
        List<HeadAxle> axles = new ArrayList<>();
        int orphans = 0;
        for (int i = 0; i < mine.size(); i++) {
            if (used[i]) {
                continue;
            }
            Pulse p = mine.get(i);
            int match = -1;
            for (int j = i + 1; j < mine.size(); j++) {
                Pulse q = mine.get(j);
                if (q.centreTick() - p.centreTick() > config.systemPairMaxUs()) {
                    break;
                }
                if (!used[j] && WheelEvent.system(q.channel()) != WheelEvent.system(p.channel())) {
                    match = j;
                    break;
                }
            }
            if (match < 0) {
                orphans++;
                continue;
            }
            used[i] = true;
            used[match] = true;
            Pulse q = mine.get(match);
            Pulse first = WheelEvent.system(p.channel()) == 0 ? p : q;
            Pulse second = first == p ? q : p;
            boolean aToB = WheelEvent.system(p.channel()) == 0;
            long centreNanos = ((p.onNanos() + p.offNanos()) / 2 + (q.onNanos() + q.offNanos()) / 2) / 2;
            axles.add(new HeadAxle((p.centreTick() + q.centreTick()) / 2, centreNanos, aToB,
                    q.centreTick() - p.centreTick(), first.peakUa(), second.peakUa()));
        }
        if (orphans > 0) {
            notes.add("head " + (head == 0 ? "A" : "B") + ": " + orphans
                    + " pulse(s) with no partner on the other system");
        }
        return axles;
    }

    static Direction direction(List<HeadAxle> a, List<HeadAxle> b, Config config, List<String> notes) {
        Boolean dirA = uniform(a);
        Boolean dirB = uniform(b);
        if ((!a.isEmpty() && dirA == null) || (!b.isEmpty() && dirB == null)) {
            return Direction.MIXED;
        }
        if (a.isEmpty() || b.isEmpty()) {
            if (!(a.isEmpty() && b.isEmpty())) {
                notes.add("only head " + (a.isEmpty() ? "B" : "A") + " saw the train; direction "
                        + "needs both");
            }
            return Direction.UNKNOWN;
        }
        if (!dirA.equals(dirB)) {
            notes.add("heads disagree on direction");
            return Direction.UNKNOWN;
        }
        // Third, independent witness: which head the first axle reached first.
        boolean headOrderAToB = a.get(0).centreTick() < b.get(0).centreTick();
        if (headOrderAToB != dirA) {
            notes.add("head order contradicts the system order at both heads");
            return Direction.UNKNOWN;
        }
        return dirA == config.upIsAToB() ? Direction.UP : Direction.DOWN;
    }

    /** true = all A→B, false = all B→A, null = both seen. Empty is true, and callers check. */
    private static Boolean uniform(List<HeadAxle> axles) {
        boolean anyAToB = axles.stream().anyMatch(HeadAxle::aToB);
        boolean anyBToA = axles.stream().anyMatch(x -> !x.aToB());
        if (anyAToB && anyBToA) {
            return null;
        }
        return !anyBToA;
    }

    private static Double speed(HeadAxle atA, HeadAxle atB, List<HeadAxle> a, List<HeadAxle> b,
                                Direction direction, boolean consistent, Config config) {
        boolean known = direction == Direction.UP || direction == Direction.DOWN;
        if (config.headSpacingM() > 0 && consistent && known && atA != null && atB != null) {
            long dt = Math.abs(atB.centreTick() - atA.centreTick());
            return dt == 0 ? null : config.headSpacingM() / (dt / 1e6) * 3.6;
        }
        // Coarse fallback, only when one head is down (design sec.5.3).
        boolean oneHead = a.isEmpty() != b.isEmpty();
        HeadAxle only = atA != null ? atA : atB;
        if (config.systemSpacingM() > 0 && oneHead && only != null && only.systemGapTicks() > 0) {
            return config.systemSpacingM() / (only.systemGapTicks() / 1e6) * 3.6;
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
