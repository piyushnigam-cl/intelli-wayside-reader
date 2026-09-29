package com.intelli.rfid.wayside.v1;

import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.wayside.ClockSync;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.cloud.CloudSender;
import com.intelli.rfid.wayside.pass.PassResult;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.wheel.WheelLinkStatus;
import com.intelli.rfid.wayside.wheel.WheelSource;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The reader's local API (design sec.7). It is <b>not</b> the cloud contract — that is the POST
 * the reader makes. Every path here falls through core's {@code ScopeRules} to ADMIN, the
 * closed-by-default rule; a READ scope for monitoring needs a rule added in core.
 *
 * <p>{@code POST /api/v1/wheel/capture} is not here yet: it needs firmware to capture anything.
 */
@RestController
@RequestMapping("/api/v1")
public class WaysideController {

    private final PassService passes;
    private final WheelSource wheels;
    private final CloudSender cloud;
    private final ReaderService reader;
    private final ClockSync clockSync;
    private final WaysideProperties properties;

    public WaysideController(PassService passes, WheelSource wheels, CloudSender cloud,
                             ReaderService reader, ClockSync clockSync,
                             WaysideProperties properties) {
        this.passes = passes;
        this.wheels = wheels;
        this.cloud = cloud;
        this.reader = reader;
        this.clockSync = clockSync;
        this.properties = properties;
    }

    @GetMapping("/status")
    public WaysideStatus status() {
        PassResult latest = passes.latest();
        return new WaysideStatus(properties.getReaderId(), reader.session().state().name(),
                reader.session().isReading(), passes.carrierWanted(),
                properties.getRfid().getCarrier().name(), passes.state().name(),
                clockSync.isSynced(), passes.currentSequence(),
                latest == null ? null : latest.endedAt(), wheels.status(),
                new WaysideStatus.Cloud(cloud.isEnabled(), cloud.spoolDepth(),
                        cloud.deadLetterDepth(), cloud.lastOutcome(), cloud.lastDeliveredAt()));
    }

    @GetMapping("/passes/latest")
    public ResponseEntity<PassResult> latest() {
        PassResult latest = passes.latest();
        return latest == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(latest);
    }

    @GetMapping("/passes")
    public List<PassResult> since(@RequestParam(name = "since", defaultValue = "0") long since,
                                  @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return passes.since(since, Math.max(1, Math.min(limit, 1000)));
    }

    @GetMapping("/passes/{id}")
    public ResponseEntity<PassResult> one(@PathVariable("id") String id) {
        PassResult result = passes.find(id);
        return result == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(result);
    }

    @GetMapping("/wheel/levels")
    public WheelLinkStatus levels() {
        return wheels.status();
    }

    @GetMapping("/events")
    public SseEmitter events() {
        return passes.subscribeEvents();
    }
}
