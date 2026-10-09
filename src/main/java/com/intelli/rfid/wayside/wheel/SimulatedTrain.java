package com.intelli.rfid.wayside.wheel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A train's passage over the proposed Charkop layout (design sec.2.1), as timed wheel messages and
 * tag passes. Pure arithmetic, so the pass logic can be driven end to end with no firmware.
 *
 * <pre>
 *   x = 0          x = antennaM             x = headSpacingM
 *   Wheel 1 ...... antenna ................ Wheel 2       UP travels +x (Wheel 1 first)
 *   (J23, ch 2/3)                           (J22, ch 0/1)
 * </pre>
 *
 * Elements sit at ±systemSpacing/2 about each sensor. Following the installation rule in
 * {@link WheelEvent}, element 2 (pin 4) is on the Wheel 1 side, so an UP wheel covers channel 3
 * before 2 and 1 before 0. A wheel covers an element for {@code footprintM / v}.
 */
public final class SimulatedTrain {

    /** A standard EMU car: 21.3 m, bogie centres 14.6 m apart, 2.5 m wheelbase. Illustrative. */
    public static final double CAR_M = 21.3;
    public static final double BOGIE_CENTRES_M = 14.6;
    public static final double WHEELBASE_M = 2.5;

    public enum Direction { UP, DOWN }

    /**
     * @param axleOffsetsM each axle's distance behind the train's front, ascending
     * @param frontTagM    tag distance behind the front (the front tag sits at the headstock, ~0)
     * @param rearTagM     rear tag distance behind the front
     */
    public record Spec(Direction direction, double speedKmh, List<Double> axleOffsetsM,
                       String frontEpc, double frontTagM, String rearEpc, double rearTagM) {}

    public record Geometry(double headSpacingM, double antennaM, double systemSpacingM,
                           double footprintM, double approachM) {}

    /** A wheel message due at {@code offsetNanos} after the simulation start. */
    public record TimedMessage(long offsetNanos, int type, byte[] payload) {}

    /** A tag passing the antenna, centred on {@code offsetNanos}. */
    public record TagPass(long offsetNanos, String epc) {}

    public record Timeline(List<TimedMessage> messages, List<TagPass> tags, long durationNanos) {}

    private SimulatedTrain() {}

    /** Axle offsets for {@code cars} standard cars coupled front to back. */
    public static List<Double> standardCars(int cars) {
        List<Double> offsets = new ArrayList<>();
        double overhang = (CAR_M - BOGIE_CENTRES_M) / 2 - WHEELBASE_M / 2;
        for (int car = 0; car < cars; car++) {
            double start = car * CAR_M + overhang;
            offsets.add(start);
            offsets.add(start + WHEELBASE_M);
            offsets.add(start + BOGIE_CENTRES_M);
            offsets.add(start + BOGIE_CENTRES_M + WHEELBASE_M);
        }
        return offsets;
    }

    /**
     * @param tickZero the tick the SAMD21 would read at offset 0, so frames carry plausible ticks
     */
    public static Timeline timeline(Spec spec, Geometry g, long tickZero) {
        double v = spec.speedKmh() / 3.6;
        if (v <= 0) {
            throw new IllegalArgumentException("speedKmh must be > 0");
        }
        // Rail position of channels 0..3: Wheel 2's elements 1 and 2, then Wheel 1's.
        double[] systems = {
                g.headSpacingM() + g.systemSpacingM() / 2, g.headSpacingM() - g.systemSpacingM() / 2,
                g.systemSpacingM() / 2, -g.systemSpacingM() / 2};
        List<TimedMessage> messages = new ArrayList<>();
        long end = 0;
        for (double offset : spec.axleOffsetsM()) {
            for (int channel = 0; channel < 4; channel++) {
                double centre = crossing(spec, g, v, offset, systems[channel]);
                double half = g.footprintM() / 2 / v;
                long on = seconds(centre - half);
                long off = seconds(centre + half);
                long tickOn = tickZero + on / 1000;
                long tickOff = tickZero + off / 1000;
                messages.add(new TimedMessage(on, WheelMessage.SYSTEM_EDGE, WheelMessages.edge(
                        new WheelMessage.SystemEdge(tickOn & 0xFFFFFFFFL, channel, true, 9800))));
                messages.add(new TimedMessage(off, WheelMessage.SYSTEM_EDGE, WheelMessages.edge(
                        new WheelMessage.SystemEdge(tickOff & 0xFFFFFFFFL, channel, false, 2100))));
                messages.add(new TimedMessage(off + 1, WheelMessage.SYSTEM_PULSE,
                        WheelMessages.pulse(new WheelMessage.SystemPulse(channel,
                                tickOn & 0xFFFFFFFFL, tickOff & 0xFFFFFFFFL, 9800,
                                (tickOff - tickOn) * 7700))));
                end = Math.max(end, off);
            }
        }
        messages.sort(Comparator.comparingLong(TimedMessage::offsetNanos));
        List<TagPass> tags = new ArrayList<>();
        if (spec.frontEpc() != null && !spec.frontEpc().isBlank()) {
            tags.add(new TagPass(seconds(crossing(spec, g, v, spec.frontTagM(), g.antennaM())),
                    spec.frontEpc()));
        }
        if (spec.rearEpc() != null && !spec.rearEpc().isBlank()) {
            tags.add(new TagPass(seconds(crossing(spec, g, v, spec.rearTagM(), g.antennaM())),
                    spec.rearEpc()));
        }
        tags.sort(Comparator.comparingLong(TagPass::offsetNanos));
        return new Timeline(messages, tags, end);
    }

    /** When the point {@code offset} metres behind the front is over rail position {@code x}. */
    private static double crossing(Spec spec, Geometry g, double v, double offset, double x) {
        if (spec.direction() == Direction.UP) {
            double front = -g.approachM();
            return (x - front + offset) / v;
        }
        double front = g.headSpacingM() + g.approachM();
        return (front + offset - x) / v;
    }

    private static long seconds(double s) {
        return Math.round(s * 1e9);
    }
}
