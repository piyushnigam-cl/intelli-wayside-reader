package com.intelli.rfid.wayside.bench;

import com.intelli.rfid.core.model.TagRead;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.wheel.SimulatedTrain;
import com.intelli.rfid.wayside.wheel.SimulatedWheelSource;
import com.intelli.rfid.wayside.wheel.WheelSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays a simulated train: wheel frames through the simulated SAMD21, and synthetic tag reads at
 * the moments the two tags would pass the antenna. Exists only with {@code wayside.bench.enabled},
 * which the packaged config keeps off, and answers 409 unless the wheel source is SIMULATED — a
 * fake train must never mix with real sensors. ADMIN, by core's default rule.
 */
@RestController
@RequestMapping("/api/bench")
@ConditionalOnProperty(prefix = "wayside.bench", name = "enabled", havingValue = "true")
public class BenchController {

    private static final int READS_PER_TAG = 3;

    private final WheelSource wheels;
    private final PassService passes;
    private final WaysideProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wayside-bench");
        thread.setDaemon(true);
        return thread;
    });

    public BenchController(WheelSource wheels, PassService passes, WaysideProperties properties) {
        this.wheels = wheels;
        this.passes = passes;
        this.properties = properties;
    }

    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> train(@RequestBody(required = false) TrainRequest body) {
        if (!(wheels instanceof SimulatedWheelSource simulator)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "wayside.wheel.source is " + properties.getWheel().getSource()
                            + "; simulated trains need SIMULATED"));
        }
        TrainRequest r = body == null ? new TrainRequest(null, null, null, null, null, null) : body;
        int cars = r.cars() == null ? 3 : Math.max(1, Math.min(r.cars(), 24));
        List<Double> axles = SimulatedTrain.standardCars(cars);
        double length = cars * SimulatedTrain.CAR_M;
        SimulatedTrain.Spec spec = new SimulatedTrain.Spec(
                r.direction() == null ? SimulatedTrain.Direction.UP : r.direction(),
                r.speedKmh() == null ? 30.0 : r.speedKmh(), axles,
                r.frontEpc() == null ? "E28011900000000000000A01" : r.frontEpc(), 0.3,
                r.rearEpc() == null ? "E28011900000000000000A02" : r.rearEpc(), length - 0.3);
        SimulatedTrain.Geometry geometry = geometry();
        long leadMs = r.leadMs() == null ? 500 : Math.max(0, r.leadMs());

        long zero = simulator.play(spec, geometry, leadMs);
        SimulatedTrain.Timeline timeline = SimulatedTrain.timeline(spec, geometry, 0);
        double v = spec.speedKmh() / 3.6;
        long spreadNanos = (long) (0.6 / v * 1e9);
        for (SimulatedTrain.TagPass tag : timeline.tags()) {
            for (int i = 0; i < READS_PER_TAG; i++) {
                long at = zero + tag.offsetNanos() - spreadNanos + i * spreadNanos;
                scheduler.schedule(() -> passes.injectTags(List.of(new TagRead(tag.epc(), 1, -45,
                                865700, 1, 0, "GEN2", Instant.now()))),
                        Math.max(0, at - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("direction", spec.direction());
        out.put("speedKmh", spec.speedKmh());
        out.put("axles", axles.size());
        out.put("tags", timeline.tags().stream().map(SimulatedTrain.TagPass::epc).toList());
        out.put("wheelsDoneInMs", leadMs + TimeUnit.NANOSECONDS.toMillis(timeline.durationNanos()));
        out.put("expectPublishAfterMs", leadMs + TimeUnit.NANOSECONDS.toMillis(timeline.durationNanos())
                + properties.getPass().getAxleGapMs() + properties.getPass().getRfidTailMs());
        out.put("geometry", geometry);
        return ResponseEntity.accepted().body(out);
    }

    /** {@code POST /api/bench/trigger?input=in1|in2}: a J26 IN1 / IN2 pulse without a wire. */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger(
            @org.springframework.web.bind.annotation.RequestParam("input") String input) {
        boolean in1 = "in1".equalsIgnoreCase(input);
        if (!in1 && !"in2".equalsIgnoreCase(input)) {
            return ResponseEntity.badRequest().body(Map.of("error", "input must be in1 or in2"));
        }
        if (properties.getTrigger().getSource() != WaysideProperties.TriggerSource.GPIO) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "wayside.trigger.source is " + properties.getTrigger().getSource()));
        }
        boolean fired = passes.injectTrigger(in1);
        return ResponseEntity.accepted().body(Map.of("input", input, "fired", fired,
                "pass", passes.state().name()));
    }

    /**
     * The configured geometry where set, otherwise a plausible 20 m layout. The antenna is placed
     * at the middle of the WPMS when the layout is configured, else halfway between the sensors.
     */
    private SimulatedTrain.Geometry geometry() {
        WaysideProperties.Wheel wheel = properties.getWheel();
        double configured = wheel.effectiveSensorSpacingM();
        double heads = configured > 0 ? configured : 20.0;
        double systems = wheel.getElementSpacingM() > 0 ? wheel.getElementSpacingM() : 0.14;
        double antenna = wheel.getWheel1ToWpmsM() > 0 && wheel.getWheel1ToWpmsM() < heads
                ? wheel.getWheel1ToWpmsM() + wheel.getWpmsLengthM() / 2 : heads / 2;
        return new SimulatedTrain.Geometry(heads, antenna, systems, 0.1, 5.0);
    }
}
