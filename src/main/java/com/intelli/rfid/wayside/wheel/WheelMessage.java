package com.intelli.rfid.wayside.wheel;

/**
 * The SAMD21's messages as sent, with ticks still raw 32-bit (design sec.4.3).
 *
 * <p><b>The payload layouts below are this side's proposal for protocol v1.</b> No firmware exists
 * yet; when it does, it follows {@link WheelMessages}, and a change on either side is a protocol
 * change, not a fix.
 */
public sealed interface WheelMessage {

    int HELLO = 0x01;
    int HEARTBEAT = 0x02;
    int SYSTEM_EDGE = 0x10;
    int SYSTEM_PULSE = 0x11;
    int CHANNEL_FAULT = 0x20;
    int CAPTURE_CHUNK = 0x30;
    int ACK = 0x7F;

    int GET_INFO = 0x81;
    int SET_DETECT = 0x82;
    int CAPTURE = 0x83;
    int HOST_ALIVE = 0x84;

    /** protoVer u8, fwVer u32 (yyyymmdd), channels u8, sampleRateHz u32, tickHz u32, resetCause u8. */
    record Hello(int protocolVersion, long firmwareVersion, int channels, long sampleRateHz,
                 long tickHz, int resetCause) implements WheelMessage {}

    /** tick u32, uptimeS u32, flags u16, meanUa u16 x4. */
    record Heartbeat(long tick, long uptimeS, int flags, int[] meanUa) implements WheelMessage {}

    /** tick u32, channel u8, edge u8 (1 = covered), levelUa u16. */
    record SystemEdge(long tick, int channel, boolean covered, int levelUa) implements WheelMessage {}

    /** channel u8, tickOn u32, tickOff u32, peakUa u16, area u32. */
    record SystemPulse(int channel, long tickOn, long tickOff, int peakUa, long area)
            implements WheelMessage {}

    /** channel u8, fault u8, levelUa u16. */
    record ChannelFault(int channel, FaultKind fault, int levelUa) implements WheelMessage {}

    /** ackedSeq u16, result u8 (0 = ACK, anything else = NAK with that code). */
    record Ack(int ackedSeq, int result) implements WheelMessage {
        public boolean ok() {
            return result == 0;
        }
    }

    /** A type this version does not handle, CAPTURE_CHUNK included for now. */
    record Unknown(int type, int length) implements WheelMessage {}

    enum FaultKind {
        NONE, OPEN, SHORT, STUCK_COVERED, OUT_OF_BAND, UNKNOWN;

        static FaultKind of(int code) {
            return switch (code) {
                case 0 -> NONE;
                case 1 -> OPEN;
                case 2 -> SHORT;
                case 3 -> STUCK_COVERED;
                case 4 -> OUT_OF_BAND;
                default -> UNKNOWN;
            };
        }

        int code() {
            return switch (this) {
                case NONE -> 0;
                case OPEN -> 1;
                case SHORT -> 2;
                case STUCK_COVERED -> 3;
                case OUT_OF_BAND -> 4;
                case UNKNOWN -> 255;
            };
        }
    }
}
