package com.intelli.rfid.wayside.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.pass.PassResult;
import com.intelli.rfid.wayside.pass.StopReason;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudSenderTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CloudSender sender(String token) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/passes", exchange -> {
            auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        WaysideProperties.Cloud cloud = new WaysideProperties.Cloud();
        cloud.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/passes");
        cloud.setToken(token);
        cloud.setAttempts(3);
        cloud.setInitialBackoffMs(10);
        cloud.setMaxBackoffMs(20);
        cloud.setReplayIntervalMs(0);
        cloud.setSpoolFile(dir.resolve("cloud.jsonl").toString());
        cloud.setDeadLetterFile(dir.resolve("cloud-dead.jsonl").toString());
        return new CloudSender(cloud, mapper);
    }

    private static PassResult pass(String id) {
        return new PassResult(PassResult.SCHEMA_VERSION, id, "charkop-01", 7, Instant.parse("2026-09-29T10:00:00Z"),
                Instant.parse("2026-09-29T10:00:20Z"), StopReason.CLEARED, true,
                new PassResult.Train(null, false, 2, 0, false), List.of(),
                new PassResult.Wheels("DOWN", null, null, null, null, List.of()),
                new PassResult.Reader("test", null, "RG_IN"));
    }

    @Test
    void deliversWithABearerTokenAndSerialisesNulls() throws Exception {
        CloudSender sender = sender("s3cret");
        sender.send(pass("p-1"));
        assertThat(sender.awaitQuiet(5000)).isTrue();
        assertThat(auth).containsExactly("Bearer s3cret");
        // A null direction is this reader declining to guess, and must reach the cloud as null.
        assertThat(mapper.readTree(bodies.get(0)).path("wheels").has("direction")).isTrue();
        assertThat(mapper.readTree(bodies.get(0)).path("wheels").get("direction").isNull()).isTrue();
        // The version is the first field, so a consumer can check it before anything else.
        assertThat(bodies.get(0)).startsWith("{\"schemaVersion\":1,");
        assertThat(sender.spoolDepth()).isZero();
    }

    @Test
    void aFourHundredIsNeverRetried() throws Exception {
        status.set(422);
        CloudSender sender = sender("");
        sender.send(pass("p-2"));
        assertThat(sender.awaitQuiet(5000)).isTrue();
        assertThat(bodies).hasSize(1);
        assertThat(auth).containsExactly("null");
        assertThat(sender.spoolDepth()).isZero();
    }

    @Test
    void aRedirectIsNotFollowedNorRetried() throws Exception {
        status.set(302);
        CloudSender sender = sender("");
        sender.send(pass("p-4"));
        assertThat(sender.awaitQuiet(5000)).isTrue();
        assertThat(bodies).hasSize(1);
        assertThat(sender.spoolDepth()).isZero();
        assertThat(sender.lastOutcome()).startsWith("redirected 302");
    }

    @Test
    void aFiveHundredIsRetriedThenSpooledWithTheSameIdAndReplayed() throws Exception {
        status.set(503);
        CloudSender sender = sender("t");
        sender.send(pass("p-3"));
        assertThat(sender.awaitQuiet(5000)).isTrue();
        assertThat(bodies).hasSize(3);
        assertThat(sender.spoolDepth()).isEqualTo(1);

        status.set(200);
        sender.replaySpool();
        assertThat(sender.spoolDepth()).isZero();
        assertThat(bodies).hasSize(4);
        assertThat(bodies).allSatisfy(b -> assertThat(b).contains("\"id\":\"p-3\""));
    }
}
