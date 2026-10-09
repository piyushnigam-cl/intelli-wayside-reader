package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.wheel.SimulatedTrain;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AxleBuilderTest {

    private static final AxleBuilder.Config CONFIG = new AxleBuilder.Config(20.0, 0.14, 2_000_000);

    private static AxleBuilder.Analysis analyse(List<WheelEvent.Pulse> pulses, AxleBuilder.Config c) {
        return AxleBuilder.analyse(pulses, c, n -> Instant.ofEpochSecond(0, n));
    }

    /** Operator, 2026-10-09: Wheel 1 (J23, channels 2/3) seeing the train first is UP. */
    @Test
    void wheelOneFirstIsUpWithEveryAxleCountedAtBothSensorsAndItsSpeed() {
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 3)), CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.UP);
        assertThat(a.wheel1()).isEqualTo(12);
        assertThat(a.wheel2()).isEqualTo(12);
        assertThat(a.consistent()).isTrue();
        assertThat(a.speedMean()).isCloseTo(40.0, within(0.2));
        assertThat(a.axles()).hasSize(12).allSatisfy(axle -> {
            assertThat(axle.atWheel1()).isBefore(axle.atWheel2());
            assertThat(axle.speedAtWheel1Kmh()).isCloseTo(40.0, within(1.0));
            assertThat(axle.speedAtWheel2Kmh()).isCloseTo(40.0, within(1.0));
            assertThat(axle.peakUa()).containsOnly(9800);
        });
        assertThat(a.notes()).isEmpty();
    }

    @Test
    void wheelTwoFirstIsDown() {
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.DOWN, 25, 2)), CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.DOWN);
        assertThat(a.speedMin()).isCloseTo(25.0, within(0.2));
        assertThat(a.axles()).allSatisfy(axle -> assertThat(axle.atWheel2()).isBefore(axle.atWheel1()));
    }

    /**
     * Only the first axle's sensor order names the direction, and only once the element order at
     * both sensors agrees with it. Here Wheel 1's elements are swapped on its connector.
     */
    @Test
    void aSensorWithItsElementsSwappedGivesUnknownNotAGuess() {
        List<WheelEvent.Pulse> swapped = Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)).stream()
                .map(p -> p.channel() < 2 ? p : new WheelEvent.Pulse(p.channel() == 2 ? 3 : 2,
                        p.tickOn(), p.tickOff(), p.onNanos(), p.offNanos(), p.peakUa(), p.area()))
                .toList();
        AxleBuilder.Analysis a = analyse(swapped, CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.UNKNOWN);
        assertThat(a.notes()).anyMatch(n -> n.contains("disagree on element order"));
    }

    @Test
    void oneSensorAloneRefusesToNameADirectionButStillGivesItsSpeed() {
        List<WheelEvent.Pulse> onlyWheel2 = Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)).stream()
                .filter(p -> p.channel() < 2).toList();
        AxleBuilder.Analysis a = analyse(onlyWheel2, CONFIG);
        assertThat(a.direction()).isEqualTo(Direction.UNKNOWN);
        assertThat(a.wheel2()).isEqualTo(4);
        assertThat(a.wheel1()).isZero();
        assertThat(a.consistent()).isFalse();
        assertThat(a.notes()).anyMatch(n -> n.contains("only Wheel 2 saw the train"));
        assertThat(a.speedMean()).isCloseTo(40.0, within(1.0));
        assertThat(a.axles()).allSatisfy(axle -> assertThat(axle.speedAtWheel1Kmh()).isNull());
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
        assertThat(analyse(both, CONFIG).direction()).isEqualTo(Direction.MIXED);
    }

    /** A missed pulse loses the sensor-to-sensor speed, but each sensor still times its own wheels. */
    @Test
    void aMissedPulseIsSurfacedNotResolved() {
        List<WheelEvent.Pulse> pulses = new ArrayList<>(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)));
        pulses.removeIf(p -> p.channel() == 3 && p.tickOn() == pulses.stream()
                .filter(q -> q.channel() == 3).mapToLong(WheelEvent.Pulse::tickOn).min().orElseThrow());
        AxleBuilder.Analysis a = analyse(pulses, CONFIG);
        assertThat(a.wheel2()).isEqualTo(4);
        assertThat(a.wheel1()).isEqualTo(3);
        assertThat(a.consistent()).isFalse();
        assertThat(a.speedMean()).isCloseTo(40.0, within(1.0));
        assertThat(a.notes()).anyMatch(n -> n.contains("no partner"))
                .anyMatch(n -> n.contains("disagrees"));
    }

    @Test
    void elementSpacingAloneGivesTheSpeedAtEachSensor() {
        AxleBuilder.Config elementsOnly = new AxleBuilder.Config(0, 0.14, 2_000_000);
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.DOWN, 60, 1)), elementsOnly);
        assertThat(a.direction()).isEqualTo(Direction.DOWN);
        assertThat(a.speedMean()).isCloseTo(60.0, within(1.5));
        assertThat(a.axles()).allSatisfy(axle -> {
            assertThat(axle.speedAtWheel1Kmh()).isCloseTo(60.0, within(1.5));
            assertThat(axle.speedAtWheel2Kmh()).isCloseTo(60.0, within(1.5));
        });
    }

    @Test
    void noDistancesMeansNoSpeed() {
        AxleBuilder.Config noSpacing = new AxleBuilder.Config(0, 0, 2_000_000);
        AxleBuilder.Analysis a = analyse(Trains.pulses(Trains.spec(SimulatedTrain.Direction.UP, 40, 1)), noSpacing);
        assertThat(a.direction()).isEqualTo(Direction.UP);
        assertThat(a.speedMean()).isNull();
        assertThat(a.axles()).allSatisfy(axle -> assertThat(axle.speedAtWheel1Kmh()).isNull());
    }

    /** Site files written before 2026-10-09 still bind to the renamed settings. */
    @Test
    void theOldSpacingNamesStillBind() {
        WaysideProperties.Wheel wheel = new WaysideProperties.Wheel();
        wheel.setHeadSpacingM(18.5);
        wheel.setSystemSpacingM(0.12);
        assertThat(wheel.getSensorSpacingM()).isEqualTo(18.5);
        assertThat(wheel.getElementSpacingM()).isEqualTo(0.12);
    }

    /** Operator, 2026-10-09: Wheel 1 --13 m-- WPMS 3.5 m --18.5 m-- Wheel 2. */
    @Test
    void theSensorSpacingIsTheLayoutAddedUpUnlessSetOutright() {
        WaysideProperties.Wheel wheel = new WaysideProperties.Wheel();
        assertThat(wheel.effectiveSensorSpacingM()).isZero();
        wheel.setWheel1ToWpmsM(13.0);
        wheel.setWpmsLengthM(3.5);
        assertThat(wheel.effectiveSensorSpacingM()).as("a partial layout gives no speed").isZero();
        wheel.setWpmsToWheel2M(18.5);
        assertThat(wheel.effectiveSensorSpacingM()).isEqualTo(35.0);
        wheel.setSensorSpacingM(34.8);
        assertThat(wheel.effectiveSensorSpacingM()).isEqualTo(34.8);
    }
}
