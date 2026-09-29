package com.intelli.rfid.wayside;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code wayside.*}. Every timing and geometry value here is a working default, not a site value:
 * {@code docs/Wayside-Reader-Design.md} sec.8 says how each is re-derived at Charkop.
 */
@ConfigurationProperties(prefix = "wayside")
public class WaysideProperties {

    /** Per unit, and it goes into every cloud call. Blank is warned about at start-up. */
    private String readerId = "";

    /** Pass history: every published train, one JSON line each, replayable by sequence. */
    private String spoolDir = "/var/lib/intelli/wayside/spool";
    private boolean spoolEnabled = true;

    private final Wheel wheel = new Wheel();
    private final Pass pass = new Pass();
    private final Rfid rfid = new Rfid();
    private final Train train = new Train();
    private final Cloud cloud = new Cloud();
    private final Bench bench = new Bench();

    public enum WheelSourceType {
        /** The SAMD21 on UART3. The production setting. */
        SERIAL,
        /** Generated trains, driven by {@code POST /api/bench/train}. Bench and test only. */
        SIMULATED,
        /** No wheel sensing at all: every pass is RFID-only (degraded). */
        NONE
    }

    public enum CarrierMode {
        /** On when a train opens a pass, off after the tail. The default (design sec.5.2). */
        TRIGGERED,
        /** Always on. For commissioning, or a site whose geometry cannot trigger in time. */
        ALWAYS
    }

    public enum DecodeMode {
        /** No rule yet: train id is null and both EPCs are reported raw. */
        RAW,
        /** {@code train.pattern} is matched against the EPC hex; named group {@code id} is the train. */
        REGEX
    }

    public static class Wheel {
        private WheelSourceType source = WheelSourceType.SERIAL;
        private String port = "/dev/ttyAMA3";
        private int baud = 115200;

        /**
         * Which way is UP. Proven by a train of known direction on commissioning, never by review:
         * wired the other way, every direction is confidently wrong.
         */
        private boolean upIsAToB = true;

        /** Rail distance between head A and head B. 0 = no speed is reported. Measure on site. */
        private double headSpacingM = 0;

        /**
         * Distance between the two systems inside one head, for the coarse single-head speed
         * fallback. 0 = no fallback. From the sensor datasheet.
         */
        private double systemSpacingM = 0;

        /**
         * The longest time between the two systems of one head seeing the same wheel. Beyond this
         * two pulses are not the same axle. system spacing / slowest speed, with margin.
         */
        private long systemPairMaxMs = 2000;

        /** No valid frame for this long and the link is DOWN. The SAMD21 heartbeats at 1 Hz. */
        private long linkTimeoutMs = 3000;

        private final Detect detect = new Detect();

        public WheelSourceType getSource() { return source; }
        public void setSource(WheelSourceType source) { this.source = source; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        public int getBaud() { return baud; }
        public void setBaud(int baud) { this.baud = baud; }
        public boolean isUpIsAToB() { return upIsAToB; }
        public void setUpIsAToB(boolean upIsAToB) { this.upIsAToB = upIsAToB; }
        public double getHeadSpacingM() { return headSpacingM; }
        public void setHeadSpacingM(double headSpacingM) { this.headSpacingM = headSpacingM; }
        public double getSystemSpacingM() { return systemSpacingM; }
        public void setSystemSpacingM(double systemSpacingM) { this.systemSpacingM = systemSpacingM; }
        public long getSystemPairMaxMs() { return systemPairMaxMs; }
        public void setSystemPairMaxMs(long systemPairMaxMs) { this.systemPairMaxMs = systemPairMaxMs; }
        public long getLinkTimeoutMs() { return linkTimeoutMs; }
        public void setLinkTimeoutMs(long linkTimeoutMs) { this.linkTimeoutMs = linkTimeoutMs; }
        public Detect getDetect() { return detect; }
    }

    /**
     * Detection thresholds pushed to the SAMD21 at connect. All zero refuses to start the serial
     * wheel link, rather than detecting against a guess.
     *
     * <p>{@code coveredUa} and {@code uncoveredUa} are <b>deviations from the SAMD21's tracked
     * baseline</b>, not absolute currents: the RSR110 datasheet gives 5 mA and "a change" when
     * damped, without saying which way, so intelli-samd21-fw detects on {@code |I - baseline|}.
     * {@code uncoveredUa} must be below {@code coveredUa}, or the SAMD21 NAKs the whole command.
     */
    public static class Detect {
        private int coveredUa = 0;
        private int uncoveredUa = 0;
        private int minPulseUs = 0;

        public boolean isConfigured() {
            return coveredUa > 0 && uncoveredUa > 0 && minPulseUs > 0;
        }

        public int getCoveredUa() { return coveredUa; }
        public void setCoveredUa(int coveredUa) { this.coveredUa = coveredUa; }
        public int getUncoveredUa() { return uncoveredUa; }
        public void setUncoveredUa(int uncoveredUa) { this.uncoveredUa = uncoveredUa; }
        public int getMinPulseUs() { return minPulseUs; }
        public void setMinPulseUs(int minPulseUs) { this.minPulseUs = minPulseUs; }
    }

    public static class Pass {
        /**
         * No wheel event for this long, with every channel uncovered, and the train has gone.
         * Must exceed max axle spacing / slowest crossing speed: 15 m at 5 km/h is 10.8 s.
         */
        private long axleGapMs = 15000;

        /** Backstop: a train stopped over the sensors, or a stuck channel, closes as TIMEOUT. */
        private long maxPassMs = 600000;

        /** Tags seen this long before the pass opened still belong to it (read-window batching). */
        private long rfidLeadMs = 200;

        /** Carrier stays up this long after the wheels clear, for the trailing tag. */
        private long rfidTailMs = 3000;

        /** Degraded mode only (no wheel link): silence since the last read that closes a pass. */
        private long tagGapMs = 5000;

        public long getAxleGapMs() { return axleGapMs; }
        public void setAxleGapMs(long axleGapMs) { this.axleGapMs = axleGapMs; }
        public long getMaxPassMs() { return maxPassMs; }
        public void setMaxPassMs(long maxPassMs) { this.maxPassMs = maxPassMs; }
        public long getRfidLeadMs() { return rfidLeadMs; }
        public void setRfidLeadMs(long rfidLeadMs) { this.rfidLeadMs = rfidLeadMs; }
        public long getRfidTailMs() { return rfidTailMs; }
        public void setRfidTailMs(long rfidTailMs) { this.rfidTailMs = rfidTailMs; }
        public long getTagGapMs() { return tagGapMs; }
        public void setTagGapMs(long tagGapMs) { this.tagGapMs = tagGapMs; }
    }

    public static class Rfid {
        private CarrierMode carrier = CarrierMode.TRIGGERED;

        public CarrierMode getCarrier() { return carrier; }
        public void setCarrier(CarrierMode carrier) { this.carrier = carrier; }
    }

    public static class Train {
        private DecodeMode decode = DecodeMode.RAW;
        /** REGEX mode: a Java regex over the uppercase EPC hex with a named group {@code id}. */
        private String pattern = "";
        /** One tag at each end. */
        private int tagsExpected = 2;

        public DecodeMode getDecode() { return decode; }
        public void setDecode(DecodeMode decode) { this.decode = decode; }
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public int getTagsExpected() { return tagsExpected; }
        public void setTagsExpected(int tagsExpected) { this.tagsExpected = tagsExpected; }
    }

    public static class Cloud {
        /** Empty = results go to the local pass history only and nothing is sent. */
        private String url = "";
        /** Bearer token. Site config only, never the packaged file. */
        private String token = "";
        /** 5 s, not the tunnel's 2 s: a trackside uplink is probably cellular. */
        private long timeoutMs = 5000;
        private int attempts = 5;
        private long initialBackoffMs = 1000;
        private long maxBackoffMs = 30000;
        private long replayIntervalMs = 60000;
        /** 7 days, then the entry moves to the dead-letter file: abandoned, not discarded. */
        private long maxAgeMs = 604800000L;
        private String spoolFile = "/var/lib/intelli/wayside/cloud.jsonl";
        private String deadLetterFile = "/var/lib/intelli/wayside/cloud-dead.jsonl";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
        public long getReplayIntervalMs() { return replayIntervalMs; }
        public void setReplayIntervalMs(long replayIntervalMs) { this.replayIntervalMs = replayIntervalMs; }
        public long getMaxAgeMs() { return maxAgeMs; }
        public void setMaxAgeMs(long maxAgeMs) { this.maxAgeMs = maxAgeMs; }
        public String getSpoolFile() { return spoolFile; }
        public void setSpoolFile(String spoolFile) { this.spoolFile = spoolFile; }
        public String getDeadLetterFile() { return deadLetterFile; }
        public void setDeadLetterFile(String deadLetterFile) { this.deadLetterFile = deadLetterFile; }
    }

    public static class Bench {
        /** Train injection. Off in the packaged config; only meaningful with wheel.source SIMULATED. */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public String getReaderId() { return readerId; }
    public void setReaderId(String readerId) { this.readerId = readerId; }
    public String getSpoolDir() { return spoolDir; }
    public void setSpoolDir(String spoolDir) { this.spoolDir = spoolDir; }
    public boolean isSpoolEnabled() { return spoolEnabled; }
    public void setSpoolEnabled(boolean spoolEnabled) { this.spoolEnabled = spoolEnabled; }
    public Wheel getWheel() { return wheel; }
    public Pass getPass() { return pass; }
    public Rfid getRfid() { return rfid; }
    public Train getTrain() { return train; }
    public Cloud getCloud() { return cloud; }
    public Bench getBench() { return bench; }
}
