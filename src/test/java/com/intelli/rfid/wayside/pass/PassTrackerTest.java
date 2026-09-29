package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelli.rfid.core.model.TagRead;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.wheel.SimulatedTrain;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PassTrackerTest {

    private static final Instant EPOCH = Instant.parse("2026-09-29T10:00:00Z");

    private final List<PassTracker.ClosedPass> published = new ArrayList<>();
    private final List<Boolean> carrier = new ArrayList<>();
    private long now;
    private WaysideProperties properties;
    private PassTracker tracker;

    private final PassTracker.Clocks clocks = new PassTracker.Clocks() {
        @Override
        public long nanos() {
            return now;
        }

        @Override
        public Instant wall(long nanos) {
            return EPOCH.plusNanos(nanos);
        }
    };

    @BeforeEach
    void setUp() {
        properties = new WaysideProperties();
        properties.getPass().setAxleGapMs(2000);
        properties.getPass().setRfidTailMs(500);
        properties.getPass().setRfidLeadMs(200);
        properties.getPass().setTagGapMs(1000);
        properties.getPass().setMaxPassMs(60000);
        properties.getWheel().setHeadSpacingM(20);
        properties.getWheel().setSystemSpacingM(0.14);
        build();
    }

    private void build() {
        tracker = new PassTracker(properties, new TrainIdDecoder(properties.getTrain()), clocks,
                carrier::add, published::add);
    }

    private void at(long ms) {
        now = TimeUnit.MILLISECONDS.toNanos(ms);
    }

    private void advanceTicking(long toMs) {
        long target = TimeUnit.MILLISECONDS.toNanos(toMs);
        while (now < target) {
            now = Math.min(target, now + TimeUnit.MILLISECONDS.toNanos(100));
            tracker.tick();
        }
    }

    private static TagRead tag(String epc, long ms) {
        return new TagRead(epc, 1, -45, 865700, 1, 0, "GEN2", EPOCH.plusMillis(ms));
    }

    /** Plays a simulated train with the first event at ~1 s, ticking between events. */
    private List<WheelEvent> playTrain(SimulatedTrain.Direction direction) {
        List<WheelEvent> events = Trains.events(Trains.spec(direction, 40, 1), 0);
        for (WheelEvent event : events) {
            long target = event.atNanos() + TimeUnit.SECONDS.toNanos(1);
            while (now + TimeUnit.MILLISECONDS.toNanos(100) < target) {
                now += TimeUnit.MILLISECONDS.toNanos(100);
                tracker.tick();
            }
            now = target;
            tracker.onWheel(shift(event, TimeUnit.SECONDS.toNanos(1)));
        }
        return events;
    }

    private static WheelEvent shift(WheelEvent event, long nanos) {
        long ticks = nanos / 1000;
        return switch (event) {
            case WheelEvent.Edge e -> new WheelEvent.Edge(e.channel(), e.covered(), e.tick() + ticks,
                    e.atNanos() + nanos, e.levelUa());
            case WheelEvent.Pulse p -> new WheelEvent.Pulse(p.channel(), p.tickOn() + ticks,
                    p.tickOff() + ticks, p.onNanos() + nanos, p.offNanos() + nanos, p.peakUa(), p.area());
            default -> event;
        };
    }

    @Test
    void aTrainOpensOnItsFirstWheelAndClosesClearedAfterTheGapAndTheTail() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(300);
        tracker.onTags(List.of(tag("OLD", 300)));        // long before the train: not claimed
        List<WheelEvent> events = playTrain(SimulatedTrain.Direction.UP);
        long firstMs = TimeUnit.NANOSECONDS.toMillis(events.get(0).atNanos()) + 1000;

        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        assertThat(carrier).containsExactly(true);
        tracker.onTags(List.of(tag("FRONT", firstMs + 50)));
        tracker.onTags(List.of(tag("REAR", firstMs + 900)));

        long lastMs = TimeUnit.NANOSECONDS.toMillis(now);
        advanceTicking(lastMs + 1900);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        advanceTicking(lastMs + 2100);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.TAIL);
        assertThat(published).isEmpty();
        advanceTicking(lastMs + 2700);

        assertThat(published).hasSize(1);
        assertThat(carrier).containsExactly(true, false);
        PassTracker.ClosedPass pass = published.get(0);
        assertThat(pass.stopReason()).isEqualTo(StopReason.CLEARED);
        assertThat(pass.tags()).extracting(PassResult.Tag::epc).containsExactly("FRONT", "REAR");
        assertThat(pass.wheels().link()).isEqualTo("OK");
        assertThat(pass.wheels().direction()).isEqualTo(Direction.UP);
        assertThat(pass.wheels().axleCount().headA()).isEqualTo(4);
        assertThat(pass.wheels().axleCount().consistent()).isTrue();
        assertThat(pass.wheels().speedKmh().mean()).isBetween(39.0, 41.0);
    }

    @Test
    void aTagReadJustBeforeTheFirstWheelBelongsToThePass() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(900);
        tracker.onTags(List.of(tag("EARLY", 900)));
        at(1000);
        tracker.onWheel(new WheelEvent.Edge(0, true, 1_000_000, now, 9800));
        tracker.onWheel(new WheelEvent.Edge(0, false, 1_002_000, now, 2000));
        advanceTicking(1000 + 2000 + 600);
        assertThat(published).singleElement().satisfies(p ->
                assertThat(p.tags()).extracting(PassResult.Tag::epc).containsExactly("EARLY"));
    }

    @Test
    void aWheelDuringTheTailIsTheSameTrainNotANewOne() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(1000);
        tracker.onWheel(new WheelEvent.Edge(0, true, 1, now, 0));
        tracker.onWheel(new WheelEvent.Edge(0, false, 2, now, 0));
        advanceTicking(3200);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.TAIL);
        tracker.onWheel(new WheelEvent.Edge(1, true, 3, now, 0));
        tracker.onWheel(new WheelEvent.Edge(1, false, 4, now, 0));
        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        advanceTicking(3200 + 2600);
        assertThat(published).hasSize(1);
    }

    @Test
    void aChannelStuckCoveredTimesOutInsteadOfHoldingTheCarrierForever() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(1000);
        tracker.onWheel(new WheelEvent.Edge(2, true, 1, now, 20000));
        advanceTicking(1000 + 59_000);
        assertThat(published).isEmpty();
        advanceTicking(1000 + 60_100);
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.stopReason()).isEqualTo(StopReason.TIMEOUT);
            assertThat(p.wheels().faults()).anyMatch(f -> f.contains("head B system 1")
                    && f.contains("still covered"));
        });
        assertThat(carrier).containsExactly(true, false);
    }

    @Test
    void withTheWheelLinkUpTagsAloneNeverOpenAPass() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(1000);
        tracker.onTags(List.of(tag("STRAY", 1000)));
        advanceTicking(10_000);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.IDLE);
        assertThat(published).isEmpty();
    }

    @Test
    void withTheWheelLinkDownAPassIsRfidOnlyAndClosesOnTagSilence() {
        at(1000);
        tracker.onTags(List.of(tag("FRONT", 1000)));
        assertThat(tracker.isDegraded()).isTrue();
        at(1400);
        tracker.onTags(List.of(tag("FRONT", 1400)));
        advanceTicking(2300);
        assertThat(published).isEmpty();
        at(2300);
        tracker.onTags(List.of(tag("REAR", 2300)));
        advanceTicking(3400);
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.stopReason()).isEqualTo(StopReason.TAG_GAP);
            assertThat(p.tags()).extracting(PassResult.Tag::epc).containsExactly("FRONT", "REAR");
            assertThat(p.tags().get(0).reads()).isEqualTo(2);
            assertThat(p.wheels().link()).isEqualTo("DOWN");
            assertThat(p.wheels().direction()).isNull();
            assertThat(p.wheels().axleCount()).isNull();
            assertThat(p.wheels().speedKmh()).isNull();
        });
    }

    @Test
    void losingTheLinkMidPassIsReportedAsLost() {
        tracker.onWheel(new WheelEvent.Link(true, 0));
        at(1000);
        tracker.onWheel(new WheelEvent.Edge(0, true, 1, now, 0));
        tracker.onWheel(new WheelEvent.Link(false, now));
        advanceTicking(1000 + 2000 + 600);
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.stopReason()).isEqualTo(StopReason.CLEARED);
            assertThat(p.wheels().link()).isEqualTo("LOST");
            assertThat(p.wheels().faults()).contains("wheel link lost during the pass");
        });
    }

    private void gpioMode() {
        properties.getTrigger().setSource(WaysideProperties.TriggerSource.GPIO);
        build();
        tracker.onTriggerHealth(true);
    }

    @Test
    void in1FirstIsAnUpTrainEndedByIn2AfterTheTail() {
        gpioMode();
        at(900);
        tracker.onTags(List.of(tag("EARLY", 900)));
        at(1000);
        tracker.onGpioInput(1, now);
        assertThat(carrier).containsExactly(true);
        at(2000);
        tracker.onTags(List.of(tag("FRONT", 2000)));
        // No wheel ever arrives, and the axle gap must not end a GPIO pass on its own.
        advanceTicking(40_000);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        at(40_000);
        tracker.onTags(List.of(tag("REAR", 40_000)));
        tracker.onGpioInput(2, now);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.TAIL);
        at(40_300);
        tracker.onTags(List.of(tag("LATE", 40_300)));
        advanceTicking(40_600);
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.stopReason()).isEqualTo(StopReason.CLEARED);
            assertThat(p.tags()).extracting(PassResult.Tag::epc)
                    .containsExactly("EARLY", "FRONT", "REAR", "LATE");
            assertThat(p.wheels().link()).isEqualTo("GPIO");
            assertThat(p.wheels().direction()).isEqualTo(Direction.UP);
            assertThat(p.wheels().axleCount()).isNull();
        });
        assertThat(carrier).containsExactly(true, false);
    }

    @Test
    void in2FirstIsADownTrainEndedByIn1() {
        gpioMode();
        at(1000);
        tracker.onGpioInput(2, now);
        tracker.onTags(List.of(tag("T", 1000)));
        at(3000);
        tracker.onGpioInput(1, now);
        advanceTicking(3600);
        assertThat(published).singleElement().satisfies(p ->
                assertThat(p.wheels().direction()).isEqualTo(Direction.DOWN));
    }

    @Test
    void theStartingInputAgainIsARepeatNotTheEnd() {
        gpioMode();
        at(1000);
        tracker.onGpioInput(1, now);
        at(2000);
        tracker.onGpioInput(1, now);
        advanceTicking(10_000);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        assertThat(published).isEmpty();
    }

    @Test
    void theMappingFlipsWithIn1IsUp() {
        properties.getTrigger().getGpio().setIn1IsUp(false);
        gpioMode();
        at(1000);
        tracker.onGpioInput(1, now);
        at(2000);
        tracker.onGpioInput(2, now);
        advanceTicking(2600);
        assertThat(published.get(0).wheels().direction()).isEqualTo(Direction.DOWN);
    }

    @Test
    void anyInputDuringTheTailPublishesTheLastTrainAndStartsANewOne() {
        gpioMode();
        at(1000);
        tracker.onGpioInput(1, now);
        tracker.onTags(List.of(tag("A", 1000)));
        at(5000);
        tracker.onGpioInput(2, now);
        at(5100);
        tracker.onGpioInput(2, now);          // a new train, coming the other way
        assertThat(published).hasSize(1);
        assertThat(published.get(0).wheels().direction()).isEqualTo(Direction.UP);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.OCCUPIED);
        tracker.onTags(List.of(tag("B", 5100)));
        at(6000);
        tracker.onGpioInput(1, now);
        advanceTicking(6600);
        assertThat(published).hasSize(2);
        assertThat(published.get(1).tags()).extracting(PassResult.Tag::epc).containsExactly("B");
        assertThat(published.get(1).wheels().direction()).isEqualTo(Direction.DOWN);
    }

    @Test
    void inGpioModeTagsAloneOpenNothing() {
        gpioMode();
        at(1000);
        tracker.onTags(List.of(tag("STRAY", 1000)));
        advanceTicking(10_000);
        assertThat(tracker.state()).isEqualTo(PassTracker.State.IDLE);
        assertThat(published).isEmpty();
    }

    @Test
    void inGpioModeALostTriggerFallsBackToRfidOnly() {
        gpioMode();
        tracker.onTriggerHealth(false);
        at(1000);
        tracker.onTags(List.of(tag("T", 1000)));
        advanceTicking(2200);
        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.stopReason()).isEqualTo(StopReason.TAG_GAP);
            assertThat(p.wheels().link()).isEqualTo("DOWN");
        });
    }

    @Test
    void carTagsGiveTheTrainAndBothEndsMakeItComplete() {
        properties.getTrain().setDecode(WaysideProperties.DecodeMode.CAR_TAG);
        build();
        at(1000);
        tracker.onTags(List.of(tag("8A8020008A1D00021F0C12E9", 1000)));   // 02-0008 DMC-1
        at(1500);
        tracker.onTags(List.of(tag("8A8020008A6D00021F0C11D6", 1500)));   // 02-0008 DMC-2
        advanceTicking(3000);
        PassTracker.ClosedPass pass = published.get(0);
        assertThat(pass.train().id()).isEqualTo("02-0008");
        assertThat(pass.train().line()).isEqualTo("02");
        assertThat(pass.train().trainSet()).isEqualTo("0008");
        assertThat(pass.train().complete()).isTrue();
        assertThat(pass.tags()).extracting(t -> t.car().positionName()).containsExactly("DMC-1", "DMC-2");
    }

    @Test
    void oneEndOnlyIsTheTrainButNotComplete() {
        properties.getTrain().setDecode(WaysideProperties.DecodeMode.CAR_TAG);
        build();
        at(1000);
        tracker.onTags(List.of(tag("8A8020013A1D00021F0C5233", 1000), tag("E2C06892000000021F0C1400", 1000)));
        advanceTicking(3000);
        PassResult.Train train = published.get(0).train();
        assertThat(train.id()).isEqualTo("02-0013");
        assertThat(train.complete()).isFalse();
        assertThat(train.tagsFound()).isEqualTo(2);
        assertThat(published.get(0).tags()).filteredOn(t -> !t.decoded()).singleElement()
                .satisfies(t -> assertThat(t.car()).isNull());
    }

    @Test
    void tagsFromTwoTrainsGiveNoTrainId() {
        properties.getTrain().setDecode(WaysideProperties.DecodeMode.CAR_TAG);
        build();
        at(1000);
        tracker.onTags(List.of(tag("8A8020008A1D00021F0C12E9", 1000), tag("8A8070003A6D00021F0C3F9D", 1000)));
        advanceTicking(3000);
        assertThat(published.get(0).train().id()).isNull();
        assertThat(published.get(0).train().complete()).isFalse();
    }

    @Test
    void rawDecodingIsNeverComplete() {
        at(1000);
        tracker.onTags(List.of(tag("E2801190000000000000AA01", 1000),
                tag("E2801190000000000000AA02", 1000)));
        advanceTicking(2500);
        PassResult.Train train = published.get(0).train();
        assertThat(train.tagsFound()).isEqualTo(2);
        assertThat(train.id()).isNull();
        assertThat(train.decoded()).isFalse();
        assertThat(train.complete()).isFalse();
        assertThat(published.get(0).tags()).allSatisfy(t -> assertThat(t.decoded()).isFalse());
    }

    @Test
    void twoTagsDecodingToOneTrainAreComplete() {
        properties.getTrain().setDecode(WaysideProperties.DecodeMode.REGEX);
        properties.getTrain().setPattern("E28011900000(?<id>[0-9A-F]{8})AA0[12]");
        build();
        at(1000);
        tracker.onTags(List.of(tag("E2801190000000004321AA01", 1000), tag("JUNK", 1000)));
        at(1500);
        tracker.onTags(List.of(tag("E2801190000000004321AA02", 1500)));
        advanceTicking(3000);
        PassTracker.ClosedPass pass = published.get(0);
        assertThat(pass.train().id()).isEqualTo("00004321");
        assertThat(pass.train().complete()).isTrue();
        assertThat(pass.train().tagsFound()).isEqualTo(3);
        // The undecodable tag is still reported, raw.
        assertThat(pass.tags()).filteredOn(t -> t.epc().equals("JUNK"))
                .singleElement().satisfies(t -> assertThat(t.decoded()).isFalse());
    }
}
