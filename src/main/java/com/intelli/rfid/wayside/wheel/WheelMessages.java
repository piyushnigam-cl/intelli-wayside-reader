package com.intelli.rfid.wayside.wheel;

import com.intelli.rfid.wayside.wheel.WheelMessage.Ack;
import com.intelli.rfid.wayside.wheel.WheelMessage.ChannelFault;
import com.intelli.rfid.wayside.wheel.WheelMessage.FaultKind;
import com.intelli.rfid.wayside.wheel.WheelMessage.Heartbeat;
import com.intelli.rfid.wayside.wheel.WheelMessage.Hello;
import com.intelli.rfid.wayside.wheel.WheelMessage.SystemEdge;
import com.intelli.rfid.wayside.wheel.WheelMessage.SystemPulse;
import com.intelli.rfid.wayside.wheel.WheelMessage.Unknown;

/**
 * Payload layouts for protocol v1, both directions. The encoders for SAMD21 messages exist so the
 * simulator and the tests speak exactly the bytes the firmware must.
 */
public final class WheelMessages {

    public static final int CHANNELS = 4;

    private WheelMessages() {}

    /**
     * @throws IllegalArgumentException when the payload is shorter than its type requires. A frame
     *         that passed CRC and still has the wrong length is a protocol mismatch, not line noise.
     */
    public static WheelMessage decode(Frame frame) {
        byte[] p = frame.payload();
        return switch (frame.type()) {
            case WheelMessage.HELLO -> {
                need(p, 15, "HELLO");
                yield new Hello(Bytes.u8(p, 0), Bytes.u32(p, 1), Bytes.u8(p, 5), Bytes.u32(p, 6),
                        Bytes.u32(p, 10), Bytes.u8(p, 14));
            }
            case WheelMessage.HEARTBEAT -> {
                need(p, 10 + 2 * CHANNELS, "HEARTBEAT");
                int[] mean = new int[CHANNELS];
                for (int i = 0; i < CHANNELS; i++) {
                    mean[i] = Bytes.u16(p, 10 + 2 * i);
                }
                yield new Heartbeat(Bytes.u32(p, 0), Bytes.u32(p, 4), Bytes.u16(p, 8), mean);
            }
            case WheelMessage.SYSTEM_EDGE -> {
                need(p, 8, "SYSTEM_EDGE");
                yield new SystemEdge(Bytes.u32(p, 0), Bytes.u8(p, 4), Bytes.u8(p, 5) == 1,
                        Bytes.u16(p, 6));
            }
            case WheelMessage.SYSTEM_PULSE -> {
                need(p, 15, "SYSTEM_PULSE");
                yield new SystemPulse(Bytes.u8(p, 0), Bytes.u32(p, 1), Bytes.u32(p, 5),
                        Bytes.u16(p, 9), Bytes.u32(p, 11));
            }
            case WheelMessage.CHANNEL_FAULT -> {
                need(p, 4, "CHANNEL_FAULT");
                yield new ChannelFault(Bytes.u8(p, 0), FaultKind.of(Bytes.u8(p, 1)), Bytes.u16(p, 2));
            }
            case WheelMessage.ACK -> {
                need(p, 3, "ACK");
                yield new Ack(Bytes.u16(p, 0), Bytes.u8(p, 2));
            }
            default -> new Unknown(frame.type(), p.length);
        };
    }

    private static void need(byte[] p, int length, String what) {
        if (p.length < length) {
            throw new IllegalArgumentException(what + " payload is " + p.length
                    + " bytes, protocol v1 needs " + length);
        }
    }

    // ---------------------------------------------------------------- SAMD21 -> CM4 (simulator, tests)

    public static byte[] hello(Hello m) {
        byte[] p = new byte[15];
        p[0] = (byte) m.protocolVersion();
        Bytes.putU32(p, 1, m.firmwareVersion());
        p[5] = (byte) m.channels();
        Bytes.putU32(p, 6, m.sampleRateHz());
        Bytes.putU32(p, 10, m.tickHz());
        p[14] = (byte) m.resetCause();
        return p;
    }

    public static byte[] heartbeat(Heartbeat m) {
        byte[] p = new byte[10 + 2 * CHANNELS];
        Bytes.putU32(p, 0, m.tick());
        Bytes.putU32(p, 4, m.uptimeS());
        Bytes.putU16(p, 8, m.flags());
        for (int i = 0; i < CHANNELS; i++) {
            Bytes.putU16(p, 10 + 2 * i, m.meanUa()[i]);
        }
        return p;
    }

    public static byte[] edge(SystemEdge m) {
        byte[] p = new byte[8];
        Bytes.putU32(p, 0, m.tick());
        p[4] = (byte) m.channel();
        p[5] = (byte) (m.covered() ? 1 : 0);
        Bytes.putU16(p, 6, m.levelUa());
        return p;
    }

    public static byte[] pulse(SystemPulse m) {
        byte[] p = new byte[15];
        p[0] = (byte) m.channel();
        Bytes.putU32(p, 1, m.tickOn());
        Bytes.putU32(p, 5, m.tickOff());
        Bytes.putU16(p, 9, m.peakUa());
        Bytes.putU32(p, 11, m.area());
        return p;
    }

    public static byte[] fault(ChannelFault m) {
        byte[] p = new byte[4];
        p[0] = (byte) m.channel();
        p[1] = (byte) m.fault().code();
        Bytes.putU16(p, 2, m.levelUa());
        return p;
    }

    public static byte[] ack(Ack m) {
        byte[] p = new byte[3];
        Bytes.putU16(p, 0, m.ackedSeq());
        p[2] = (byte) m.result();
        return p;
    }

    // ---------------------------------------------------------------- CM4 -> SAMD21

    public static byte[] getInfo() {
        return new byte[0];
    }

    /** Per channel: coveredUa u16, uncoveredUa u16, minPulseUs u32. The same values on all four. */
    public static byte[] setDetect(int coveredUa, int uncoveredUa, long minPulseUs) {
        byte[] p = new byte[8 * CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            Bytes.putU16(p, 8 * i, coveredUa);
            Bytes.putU16(p, 8 * i + 2, uncoveredUa);
            Bytes.putU32(p, 8 * i + 4, minPulseUs);
        }
        return p;
    }

    /** Reserved for the supervisor watchdog; v1 firmware ignores it (design sec.4.6). */
    public static byte[] hostAlive(long uptimeS) {
        byte[] p = new byte[4];
        Bytes.putU32(p, 0, uptimeS);
        return p;
    }
}
