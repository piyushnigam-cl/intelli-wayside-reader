package com.intelli.rfid.wayside.wheel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TickClockTest {

    @Test
    void unwrapsAcrossTheThirtyTwoBitWrap() {
        TickClock clock = new TickClock(10);
        assertThat(clock.unwrap(0xFFFFFF00L)).isEqualTo(0xFFFFFF00L);
        assertThat(clock.unwrap(0x00000100L)).isEqualTo(0x1_00000100L);
        assertThat(clock.unwrap(0x00001000L)).isEqualTo(0x1_00001000L);
    }

    /** A pulse's tick-on is older than the heartbeat before it; it must not jump a whole wrap. */
    @Test
    void anOlderTickJustAfterTheWrapLandsBeforeIt() {
        TickClock clock = new TickClock(10);
        clock.unwrap(0xFFFFFF00L);
        clock.unwrap(0x00000100L);
        assertThat(clock.unwrap(0xFFFFFFF0L)).isEqualTo(0xFFFFFFF0L);
    }

    @Test
    void fitsASlightlyFastCrystal() {
        TickClock clock = new TickClock(60);
        // 1 MHz tick running 100 ppm fast: 1,000,100 ticks per host second, 0.3 ms receipt jitter.
        for (int s = 0; s < 60; s++) {
            long tick = s * 1_000_100L;
            long nanos = 5_000_000_000L + s * 1_000_000_000L + (s % 3) * 300_000L;
            clock.sync(clock.unwrap(tick), nanos);
        }
        assertThat(clock.slope()).isCloseTo(1000.0 / 1.0001, within(0.05));
        long mid = 30 * 1_000_100L;
        assertThat(clock.toNanos(mid)).isCloseTo(35_000_000_000L + 300_000L, within(1_000_000L));
    }
}
