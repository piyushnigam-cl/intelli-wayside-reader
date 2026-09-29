package com.intelli.rfid.wayside;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Trackside railway reader, Charkop.
 *
 * <p>A train passes; its two tags (one at each end) identify it, the wheel sensors read by the
 * board's SAMD21 count its axles and give direction and speed, and one call per train goes to the
 * cloud. Design, and every value that still has to be measured on site:
 * {@code docs/Wayside-Reader-Design.md} in the workspace repo.
 *
 * <p><b>It cannot share a board with the tunnel.</b> Both own {@code /dev/ttyAMA0} and the JNI
 * library, and each allows a single owner; the systemd unit declares the conflict.
 */
@SpringBootApplication
@EnableConfigurationProperties(WaysideProperties.class)
public class WaysideApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaysideApplication.class, args);
    }
}
