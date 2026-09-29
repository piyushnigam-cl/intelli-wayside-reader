package com.intelli.rfid.wayside.trigger;

import com.intelli.rfid.wayside.WaysideProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Train start on J26 IN1 and train end on IN2 — a stand-in for the Frauscher sensors until they
 * are fitted (operator, 2026-09-29).
 *
 * <p>Adapted from the tunnel's {@code GpioEdgeMonitor}, whose every choice was measured on this
 * carrier and is kept for the same reasons:
 * <ul>
 *   <li>{@code gpiomon}, not a JNI GPIO library: one child process, kernel edge timestamps.
 *   <li><b>{@code stdbuf -oL}</b>: gpiomon block-buffers into a pipe, and without this the edges
 *       are detected and then sit in a 4 KB buffer for hours with no error anywhere.
 *   <li><b>{@code -l}</b> (active-low): 24 V on a J26 input lights the opto and pulls the pin LOW,
 *       so without it "rising" fires when the signal is <i>released</i>.
 *   <li>libgpiod v1 and v2 take different arguments; v2 prints an unknown format specifier
 *       literally, which looks like a dead sensor. The version is detected.
 * </ul>
 *
 * <p>The edge timestamps are CLOCK_MONOTONIC, the clock {@code System.nanoTime()} reads on Linux,
 * so they drop straight into the pass logic's timeline.
 */
public class GpioTrigger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpioTrigger.class);
    private static final long RESTART_DELAY_MS = 2_000;
    private static final Pattern VERSION = Pattern.compile("v(\\d+)\\.\\d+");

    private final WaysideProperties.Gpio config;
    private final Map<Integer, Long> lastEdgeNanos = new ConcurrentHashMap<>();
    private volatile LongConsumer onStart = n -> {};
    private volatile LongConsumer onEnd = n -> {};
    private volatile Process process;
    private volatile Thread reader;
    private volatile boolean running;
    private volatile boolean started;
    private volatile String problem = "not started";
    private volatile Integer majorVersion;

    public GpioTrigger(WaysideProperties.Gpio config) {
        this.config = config;
    }

    public synchronized void start(LongConsumer onStart, LongConsumer onEnd) {
        this.onStart = onStart;
        this.onEnd = onEnd;
        if (running) {
            return;
        }
        running = true;
        reader = new Thread(this::monitorLoop, "wayside-gpio");
        reader.setDaemon(true);
        reader.start();
    }

    /** True while gpiomon is running and has not reported a problem. */
    public boolean isWatching() {
        return running && started && problem == null;
    }

    /** Null while watching; otherwise why not. */
    public String problem() {
        return problem;
    }

    /** Bench: fires a line's handler as though gpiomon had reported the edge, debounce included. */
    public boolean inject(boolean start) {
        int line = start ? config.getStartLine() : config.getEndLine();
        long nanos = System.nanoTime();
        if (debounced(line, nanos)) {
            return false;
        }
        log.info("Synthetic train {} (line {})", start ? "START" : "END", line);
        (start ? onStart : onEnd).accept(nanos);
        return true;
    }

    @Override
    public synchronized void close() {
        running = false;
        Process current = process;
        if (current != null) {
            current.destroy();
        }
        if (reader != null) {
            reader.interrupt();
        }
    }

    private void monitorLoop() {
        while (running) {
            try {
                runMonitor();
            } catch (IOException e) {
                fail("Could not run " + config.getCommand() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                fail("GPIO trigger failed: " + e);
            }
            if (running) {
                sleep(RESTART_DELAY_MS);
            }
        }
    }

    List<String> commandLine() {
        List<String> command = new ArrayList<>();
        if (!config.getLineBufferCommand().isBlank()) {
            command.add(config.getLineBufferCommand());
            command.add("-oL");
        }
        command.add(config.getCommand());
        if (config.isActiveLow()) {
            command.add("-l");
        }
        if (majorVersion() >= 2) {
            command.add("--edges=rising");
            command.add("--format=%o %S");
            command.add("-c");
            command.add(config.getChip());
        } else {
            command.add("--rising-edge");
            command.add("--format=%o %s.%n");
            command.add(config.getChip());
        }
        command.add(String.valueOf(config.getStartLine()));
        command.add(String.valueOf(config.getEndLine()));
        return command;
    }

    private int majorVersion() {
        Integer known = majorVersion;
        if (known != null) {
            return known;
        }
        int detected = config.getLibgpiodMajor() > 0 ? config.getLibgpiodMajor() : detect();
        majorVersion = detected;
        return detected;
    }

    private int detect() {
        try {
            Process p = new ProcessBuilder(config.getCommand(), "--version")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            Matcher m = VERSION.matcher(out);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not probe gpiomon --version: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.warn("Could not determine the libgpiod version; assuming v2");
        return 2;
    }

    private void runMonitor() throws IOException, InterruptedException {
        List<String> command = commandLine();
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        process = p;
        started = true;
        problem = null;
        log.info("Watching train START on line {} and END on line {}: {}", config.getStartLine(),
                config.getEndLine(), String.join(" ", command));
        boolean sawAnything = false;
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = out.readLine()) != null) {
                if (handle(line)) {
                    sawAnything = true;
                } else if (!sawAnything) {
                    fail("gpiomon said: " + line.trim());
                }
            }
        }
        int exit = p.waitFor();
        if (running) {
            fail("gpiomon exited with " + exit + "; train start/end are not being watched");
        }
    }

    /** @return true if the line was a parseable edge */
    boolean handle(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 2) {
            return false;
        }
        int offset;
        long nanos;
        try {
            offset = Integer.parseInt(parts[0]);
            String[] time = parts[1].split("\\.");
            nanos = Long.parseLong(time[0]) * 1_000_000_000L + Long.parseLong(time[1]);
        } catch (RuntimeException e) {
            return false;
        }
        if (debounced(offset, nanos)) {
            return true;
        }
        try {
            if (offset == config.getStartLine()) {
                log.info("Train START (J26 IN1, line {})", offset);
                onStart.accept(nanos);
            } else if (offset == config.getEndLine()) {
                log.info("Train END (J26 IN2, line {})", offset);
                onEnd.accept(nanos);
            }
        } catch (RuntimeException e) {
            log.error("Trigger handler for line {} threw", offset, e);
        }
        return true;
    }

    private boolean debounced(int offset, long nanos) {
        long window = config.getDebounceMs() * 1_000_000L;
        if (window <= 0) {
            return false;
        }
        Long previous = lastEdgeNanos.put(offset, nanos);
        return previous != null && nanos - previous < window;
    }

    private void fail(String message) {
        if (!message.equals(problem)) {
            log.error("{}. Passes fall back to RFID-only (open on the first tag, close on silence).",
                    message);
        }
        problem = message;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
