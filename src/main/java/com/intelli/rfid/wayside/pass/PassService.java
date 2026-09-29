package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelli.rfid.core.model.ReaderInfo;
import com.intelli.rfid.core.model.TagRead;
import com.intelli.rfid.spring.JsonlSpool;
import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.spring.TagBroadcaster;
import com.intelli.rfid.wayside.ClockSync;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.cloud.CloudSender;
import com.intelli.rfid.wayside.trigger.GpioTrigger;
import com.intelli.rfid.wayside.wheel.WheelSource;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Owns the one thread every pass input runs on, and does what happens to a closed pass: sequence,
 * local history, cloud, SSE.
 *
 * <p>Tag batches arrive on core's dispatcher thread and wheel events on the link's reader thread;
 * both are handed to {@code wayside-pass} and nothing else touches the {@link PassTracker}. The
 * cloud send is a hand-off too, so a dead uplink never holds up the next train.
 */
public class PassService {

    private static final Logger log = LoggerFactory.getLogger(PassService.class);
    private static final int HISTORY = 200;

    private final WaysideProperties properties;
    private final ReaderService reader;
    private final WheelSource wheels;
    private final CloudSender cloud;
    private final ClockSync clockSync;
    private final CarrierController carrier;
    /** Non-null in GPIO trigger mode: J26 IN1/IN2 bound the passes, not the wheels. */
    private final GpioTrigger gpio;
    private volatile boolean triggerUp;
    private final PassTracker tracker;
    private final JsonlSpool<PassResult> spool;
    private final String spoolProblem;
    private final TagBroadcaster events = new TagBroadcaster(0L);
    private final Deque<PassResult> history = new ArrayDeque<>();
    private final String appVersion;
    private volatile PassResult latest;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wayside-pass");
        thread.setDaemon(true);
        return thread;
    });

    public PassService(WaysideProperties properties, ReaderService reader, WheelSource wheels,
                       GpioTrigger gpio, CloudSender cloud, ClockSync clockSync, ObjectMapper mapper) {
        this.properties = properties;
        this.reader = reader;
        this.wheels = wheels;
        this.gpio = gpio;
        this.cloud = cloud;
        this.clockSync = clockSync;
        this.carrier = new CarrierController(reader, properties.getRfid().getCarrier(),
                gpio != null ? gpio::isWatching : wheels::isUp);

        JsonlSpool<PassResult> opened;
        String problem = null;
        try {
            opened = new JsonlSpool<>(properties.getSpoolDir(), "pass", properties.isSpoolEnabled(),
                    mapper, PassResult.class, PassResult::sequence);
        } catch (UncheckedIOException e) {
            // A spool that cannot be written is a degraded reader, not an unusable one: trains are
            // still identified and sent to the cloud. Health reports it (the tunnel's lesson).
            problem = e.getMessage() + ": " + e.getCause();
            log.error("Pass history spool unavailable, running in memory only: {}", problem);
            opened = new JsonlSpool<>(properties.getSpoolDir(), "pass", false, mapper,
                    PassResult.class, PassResult::sequence);
        }
        this.spool = opened;
        this.spoolProblem = problem;

        Clock wall = Clock.systemUTC();
        PassTracker.Clocks clocks = new PassTracker.Clocks() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }

            @Override
            public Instant wall(long nanos) {
                return wall.instant().minusNanos(System.nanoTime() - nanos);
            }
        };
        this.tracker = new PassTracker(properties, new TrainIdDecoder(properties.getTrain()), clocks,
                carrier, this::publish);
        String version = PassService.class.getPackage().getImplementationVersion();
        this.appVersion = version == null ? "dev" : version;
    }

    public void start() {
        if (properties.getReaderId() == null || properties.getReaderId().isBlank()) {
            log.warn("wayside.reader-id is empty; every pass goes to the cloud without a reader "
                    + "identity. Set it per unit in the site config.");
        }
        reader.subscribe(reads -> executor.execute(() -> tracker.onTags(reads)));
        if (gpio != null) {
            gpio.start(nanos -> executor.execute(() -> tracker.onGpioInput(1, nanos)),
                    nanos -> executor.execute(() -> tracker.onGpioInput(2, nanos)));
            // The SAMD21 link still runs, for /api/v1/wheel/levels, but it does not bound passes.
            wheels.start(event -> {});
        } else {
            wheels.start(event -> executor.execute(() -> tracker.onWheel(event)));
        }
        executor.scheduleWithFixedDelay(this::tickQuietly, 100, 100, TimeUnit.MILLISECONDS);
        carrier.start();
        log.info("Wayside pass tracking started: trigger {}, wheel source {}, carrier {}, decode {}",
                properties.getTrigger().getSource(), properties.getWheel().getSource(),
                properties.getRfid().getCarrier(),
                properties.getTrain().getDecode());
    }

    public void stop() {
        carrier.stop();
        if (gpio != null) {
            gpio.close();
        }
        wheels.stop();
        executor.shutdownNow();
        events.shutdown();
    }

    private void tickQuietly() {
        try {
            if (gpio != null && gpio.isWatching() != triggerUp) {
                triggerUp = gpio.isWatching();
                tracker.onTriggerHealth(triggerUp);
            }
            tracker.tick();
        } catch (RuntimeException e) {
            log.error("Pass tick failed", e);
        }
    }

    /** Bench only: tag reads that did not come from the module, fed in as if they had. */
    public void injectTags(List<TagRead> reads) {
        executor.execute(() -> tracker.onTags(reads));
    }

    // ---------------------------------------------------------------- publishing

    private void publish(PassTracker.ClosedPass pass) {
        long sequence = spool.nextSequence();
        PassResult result = new PassResult(PassResult.SCHEMA_VERSION, UUID.randomUUID().toString(),
                properties.getReaderId(),
                sequence, pass.startedAt(), pass.endedAt(), pass.stopReason(),
                clockSync.isSynced(), pass.train(), pass.tags(), pass.wheels(), readerMeta());
        latest = result;
        synchronized (history) {
            history.addFirst(result);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
        try {
            spool.append(result);
        } catch (RuntimeException e) {
            log.error("Could not write pass {} to the local history", result.id(), e);
        }
        cloud.send(result);
        events.publishEvent("pass", result);
    }

    private PassResult.Reader readerMeta() {
        String fw = null;
        try {
            ReaderInfo info = reader.session().info();
            fw = info == null ? null : info.softwareVersion();
        } catch (RuntimeException e) {
            // Module identity is decoration here; never let it cost a pass.
        }
        String region = reader.properties().getRegion() == null ? null
                : reader.properties().getRegion().name();
        return new PassResult.Reader(appVersion, fw, region);
    }

    // ---------------------------------------------------------------- queries

    public PassResult latest() {
        return latest;
    }

    public PassResult find(String id) {
        synchronized (history) {
            for (PassResult result : history) {
                if (result.id().equals(id)) {
                    return result;
                }
            }
        }
        return null;
    }

    /** Durable catch-up from the local spool; falls back to memory when the spool is off. */
    public List<PassResult> since(long sequence, int limit) {
        if (spool.isEnabled()) {
            return spool.since(sequence, limit);
        }
        List<PassResult> out = new ArrayList<>();
        synchronized (history) {
            history.descendingIterator().forEachRemaining(r -> {
                if (r.sequence() > sequence && out.size() < limit) {
                    out.add(r);
                }
            });
        }
        return out;
    }

    public SseEmitter subscribeEvents() {
        return events.subscribe();
    }

    /** Bench: an IN1 or IN2 pulse as though J26 had seen it. False outside GPIO mode. */
    public boolean injectTrigger(boolean in1) {
        return gpio != null && gpio.inject(in1);
    }

    /** Null outside GPIO mode; "WATCHING" or the problem otherwise. */
    public String triggerState() {
        if (gpio == null) {
            return null;
        }
        return gpio.isWatching() ? "WATCHING" : String.valueOf(gpio.problem());
    }

    public PassTracker.State state() {
        return tracker.state();
    }

    public boolean carrierWanted() {
        return carrier.wanted();
    }

    public long currentSequence() {
        return spool.currentSequence();
    }

    public String spoolProblem() {
        return spoolProblem;
    }
}
