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

    /**
     * Sent in every pass as "Site", "ToolId" and "TrainType" (operator, 2026-09-29: Charkop,
     * C420460060, MRS1). Per site, so set in the site config; blank is sent as null.
     */
    private String site = "";
    private String toolId = "";
    private String trainType = "";

    /** Pass history: every published train, one JSON line each, replayable by sequence. */
    private String spoolDir = "/var/lib/intelli/wayside/spool";
    private boolean spoolEnabled = true;

    private final Trigger trigger = new Trigger();
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

    public enum TriggerSource {
        /** Wheel sensors via the SAMD21: the design, once the Frauscher sensors are fitted. */
        WHEELS,
        /**
         * J26 IN1 and IN2, a stand-in for the wheel sensors (operator, 2026-09-29). Whichever fires
         * first starts the train and gives its direction; the other one ends it. No axles or speed.
         */
        GPIO
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
        REGEX,
        /** The train-set car tag format, Table-3: 8A8, line, set, car type, position, side, serial. */
        CAR_TAG
    }

    public static class Trigger {
        private TriggerSource source = TriggerSource.WHEELS;
        private final Gpio gpio = new Gpio();

        public TriggerSource getSource() { return source; }
        public void setSource(TriggerSource source) { this.source = source; }
        public Gpio getGpio() { return gpio; }
    }

    /** J26 inputs through {@code gpiomon}, exactly as the tunnel watches them. */
    public static class Gpio {
        private String command = "gpiomon";
        /** gpiomon block-buffers into a pipe; without this edges sit unseen for hours. */
        private String lineBufferCommand = "stdbuf";
        private String chip = "gpiochip0";
        /** J26 IN1 = BCM 23. */
        private int in1Line = 23;
        /** J26 IN2 = BCM 24. */
        private int in2Line = 24;
        /**
         * Whichever input fires first starts the train, and that names the direction: IN1 first is
         * UP when this is true. Flip it rather than rewiring if the site's UP runs the other way.
         */
        private boolean in1IsUp = true;
        /**
         * true: an input asserted at J26 (24 V, opto on) is GPIO LOW, so "rising" must mean "became
         * active" (gpiomon -l). Measured on the production carrier; the tunnel learned it the hard way.
         */
        private boolean activeLow = true;
        private long debounceMs = 50;
        /** 0 = detect from gpiomon --version (v2 on Trixie, v1 on Bookworm). */
        private int libgpiodMajor = 0;

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getLineBufferCommand() { return lineBufferCommand; }
        public void setLineBufferCommand(String lineBufferCommand) { this.lineBufferCommand = lineBufferCommand; }
        public String getChip() { return chip; }
        public void setChip(String chip) { this.chip = chip; }
        public int getIn1Line() { return in1Line; }
        public void setIn1Line(int in1Line) { this.in1Line = in1Line; }
        public int getIn2Line() { return in2Line; }
        public void setIn2Line(int in2Line) { this.in2Line = in2Line; }
        public boolean isIn1IsUp() { return in1IsUp; }
        public void setIn1IsUp(boolean in1IsUp) { this.in1IsUp = in1IsUp; }
        public boolean isActiveLow() { return activeLow; }
        public void setActiveLow(boolean activeLow) { this.activeLow = activeLow; }
        public long getDebounceMs() { return debounceMs; }
        public void setDebounceMs(long debounceMs) { this.debounceMs = debounceMs; }
        public int getLibgpiodMajor() { return libgpiodMajor; }
        public void setLibgpiodMajor(int libgpiodMajor) { this.libgpiodMajor = libgpiodMajor; }
    }

    public static class Wheel {
        private WheelSourceType source = WheelSourceType.SERIAL;
        private String port = "/dev/ttyAMA3";
        private int baud = 115200;

        /*
         * Direction is not configurable (operator, 2026-10-09): Wheel 1 (J23) seeing the train first
         * is UP, Wheel 2 (J22) first is DOWN. A site wired the other way round is rewired, not
         * reconfigured. The old up-is-a-to-b flag is gone, and a site file that still sets it is
         * ignored.
         */

        /**
         * Rail distance between Wheel 1 and Wheel 2, measured on site. 0 = no sensor-to-sensor
         * speed. Was {@code head-spacing-m}, which is still accepted.
         */
        private double sensorSpacingM = 0;

        /**
         * Distance between the two sensing elements inside one RSR110d, measured on site; one value
         * for both sensors. Gives the speed at each sensor. 0 = no per-sensor speed. Was
         * {@code system-spacing-m}, which is still accepted.
         */
        private double elementSpacingM = 0;

        /**
         * The longest time between the two elements of one sensor seeing the same wheel. Beyond this
         * two pulses are not the same axle. element spacing / slowest speed, with margin.
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
        public double getSensorSpacingM() { return sensorSpacingM; }
        public void setSensorSpacingM(double sensorSpacingM) { this.sensorSpacingM = sensorSpacingM; }
        public double getElementSpacingM() { return elementSpacingM; }
        public void setElementSpacingM(double elementSpacingM) { this.elementSpacingM = elementSpacingM; }
        /** The old name of {@code sensor-spacing-m}, kept so an existing site file still binds. */
        public void setHeadSpacingM(double headSpacingM) { this.sensorSpacingM = headSpacingM; }
        /** The old name of {@code element-spacing-m}, kept so an existing site file still binds. */
        public void setSystemSpacingM(double systemSpacingM) { this.elementSpacingM = systemSpacingM; }
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
     * damped, without saying which way, so intelli-wayside-reader-mcu detects on {@code |I - baseline|}.
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
        /** CAR_TAG: a site's own train set lookup CSV (line,id2,ts); blank = the packaged table. */
        private String lookupFile = "";
        /** One tag at each end. */
        private int tagsExpected = 2;

        public DecodeMode getDecode() { return decode; }
        public void setDecode(DecodeMode decode) { this.decode = decode; }
        public String getPattern() { return pattern; }
        public String getLookupFile() { return lookupFile; }
        public void setLookupFile(String lookupFile) { this.lookupFile = lookupFile; }
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

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public String getTrainType() { return trainType; }
    public void setTrainType(String trainType) { this.trainType = trainType; }
    public String getReaderId() { return readerId; }
    public void setReaderId(String readerId) { this.readerId = readerId; }
    public String getSpoolDir() { return spoolDir; }
    public void setSpoolDir(String spoolDir) { this.spoolDir = spoolDir; }
    public boolean isSpoolEnabled() { return spoolEnabled; }
    public void setSpoolEnabled(boolean spoolEnabled) { this.spoolEnabled = spoolEnabled; }
    public Trigger getTrigger() { return trigger; }
    public Wheel getWheel() { return wheel; }
    public Pass getPass() { return pass; }
    public Rfid getRfid() { return rfid; }
    public Train getTrain() { return train; }
    public Cloud getCloud() { return cloud; }
    public Bench getBench() { return bench; }
}
