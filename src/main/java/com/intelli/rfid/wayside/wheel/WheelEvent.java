package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.wheel.WheelMessage.FaultKind;

/**
 * What the pass logic sees from the wheel sensors: ticks already unwrapped to a 64-bit µs line,
 * and host times ({@code System.nanoTime()} domain) already estimated through {@link TickClock}.
 *
 * <p>Channels 0 and 1 are head A's two systems, 2 and 3 are head B's, each numbered in the A→B
 * direction along the rail. That numbering is what makes "system 1 before system 2" mean A→B.
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

    static int head(int channel) {
        return channel / 2;
    }

    static int system(int channel) {
        return channel % 2;
    }
}
