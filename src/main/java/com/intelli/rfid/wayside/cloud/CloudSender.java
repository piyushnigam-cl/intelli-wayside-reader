package com.intelli.rfid.wayside.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.pass.PassResult;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One POST per train to {@code wayside.cloud.url}: retry, backoff, spool, replay, dead-letter.
 *
 * <p>Adapted from the tunnel's {@code CallbackSender}, where the behaviour is proven (design sec.6):
 * <ul>
 *   <li><b>Never blocks the pass path.</b> {@link #send} hands off to one background thread.
 *   <li><b>5 s timeout</b>, not the tunnel's 2 s: a trackside uplink is probably cellular.
 *   <li><b>Retry 5xx and timeouts, never 4xx. 401/403 raise an alarm</b> — a bad token is an
 *       operator problem, and retrying it forever fixes nothing.
 *   <li><b>At-least-once, same {@code id} every attempt.</b> The cloud deduplicates on it.
 *   <li>Unreachable for good: spooled, replayed every {@code replay-interval-ms}, and after
 *       {@code max-age-ms} (7 days) moved to the dead-letter file — abandoned, not discarded. The
 *       pass is still in the local history either way.
 * </ul>
 *
 * <p>{@code url} empty means nothing is sent at all; results live in the local pass history only.
 * The URL is captured into each spooled entry, so changing it does not redirect a backlog.
 */
public class CloudSender {

    private static final Logger log = LoggerFactory.getLogger(CloudSender.class);

    private final WaysideProperties.Cloud properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Path spoolFile;
    private final Path deadLetterFile;
    private final Clock clock;

    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "wayside-cloud");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledExecutorService replayTimer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "wayside-cloud-replay");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running = true;
    private volatile String lastOutcome;
    private volatile Instant lastDeliveredAt;

    public CloudSender(WaysideProperties.Cloud properties, ObjectMapper mapper) {
        this(properties, mapper, Clock.systemUTC());
    }

    /** Clock injected so the age cap is testable without waiting seven days. */
    public CloudSender(WaysideProperties.Cloud properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.spoolFile = Path.of(properties.getSpoolFile());
        this.deadLetterFile = Path.of(properties.getDeadLetterFile());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        long interval = properties.getReplayIntervalMs();
        if (interval > 0) {
            replayTimer.scheduleWithFixedDelay(
                    this::replayQuietly, interval, interval, TimeUnit.MILLISECONDS);
        }
        if (!isEnabled()) {
            log.warn("wayside.cloud.url is empty: passes go to the local history only and nothing "
                    + "is sent. Set it in the site config once the cloud endpoint exists.");
        } else if (properties.getToken().isBlank()) {
            log.warn("wayside.cloud.token is empty, so passes will carry no Authorization header.");
        }
    }

    public boolean isEnabled() {
        return properties.getUrl() != null && !properties.getUrl().isBlank();
    }

    /** Queues a pass for delivery and returns immediately. Never throws at the call site. */
    public void send(PassResult result) {
        if (!isEnabled()) {
            return;
        }
        String url = properties.getUrl();
        try {
            String body = mapper.writeValueAsString(result);
            sender.execute(() -> deliverOrSpool(url, result.id(), body));
        } catch (RuntimeException | IOException e) {
            log.error("Could not serialise pass {} for the cloud; it is still in the local history",
                    result.id(), e);
        }
    }

    /** Retries whatever is parked on the spool. Cheap when the spool is empty. */
    public synchronized void replaySpool() {
        List<Pending> pending = readSpool();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Replaying {} spooled pass(es)", pending.size());
        List<Pending> stillFailing = new ArrayList<>();
        int deadLettered = 0;
        for (Pending item : pending) {
            // Age before the attempt: an expired backlog costs no network at all.
            if (isExpired(item)) {
                deadLetter(item);
                deadLettered++;
                continue;
            }
            if (attempt(item.url(), item.id(), item.body()) != Outcome.DELIVERED) {
                stillFailing.add(item);
            }
        }
        writeSpool(stillFailing);
        if (deadLettered > 0) {
            log.error("{} pass(es) outlived wayside.cloud.max-age-ms and were moved to {}. Delivery "
                    + "is abandoned for them; they remain in the local pass history.",
                    deadLettered, deadLetterFile);
        }
        log.info("Spool replay done: {} delivered, {} dead-lettered, {} still pending",
                pending.size() - stillFailing.size() - deadLettered, deadLettered,
                stillFailing.size());
    }

    private void replayQuietly() {
        try {
            replaySpool();
        } catch (RuntimeException e) {
            log.error("Scheduled spool replay failed; will try again", e);
        }
    }

    public int spoolDepth() {
        return readSpool().size();
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    public Instant lastDeliveredAt() {
        return lastDeliveredAt;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        replayTimer.shutdownNow();
        sender.shutdownNow();
    }

    // ---------------------------------------------------------------- delivery

    private void deliverOrSpool(String url, String id, String body) {
        long backoff = properties.getInitialBackoffMs();
        for (int attempt = 1; running && attempt <= properties.getAttempts(); attempt++) {
            Outcome outcome = attempt(url, id, body);
            if (outcome == Outcome.DELIVERED || outcome == Outcome.REJECTED) {
                return;
            }
            if (attempt < properties.getAttempts()) {
                sleep(backoff);
                backoff = Math.min(backoff * 2, properties.getMaxBackoffMs());
            }
        }
        if (running) {
            spool(new Pending(url, id, body, Instant.now(clock).toString()));
        }
    }

    private Outcome attempt(String url, String id, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String token = properties.getToken();
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.info("Pass {} delivered to {} ({})", id, url, status);
                lastOutcome = "delivered " + status;
                lastDeliveredAt = Instant.now(clock);
                return Outcome.DELIVERED;
            }
            if (status >= 300 && status < 400) {
                // Not followed. A redirected POST is re-sent as a GET by most clients, so following it
                // would "succeed" against a page that never saw the pass. The URL is wrong, or the
                // endpoint wants another scheme or path, and that needs a person.
                String location = response.headers().firstValue("Location").orElse("(no Location)");
                log.error("Cloud answered pass {} with {} -> {}. Not following and not retrying: "
                        + "check wayside.cloud.url.", id, status, location);
                lastOutcome = "redirected " + status + " to " + location;
                return Outcome.REJECTED;
            }
            if (status == 401 || status == 403) {
                log.error("Cloud rejected pass {} with {}: the token is wrong or revoked. Not "
                        + "retrying. This needs an operator, not a backoff.", id, status);
                lastOutcome = "rejected " + status + " (token)";
                return Outcome.REJECTED;
            }
            if (status >= 400 && status < 500) {
                log.error("Cloud rejected pass {} with {}: {}. Not retrying.",
                        id, status, abbreviate(response.body()));
                lastOutcome = "rejected " + status;
                return Outcome.REJECTED;
            }
            log.warn("Cloud returned {} for pass {}; will retry", status, id);
            lastOutcome = "retrying after " + status;
            return Outcome.RETRYABLE;
        } catch (IOException e) {
            log.warn("Cloud POST for pass {} to {} failed: {}; will retry", id, url, e.toString());
            lastOutcome = "retrying after " + e.getClass().getSimpleName();
            return Outcome.RETRYABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.RETRYABLE;
        }
    }

    private enum Outcome { DELIVERED, RETRYABLE, REJECTED }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    // ---------------------------------------------------------------- spool

    /** @param body the exact bytes sent on every attempt, so the id and the payload cannot drift */
    private record Pending(String url, String id, String body, String spooledAt) {}

    private boolean isExpired(Pending item) {
        long maxAge = properties.getMaxAgeMs();
        if (maxAge <= 0 || item.spooledAt() == null) {
            return false;
        }
        try {
            return Duration.between(Instant.parse(item.spooledAt()), Instant.now(clock)).toMillis()
                    > maxAge;
        } catch (RuntimeException e) {
            log.warn("Spooled pass {} has an unreadable spooledAt {}; keeping it",
                    item.id(), item.spooledAt());
            return false;
        }
    }

    /** Append-only and never read back: a record for an operator. */
    private void deadLetter(Pending item) {
        log.error("Pass {} to {} has been failing since {} and is past max-age-ms={}; moving to {}.",
                item.id(), item.url(), item.spooledAt(), properties.getMaxAgeMs(), deadLetterFile);
        try {
            Path parent = deadLetterFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(deadLetterFile,
                    mapper.writeValueAsString(item) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Could not append pass {} to the dead-letter file at {}; this log line is now "
                    + "the only record of the abandoned delivery", item.id(), deadLetterFile, e);
        }
    }

    public int deadLetterDepth() {
        if (!Files.isRegularFile(deadLetterFile)) {
            return 0;
        }
        try (var lines = Files.lines(deadLetterFile, StandardCharsets.UTF_8)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            log.warn("Could not read the dead-letter file at {}", deadLetterFile, e);
            return 0;
        }
    }

    private synchronized void spool(Pending item) {
        List<Pending> pending = readSpool();
        pending.add(item);
        writeSpool(pending);
        log.error("Cloud unreachable after {} attempts; pass {} spooled to {} (depth {})",
                properties.getAttempts(), item.id(), spoolFile, pending.size());
    }

    private List<Pending> readSpool() {
        List<Pending> pending = new ArrayList<>();
        if (!Files.isRegularFile(spoolFile)) {
            return pending;
        }
        try {
            for (String line : Files.readAllLines(spoolFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    pending.add(mapper.readValue(line, Pending.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable cloud spool line");
                }
            }
        } catch (IOException e) {
            log.error("Could not read the cloud spool at {}", spoolFile, e);
        }
        return pending;
    }

    private void writeSpool(List<Pending> pending) {
        try {
            Path parent = spoolFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder out = new StringBuilder();
            for (Pending item : pending) {
                out.append(mapper.writeValueAsString(item)).append(System.lineSeparator());
            }
            Files.writeString(spoolFile, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Could not write the cloud spool at {}; {} pass(es) are only in memory",
                    spoolFile, pending.size(), e);
        }
    }

    /** For tests and shutdown ordering. */
    public boolean awaitQuiet(long timeoutMs) throws InterruptedException {
        sender.shutdown();
        return sender.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
