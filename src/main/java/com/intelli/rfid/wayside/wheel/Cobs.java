package com.intelli.rfid.wayside.wheel;

import java.io.ByteArrayOutputStream;

/**
 * Consistent Overhead Byte Stuffing. The encoded form contains no zero byte, so {@code 0x00} can
 * delimit frames on the wire, and a receiver that lands mid-frame resynchronises at the next zero
 * and loses one message rather than the stream.
 */
public final class Cobs {

    private Cobs() {}

    /** Encodes without the trailing delimiter. */
    public static byte[] encode(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + data.length / 254 + 2);
        int codeIndex = 0;
        byte[] block = new byte[255];
        int blockLen = 1;
        for (byte b : data) {
            if (b == 0) {
                block[0] = (byte) blockLen;
                out.write(block, 0, blockLen);
                blockLen = 1;
            } else {
                block[blockLen++] = b;
                if (blockLen == 255) {
                    block[0] = (byte) 255;
                    out.write(block, 0, 255);
                    blockLen = 1;
                }
            }
        }
        block[0] = (byte) blockLen;
        out.write(block, 0, blockLen);
        return out.toByteArray();
    }

    /**
     * Decodes one frame (delimiter already stripped).
     *
     * @throws IllegalArgumentException on a malformed frame: a zero byte inside it, or a code that
     *         runs past the end
     */
    public static byte[] decode(byte[] data, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int i = 0;
        while (i < length) {
            int code = data[i] & 0xFF;
            if (code == 0) {
                throw new IllegalArgumentException("zero byte inside a COBS frame at " + i);
            }
            if (i + code > length) {
                throw new IllegalArgumentException("COBS code " + code + " at " + i
                        + " runs past the frame end " + length);
            }
            for (int j = 1; j < code; j++) {
                byte b = data[i + j];
                if (b == 0) {
                    throw new IllegalArgumentException("zero byte inside a COBS frame at " + (i + j));
                }
                out.write(b);
            }
            i += code;
            if (code < 255 && i < length) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }
}
