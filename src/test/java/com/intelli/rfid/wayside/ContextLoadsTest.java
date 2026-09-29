package com.intelli.rfid.wayside;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the whole application context, with no module, no serial port and no disk.
 *
 * <p>Written after 2026-09-29, when a new controller mapped POST /api/v1/tags/write, which core's
 * CommissioningController already owns. Every unit test passed, and the deployed service
 * crash-looped on "Ambiguous mapping". Only a context load sees route collisions, bean wiring and
 * configuration binding together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rfid.reader.auto-start=false",
        "rfid.security.keys[0].id=test",
        "rfid.security.keys[0].sha256=0000000000000000000000000000000000000000000000000000000000000000",
        "rfid.security.keys[0].scopes=ADMIN",
        "rfid.gs1.serial-counter-file=target/test-serial",
        "wayside.wheel.source=NONE",
        "wayside.spool-enabled=false",
        "wayside.cloud.spool-file=target/test-cloud.jsonl",
        "wayside.cloud.dead-letter-file=target/test-dead.jsonl",
        "logging.file.name=target/test.log"})
class ContextLoadsTest {

    @Test
    void theApplicationStarts() {
    }
}
