package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.wheel.WheelMessage.FaultKind;

/**
 * What the pass logic sees from the wheel sensors: ticks already unwrapped to a 64-bit µs line,
 * and host times ({@code System.nanoTime()} domain) already estimated through {@link TickClock}.
 *
 * <p>Channels 0 and 1 are <b>Wheel 2</b>'s two sensing elements (J22 pins 2 and 4), 2 and 3 are
 * <b>Wheel 1</b>'s (J23 pins 2 and 4). The names follow the board's silk (operator, 2026-10-09),
 * although the PCB put the {@code WSA*} nets on J22 and {@code WSB*} on J23.
 *
 * <p>Along the rail the channels run 0, 1, 2, 3 from the Wheel 2 end to the Wheel 1 end: in each
 * RSR110d the element on pin 4 (element 2) is the one on the Wheel 1 side. An UP train (Wheel 1
 * first) therefore covers element 2 before element 1 at each sensor. That is an installation rule,
 * and a sensor wired the other way makes every pass's direction UNKNOWN, which is the safe failure.
 */
public sealed interface WheelEvent {

    long atNanos();

    /** A system covered or uncovered. Arrives at once, so it can raise the carrier. */
    record Edge(int channel, boolean covered, long tick, long atNanos, int levelUa)
            implements WheelEvent {}

    /** One wheel over one system, reported on uncover. The authoritative record for axles. */
    record Pulse(int channel, long tickOn, long tickOff, long onNanos, long offNanos, int peakUa,
                 long area) implements WheelEvent {
        @Override
        public long atNanos() {
            return offNanos;
        }

        public long centreTick() {
            return (tickOn + tickOff) / 2;
        }
    }

    record Fault(int channel, FaultKind fault, int levelUa, long atNanos) implements WheelEvent {}

    /** The link came up or went down. */
    record Link(boolean up, long atNanos) implements WheelEvent {}

    /** 0 = Wheel 2 (channels 0, 1), 1 = Wheel 1 (channels 2, 3). Prefer {@link #wheel}. */
    static int head(int channel) {
        return channel / 2;
    }

    /** 0 = element 1 (pin 2), 1 = element 2 (pin 4). */
    static int system(int channel) {
        return channel % 2;
    }

    /** The sensor's name on the board: 1 for J23 (channels 2, 3), 2 for J22 (channels 0, 1). */
    static int wheel(int channel) {
        return channel < 2 ? 2 : 1;
    }

    /** "Wheel 1 element 2", the name a person sees in faults and status. */
    static String name(int channel) {
        return "Wheel " + wheel(channel) + " element " + (system(channel) + 1);
    }
}
