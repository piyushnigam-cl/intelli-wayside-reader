package com.intelli.rfid.wayside.wheel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Frames to and from the wire: COBS-encoded, {@code 0x00}-delimited, CRC-16/CCITT-FALSE.
 *
 * <p>Decoding is a stream accumulator. A frame that fails COBS, length or CRC is <b>counted and
 * dropped, never parsed</b>, and the decoder carries on at the next delimiter. Nothing here tries
 * to recover a lost frame; {@code seq} gaps are counted by the caller.
 */
public class FrameCodec {

    /** Longest encoded frame accepted. Anything longer is noise or a lost delimiter. */
    static final int MAX_ENCODED = 1024;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean overflowed;
    private long crcErrors;
    private long malformed;

    public static byte[] encode(Frame frame) {
        byte[] payload = frame.payload() == null ? new byte[0] : frame.payload();
        byte[] raw = new byte[Frame.HEADER + payload.length + Frame.CRC];
        raw[0] = (byte) frame.version();
        raw[1] = (byte) frame.type();
        Bytes.putU16(raw, 2, frame.seq());
        Bytes.putU16(raw, 4, payload.length);
        System.arraycopy(payload, 0, raw, Frame.HEADER, payload.length);
        int crc = Crc16.ccittFalse(raw, 0, Frame.HEADER + payload.length);
        Bytes.putU16(raw, Frame.HEADER + payload.length, crc);
        byte[] stuffed = Cobs.encode(raw);
        byte[] wire = new byte[stuffed.length + 1];
        System.arraycopy(stuffed, 0, wire, 0, stuffed.length);
        return wire;
    }

    /** Feeds received bytes; returns every complete, valid frame they finished. */
    public List<Frame> feed(byte[] data, int offset, int length) {
        List<Frame> frames = new ArrayList<>();
        for (int i = offset; i < offset + length; i++) {
            byte b = data[i];
            if (b != 0) {
                if (pending.size() >= MAX_ENCODED) {
                    overflowed = true;
                } else {
                    pending.write(b);
                }
                continue;
            }
            if (overflowed) {
                malformed++;
            } else if (pending.size() > 0) {
                Frame frame = parse(pending.toByteArray());
                if (frame != null) {
                    frames.add(frame);
                }
            }
            pending.reset();
            overflowed = false;
        }
        return frames;
    }

    private Frame parse(byte[] stuffed) {
        byte[] raw;
        try {
            raw = Cobs.decode(stuffed, stuffed.length);
        } catch (IllegalArgumentException e) {
            malformed++;
            return null;
        }
        if (raw.length < Frame.HEADER + Frame.CRC) {
            malformed++;
            return null;
        }
        int len = Bytes.u16(raw, 4);
        if (raw.length != Frame.HEADER + len + Frame.CRC) {
            malformed++;
            return null;
        }
        int expected = Bytes.u16(raw, Frame.HEADER + len);
        if (Crc16.ccittFalse(raw, 0, Frame.HEADER + len) != expected) {
            crcErrors++;
            return null;
        }
        byte[] payload = new byte[len];
        System.arraycopy(raw, Frame.HEADER, payload, 0, len);
        return new Frame(raw[0] & 0xFF, raw[1] & 0xFF, Bytes.u16(raw, 2), payload);
    }

    public long crcErrors() {
        return crcErrors;
    }

    public long malformed() {
        return malformed;
    }
}
