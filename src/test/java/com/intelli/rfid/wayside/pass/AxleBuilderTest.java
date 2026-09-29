package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.intelli.rfid.wayside.wheel.SimulatedTrain;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AxleBuilderTest {

    private static final AxleBuilder.Config CONFIG = new AxleBuilder.Config(true, 20.0, 0.14, 2_000_000);

    private static AxleBuilder.Analysis analyse(List<WheelEvent.Pulse> pulses, AxleBuilder.Config c) {
        return AxleBuilder.analyse(pulses, c, n -> Instant.ofEpochSecond(0, n));
    }

    @Test
    void anUpTrainIsUpWithEveryAxleCountedAtBothHeadsAndItsSpeed() {
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 3)), CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.UP);
        assertThat(a.headA()).isEqualTo(12);
        assertThat(a.headB()).isEqualTo(12);
        assertThat(a.consistent()).isTrue();
        assertThat(a.speedMean()).isCloseTo(40.0, within(0.2));
        assertThat(a.axles()).hasSize(12).allSatisfy(axle -> {
            assertThat(axle.atA()).isBefore(axle.atB());
            assertThat(axle.peakUa()).containsOnly(9800);
        });
        assertThat(a.notes()).isEmpty();
    }

    @Test
    void aDownTrainIsDown() {
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.DOWN, 25, 2)), CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.DOWN);
        assertThat(a.speedMin()).isCloseTo(25.0, within(0.2));
        assertThat(a.axles()).allSatisfy(axle -> assertThat(axle.atB()).isBefore(axle.atA()));
    }

    /** Wired the other way round: the config flag is the only thing that names the direction. */
    @Test
    void theUpFlagDecidesWhichWayIsUp() {
        AxleBuilder.Config flipped = new AxleBuilder.Config(false, 20.0, 0.14, 2_000_000);
        assertThat(analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)), flipped).direction())
                .isEqualTo(Direction.DOWN);
    }

    @Test
    void oneHeadAloneRefusesToNameADirection() {
        List<WheelEvent.Pulse> onlyA = Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)).stream()
                .filter(p -> p.channel() < 2).toList();
        AxleBuilder.Analysis a = analyse(onlyA, CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.UNKNOWN);
        assertThat(a.headA()).isEqualTo(4);
        assertThat(a.headB()).isZero();
        assertThat(a.consistent()).isFalse();
        // Coarse speed from the within-head system offset, since head B is down.
        assertThat(a.speedMean()).isCloseTo(40.0, within(1.0));
    }

    @Test
    void aTrainThatReversesOverTheSensorsIsMixed() {
        List<WheelEvent.Pulse> up = Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 10, 1));
        List<WheelEvent.Pulse> down = Trains.pulses(Trains.spec(SimulatedTrain.Direction.DOWN, 10, 1));
        long shift = up.stream().mapToLong(WheelEvent.Pulse::tickOff).max().orElseThrow() + 5_000_000;
        List<WheelEvent.Pulse> both = new ArrayList<>(up);
        for (WheelEvent.Pulse p : down) {
            both.add(new WheelEvent.Pulse(p.channel(), p.tickOn() + shift, p.tickOff() + shift,
                    p.onNanos() + shift * 1000, p.offNanos() + shift * 1000, p.peakUa(), p.area()));
        }
        assertThat(analyse(both, CONFIG).direction())
                .isEqualTo(Direction.MIXED);
    }

    @Test
    void aMissedPulseIsSurfacedNotResolved() {
        List<WheelEvent.Pulse> pulses = new ArrayList<>(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)));
        pulses.removeIf(p -> p.channel() == 3 && p.tickOn() == pulses.stream()
                .filter(q -> q.channel() == 3).mapToLong(WheelEvent.Pulse::tickOn).min().orElseThrow());
        AxleBuilder.Analysis a = analyse(pulses, CONFIG);
        assertThat(a.headA()).isEqualTo(4);
        assertThat(a.headB()).isEqualTo(3);
        assertThat(a.consistent()).isFalse();
        assertThat(a.speedMean()).isNull();
        assertThat(a.notes()).anyMatch(n -> n.contains("no partner"))
                .anyMatch(n -> n.contains("disagrees"));
    }

    @Test
    void noHeadSpacingMeansNoSpeed() {
        AxleBuilder.Config noSpacing = new AxleBuilder.Config(true, 0, 0, 2_000_000);
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)), noSpacing);
        assertThat(a.direction()).isEqualTo(Direction.UP);
        assertThat(a.speedMean()).isNull();
    }
}
