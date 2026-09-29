package com.intelli.rfid.wayside;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Has NTP synchronised since boot? The CM4 has no RTC that survives a hard cut, so a pass stamped
 * before sync can be minutes or days off, and {@code clockSynced: false} says so to the cloud.
 *
 * <p>systemd-timesyncd drops {@code /run/systemd/timesync/synchronized} on first sync; that is a
 * stat, not a fork. Only when it is absent does this ask {@code timedatectl}, cached for a minute.
 */
public class ClockSync {

    private static final Path MARKER = Path.of("/run/systemd/timesync/synchronized");
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private volatile boolean cached;
    private volatile long cachedAt = Long.MIN_VALUE;

    public boolean isSynced() {
        if (Files.exists(MARKER)) {
            return true;
        }
        long now = System.nanoTime();
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < CACHE_NANOS) {
            return cached;
        }
        cached = askTimedatectl();
        cachedAt = now;
        return cached;
    }

    private static boolean askTimedatectl() {
        try {
            Process process = new ProcessBuilder("timedatectl", "show", "-p", "NTPSynchronized",
                    "--value").redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return new String(process.getInputStream().readAllBytes()).trim().equals("yes");
        } catch (Exception e) {
            return false;
        }
    }
}
