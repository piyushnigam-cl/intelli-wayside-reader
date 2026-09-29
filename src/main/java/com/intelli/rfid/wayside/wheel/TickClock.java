package com.intelli.rfid.wayside.wheel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Maps the SAMD21's free-running 32-bit µs tick onto this JVM's {@code System.nanoTime()} line.
 *
 * <p>Two jobs. <b>Unwrap</b>: the tick wraps every 71.6 minutes, so each raw value is placed on a
 * 64-bit line at whichever wrap is nearest the last value seen. That tolerates events arriving
 * slightly out of order (a pulse's tick-on is older than the heartbeat before it). <b>Fit</b>: a
 * least-squares line through the last {@code window} heartbeats' (tick, receipt nanos) pairs.
 * UART latency jitter is well under a millisecond, far finer than the reader's ~50 ms read-window
 * batching, so this is good enough to decide which tags belong to which train.
 *
 * <p><b>Speed never goes through the fit</b> — axle-to-axle timing uses unwrapped ticks directly.
 */
public class TickClock {

    private static final long WRAP = 1L << 32;
    private static final long HALF = 1L << 31;

    private final int window;
    private final Deque<long[]> points = new ArrayDeque<>();
    private long last = Long.MIN_VALUE;
    private double slope = 1000.0;
    private double interceptNanos;
    private long originTick;

    public TickClock(int window) {
        this.window = Math.max(2, window);
    }

    /** Places a raw u32 tick on the unwrapped line, nearest the last value seen. */
    public synchronized long unwrap(long raw) {
        raw &= 0xFFFFFFFFL;
        if (last == Long.MIN_VALUE) {
            last = raw;
            return raw;
        }
        long base = last - (last & 0xFFFFFFFFL);
        long candidate = base + raw;
        if (candidate - last > HALF) {
            candidate -= WRAP;
        } else if (last - candidate > HALF) {
            candidate += WRAP;
        }
        if (candidate > last) {
            last = candidate;
        }
        return candidate;
    }

    /** Records a heartbeat: the tick it carried, and when it arrived here. */
    public synchronized void sync(long unwrappedTick, long receivedNanos) {
        points.addLast(new long[] {unwrappedTick, receivedNanos});
        while (points.size() > window) {
            points.removeFirst();
        }
        fit();
    }

    public synchronized boolean isSynced() {
        return !points.isEmpty();
    }

    /** Host nanos for an unwrapped tick. Before the first heartbeat, assumes 1 tick = 1 µs from now. */
    public synchronized long toNanos(long unwrappedTick) {
        if (points.isEmpty()) {
            return System.nanoTime();
        }
        return Math.round(interceptNanos + slope * (unwrappedTick - originTick));
    }

    private void fit() {
        long[] first = points.peekFirst();
        originTick = first[0];
        long originNanos = first[1];
        int n = points.size();
        if (n == 1) {
            slope = 1000.0;
            interceptNanos = originNanos;
            return;
        }
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (long[] p : points) {
            double x = p[0] - originTick;
            double y = p[1] - originNanos;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denominator = n * sxx - sx * sx;
        if (denominator == 0) {
            slope = 1000.0;
            interceptNanos = originNanos + sy / n;
            return;
        }
        slope = (n * sxy - sx * sy) / denominator;
        interceptNanos = originNanos + (sy - slope * sx) / n;
    }

    /** Nanoseconds per tick from the fit: 1000 for an exact 1 MHz crystal. */
    public synchronized double slope() {
        return slope;
    }
}
