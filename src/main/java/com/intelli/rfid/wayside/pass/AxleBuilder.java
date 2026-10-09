package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import com.intelli.rfid.wayside.wheel.WheelEvent.Pulse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Pairs each wheel sensor's two elements into axles and derives direction, speed and the
 * per-sensor counts (design sec.5.3). Pure: a list of pulses in, one {@link Analysis} out.
 *
 * <p><b>Wheel 1 (J23) seeing the train first is UP, Wheel 2 (J22) first is DOWN</b> (operator,
 * 2026-10-09). Not configurable. Three witnesses must agree before a direction is named: which
 * sensor the first axle reached first, and the element order at each sensor (see
 * {@link WheelEvent} for the installation rule that makes element order mean anything).
 */
public final class AxleBuilder {

    /**
     * @param sensorSpacingM  rail distance Wheel 1 → Wheel 2; 0 = no sensor-to-sensor speed
     * @param elementSpacingM distance between one sensor's two elements; 0 = no per-sensor speed
     * @param systemPairMaxUs longest gap between one sensor's two elements seeing one wheel
     */
    public record Config(double sensorSpacingM, double elementSpacingM, long systemPairMaxUs) {}

    /**
     * One axle as the pass result reports it. Nulls where a sensor did not see it, or where the
     * distance a speed needs is not configured.
     *
     * <p>{@code atA}/{@code atB} keep their schema-1 names: A is Wheel 2 (J22), B is Wheel 1 (J23).
     * {@code speedKmh} is the sensor-to-sensor speed, or, when that cannot be had, the mean of the
     * speeds at the two sensors. {@code peakUa} is in channel order: Wheel 2 elements 1 and 2, then
     * Wheel 1 elements 1 and 2.
     */
    public record Axle(int n,
                       @JsonProperty("atA") Instant atWheel2,
                       @JsonProperty("atB") Instant atWheel1,
                       Double speedKmh, Double speedAtWheel1Kmh, Double speedAtWheel2Kmh,
                       List<Integer> peakUa) {}

    public record Analysis(Direction direction, int wheel1, int wheel2, boolean consistent,
                           Double speedMin, Double speedMean, Double speedMax, List<Axle> axles,
                           List<String> notes) {}

    /**
     * One wheel seen by both elements of one sensor. {@code up} is the element order: element 2
     * (the Wheel 1 side) before element 1.
     */
    record SensorAxle(long centreTick, long centreNanos, boolean up, long systemGapTicks,
                      int peak1, int peak2) {}

    private static final int WHEEL_2 = 0;
    private static final int WHEEL_1 = 1;

    private AxleBuilder() {}

    public static Analysis analyse(List<Pulse> pulses, Config config, LongFunction<Instant> wall) {
        List<String> notes = new ArrayList<>();
        List<SensorAxle> w1 = pair(pulses, WHEEL_1, config, notes);
        List<SensorAxle> w2 = pair(pulses, WHEEL_2, config, notes);
        boolean consistent = w1.size() == w2.size();
        Direction direction = direction(w1, w2, notes);

        int n = Math.max(w1.size(), w2.size());
        List<Axle> axles = new ArrayList<>(n);
        List<Double> speeds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SensorAxle at1 = i < w1.size() ? w1.get(i) : null;
            SensorAxle at2 = i < w2.size() ? w2.get(i) : null;
            Double between = betweenSensors(at1, at2, direction, consistent, config);
            Double atWheel1 = atSensor(at1, config);
            Double atWheel2 = atSensor(at2, config);
            Double speed = between != null ? between : mean(atWheel1, atWheel2);
            if (speed != null) {
                speeds.add(speed);
            }
            axles.add(new Axle(i + 1,
                    at2 == null ? null : wall.apply(at2.centreNanos()),
                    at1 == null ? null : wall.apply(at1.centreNanos()),
                    round1(speed), round1(atWheel1), round1(atWheel2),
                    Arrays.asList(at2 == null ? null : at2.peak1(), at2 == null ? null : at2.peak2(),
                            at1 == null ? null : at1.peak1(), at1 == null ? null : at1.peak2())));
        }
        if (!consistent) {
            notes.add("axle count disagrees between sensors: Wheel 1 " + w1.size() + ", Wheel 2 "
                    + w2.size());
        }
        Double min = round1(speeds.stream().min(Double::compare).orElse(null));
        Double max = round1(speeds.stream().max(Double::compare).orElse(null));
        Double avg = speeds.isEmpty() ? null
                : round1(speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        return new Analysis(direction, w1.size(), w2.size(), consistent, min, avg, max, axles, notes);
    }

    /**
     * Mutual nearest neighbours: a pulse pairs with the closest pulse on the sensor's other element,
     * within {@code systemPairMaxUs}, only if that pulse's closest is this one. A greedy pairing in
     * time order would join a wheel whose partner was missed to the next axle's pulse, and the
     * speed at that sensor would be confidently wrong.
     */
    static List<SensorAxle> pair(List<Pulse> pulses, int head, Config config, List<String> notes) {
        List<Pulse> mine = new ArrayList<>();
        for (Pulse p : pulses) {
            if (WheelEvent.head(p.channel()) == head) {
                mine.add(p);
            }
        }
        mine.sort(Comparator.comparingLong(Pulse::centreTick));
        int[] nearest = new int[mine.size()];
        for (int i = 0; i < mine.size(); i++) {
            nearest[i] = nearestPartner(mine, i, config.systemPairMaxUs());
        }
        List<SensorAxle> axles = new ArrayList<>();
        int orphans = 0;
        for (int i = 0; i < mine.size(); i++) {
            int j = nearest[i];
            if (j < 0 || nearest[j] != i) {
                orphans++;
                continue;
            }
            if (j < i) {
                continue;   // already added from the earlier pulse
            }
            Pulse p = mine.get(i);
            Pulse q = mine.get(j);
            Pulse element1 = WheelEvent.system(p.channel()) == 0 ? p : q;
            Pulse element2 = element1 == p ? q : p;
            boolean up = WheelEvent.system(p.channel()) == 1;
            long centreNanos = ((p.onNanos() + p.offNanos()) / 2 + (q.onNanos() + q.offNanos()) / 2) / 2;
            axles.add(new SensorAxle((p.centreTick() + q.centreTick()) / 2, centreNanos, up,
                    q.centreTick() - p.centreTick(), element1.peakUa(), element2.peakUa()));
        }
        if (orphans > 0) {
            notes.add(name(head) + ": " + orphans + " pulse(s) with no partner on the other element");
        }
        return axles;
    }

    /** Index of the closest pulse on the other element within {@code maxUs}, or -1. */
    private static int nearestPartner(List<Pulse> mine, int i, long maxUs) {
        Pulse p = mine.get(i);
        int best = -1;
        long bestGap = Long.MAX_VALUE;
        for (int j = 0; j < mine.size(); j++) {
            Pulse q = mine.get(j);
            if (WheelEvent.system(q.channel()) == WheelEvent.system(p.channel())) {
                continue;
            }
            long gap = Math.abs(q.centreTick() - p.centreTick());
            if (gap <= maxUs && gap < bestGap) {
                best = j;
                bestGap = gap;
            }
        }
        return best;
    }

    static Direction direction(List<SensorAxle> w1, List<SensorAxle> w2, List<String> notes) {
        Boolean up1 = uniform(w1);
        Boolean up2 = uniform(w2);
        if ((!w1.isEmpty() && up1 == null) || (!w2.isEmpty() && up2 == null)) {
            return Direction.MIXED;
        }
        if (w1.isEmpty() || w2.isEmpty()) {
            if (!(w1.isEmpty() && w2.isEmpty())) {
                notes.add("only " + (w1.isEmpty() ? "Wheel 2" : "Wheel 1") + " saw the train; "
                        + "direction needs both");
            }
            return Direction.UNKNOWN;
        }
        if (!up1.equals(up2)) {
            notes.add("the two sensors disagree on element order");
            return Direction.UNKNOWN;
        }
        // The operator's definition: which sensor the first axle reached first.
        boolean wheel1First = w1.get(0).centreTick() < w2.get(0).centreTick();
        if (wheel1First != up1) {
            notes.add("sensor order contradicts the element order at both sensors; check which "
                    + "element is on pin 2 and which on pin 4");
            return Direction.UNKNOWN;
        }
        return wheel1First ? Direction.UP : Direction.DOWN;
    }

    /** true = all UP order, false = all DOWN order, null = both seen. Empty is true; callers check. */
    private static Boolean uniform(List<SensorAxle> axles) {
        boolean anyUp = axles.stream().anyMatch(SensorAxle::up);
        boolean anyDown = axles.stream().anyMatch(x -> !x.up());
        if (anyUp && anyDown) {
            return null;
        }
        return !anyDown;
    }

    /** Wheel 1 to Wheel 2 for the same axle; only when the counts agree and the direction is known. */
    private static Double betweenSensors(SensorAxle at1, SensorAxle at2, Direction direction,
                                         boolean consistent, Config config) {
        boolean known = direction == Direction.UP || direction == Direction.DOWN;
        if (config.sensorSpacingM() <= 0 || !consistent || !known || at1 == null || at2 == null) {
            return null;
        }
        long dt = Math.abs(at2.centreTick() - at1.centreTick());
        return dt == 0 ? null : kmh(config.sensorSpacingM(), dt);
    }

    /** Element spacing over the time between that sensor's two elements seeing the wheel. */
    private static Double atSensor(SensorAxle at, Config config) {
        if (config.elementSpacingM() <= 0 || at == null || at.systemGapTicks() <= 0) {
            return null;
        }
        return kmh(config.elementSpacingM(), at.systemGapTicks());
    }

    private static double kmh(double metres, long micros) {
        return metres / (micros / 1e6) * 3.6;
    }

    private static Double mean(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : (a + b) / 2;
    }

    private static String name(int head) {
        return head == WHEEL_1 ? "Wheel 1" : "Wheel 2";
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }
}
