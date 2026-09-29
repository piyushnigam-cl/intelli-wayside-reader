package com.intelli.rfid.wayside;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins what the packaged {@code application.yml} must and must not ship. Site values go in
 * {@code /etc/intelli/intelli-wayside-reader/application.yml}.
 */
class PackagedConfigTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yml");

    /** Duplicate keys: Spring Boot refuses them at start-up, so the test must too (the tunnel's lesson). */
    @Test
    void parsesTheWaySpringBootParsesIt() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        assertThatCode(() -> {
            try (InputStream in = Files.newInputStream(CONFIG)) {
                new Yaml(new SafeConstructor(options)).load(in);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsNoSecretsNoBenchAndNoGuessedThresholds() throws Exception {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(CONFIG)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> wayside = (Map<String, Object>) root.get("wayside");
        Map<String, Object> cloud = (Map<String, Object>) wayside.get("cloud");
        Map<String, Object> wheel = (Map<String, Object>) wayside.get("wheel");
        Map<String, Object> detect = (Map<String, Object>) wheel.get("detect");
        Map<String, Object> bench = (Map<String, Object>) wayside.get("bench");
        Map<String, Object> rfid = (Map<String, Object>) root.get("rfid");
        Map<String, Object> reader = (Map<String, Object>) rfid.get("reader");
        Map<String, Object> security = (Map<String, Object>) rfid.get("security");

        assertThat(cloud.get("token")).isEqualTo("");
        assertThat(wayside.get("reader-id")).isEqualTo("");
        assertThat(bench.get("enabled")).isEqualTo(false);
        assertThat(detect).containsEntry("covered-ua", 0).containsEntry("uncovered-ua", 0)
                .containsEntry("min-pulse-us", 0);
        assertThat(wheel.get("source")).isEqualTo("SERIAL");
        assertThat(((Map<String, Object>) wayside.get("rfid")).get("carrier")).isEqualTo("TRIGGERED");
        assertThat(reader.get("antenna-count")).isEqualTo(1);
        assertThat(security.get("enabled")).isEqualTo(true);
        assertThat((java.util.List<?>) security.get("keys")).isEmpty();
        assertThat(((Map<String, Object>) root.get("server")).get("port")).isEqualTo(8082);
    }
}
