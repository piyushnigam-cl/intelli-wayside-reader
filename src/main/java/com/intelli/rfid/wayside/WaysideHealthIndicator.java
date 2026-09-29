package com.intelli.rfid.wayside;

import com.intelli.rfid.wayside.cloud.CloudSender;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.wheel.WheelSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The wayside's own degradations, separate from the reader's.
 *
 * <p>A wheel link that is down is reported as a <b>detail on UP</b>: trains are still identified
 * and delivered, RFID-only, which is what the cloud needs most. An unwritable history spool is
 * OUT_OF_SERVICE, as on the tunnel — passes then live in memory only.
 */
@Component
public class WaysideHealthIndicator implements HealthIndicator {

    private final PassService passes;
    private final WheelSource wheels;
    private final CloudSender cloud;

    public WaysideHealthIndicator(PassService passes, WheelSource wheels, CloudSender cloud) {
        this.passes = passes;
        this.wheels = wheels;
        this.cloud = cloud;
    }

    @Override
    public Health health() {
        String problem = passes.spoolProblem();
        Health.Builder builder = problem == null ? Health.up() : Health.outOfService()
                .withDetail("spool", "unwritable").withDetail("reason", problem);
        builder.withDetail("currentSequence", passes.currentSequence())
                .withDetail("pass", passes.state().name())
                .withDetail("wheelLink", wheels.isUp() ? "UP" : "DOWN");
        if (!wheels.isUp()) {
            builder.withDetail("wheelLinkReason", String.valueOf(wheels.status().reason()))
                    .withDetail("mode", "RFID-only: axles, direction and speed are not reported");
        }
        if (cloud.isEnabled()) {
            builder.withDetail("cloudSpoolDepth", cloud.spoolDepth());
            int abandoned = cloud.deadLetterDepth();
            if (abandoned > 0) {
                builder.withDetail("abandonedPasses", abandoned);
            }
        } else {
            builder.withDetail("cloud", "disabled (wayside.cloud.url empty)");
        }
        return builder.build();
    }
}
