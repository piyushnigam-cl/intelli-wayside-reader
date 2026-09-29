package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.WaysideProperties;
import com.intelli.rfid.wayside.wheel.WheelMessage.Hello;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SAMD21 on UART3 ({@code /dev/ttyAMA3}, GPIO4/5), 115200 8N1, no flow control (design sec.4.1).
 *
 * <p><b>No serial library, on purpose.</b> The port is configured with {@code stty} and then read
 * and written as a plain file. Linux is the only target, and a native serial jar would be a second
 * JNI library on a board where the first one has already cost days. {@code min 0 time 5} makes each
 * read return within half a second when the line is quiet, which is what lets {@link #stop()} end
 * the reader thread without closing a descriptor out from under a blocked {@code read()}.
 *
 * <p><b>Thresholds of zero refuse to open the link</b> (design sec.8): a detector running on a
 * guess produces axles that look real. The app still runs, RFID-only, and says why.
 */
public class SerialWheelSource extends FramedWheelSource {

    private static final Logger log = LoggerFactory.getLogger(SerialWheelSource.class);
    private static final long REOPEN_DELAY_MS = 5000;

    private final WaysideProperties.Wheel config;
    private volatile boolean running;
    private volatile OutputStream out;
    private Thread reader;

    public SerialWheelSource(WaysideProperties.Wheel config) {
        super("SERIAL " + config.getPort(), config.getLinkTimeoutMs(), System::nanoTime);
        this.config = config;
    }

    @Override
    protected void open() {
        if (!config.getDetect().isConfigured()) {
            String why = "wayside.wheel.detect thresholds are not set (covered-ua, uncovered-ua, "
                    + "min-pulse-us must all be > 0); the wheel link will not start against a guess. "
                    + "Derive them from the sensor datasheet and a capture (design sec.4.5).";
            log.warn("Wheel link not started: {}", why);
            setDown(why);
            return;
        }
        running = true;
        reader = new Thread(this::readLoop, "wayside-wheel-rx");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    protected void close() {
        running = false;
        if (reader != null) {
            reader.interrupt();
        }
    }

    @Override
    protected void write(byte[] wire) {
        OutputStream stream = out;
        if (stream == null) {
            return;
        }
        try {
            stream.write(wire);
            stream.flush();
        } catch (IOException e) {
            log.warn("Could not write to {}: {}", config.getPort(), e.getMessage());
        }
    }

    @Override
    protected void onHello(Hello hello) {
        WaysideProperties.Detect detect = config.getDetect();
        send(WheelMessage.SET_DETECT, WheelMessages.setDetect(
                detect.getCoveredUa(), detect.getUncoveredUa(), detect.getMinPulseUs()));
        log.info("Detection thresholds sent to the SAMD21: covered {} µA, uncovered {} µA, "
                        + "min pulse {} µs", detect.getCoveredUa(), detect.getUncoveredUa(),
                detect.getMinPulseUs());
    }

    private void readLoop() {
        byte[] buffer = new byte[512];
        while (running) {
            try {
                configurePort();
                try (InputStream in = new FileInputStream(config.getPort());
                     OutputStream stream = new FileOutputStream(config.getPort())) {
                    out = stream;
                    log.info("Wheel link port {} open at {} baud; asking the SAMD21 for HELLO",
                            config.getPort(), config.getBaud());
                    send(WheelMessage.GET_INFO, WheelMessages.getInfo());
                    while (running) {
                        int n = in.read(buffer);
                        // -1 is VTIME expiring on a quiet line, not end of stream: a tty has none.
                        if (n > 0) {
                            onBytes(buffer, 0, n);
                        }
                    }
                } finally {
                    out = null;
                }
            } catch (IOException | RuntimeException e) {
                setDown(config.getPort() + ": " + e.getMessage());
                log.warn("Wheel link port {} failed: {}; retrying in {} ms", config.getPort(),
                        e.getMessage(), REOPEN_DELAY_MS);
                sleep(REOPEN_DELAY_MS);
            }
        }
    }

    private void configurePort() throws IOException {
        List<String> command = List.of("stty", "-F", config.getPort(),
                Integer.toString(config.getBaud()), "raw", "-echo", "cs8", "-cstopb", "-parenb",
                "-crtscts", "-ixon", "-ixoff", "clocal", "cread", "min", "0", "time", "5");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("stty timed out");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes()).trim();
                throw new IOException("stty exited " + process.exitValue() + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted configuring the port");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
