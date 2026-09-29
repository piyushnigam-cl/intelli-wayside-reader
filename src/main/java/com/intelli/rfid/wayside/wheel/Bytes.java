package com.intelli.rfid.wayside.wheel;

/** Little-endian field access, as the protocol specifies. */
final class Bytes {

    private Bytes() {}

    static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16
                | (b[at + 3] & 0xFFL) << 24;
    }

    static void putU16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
    }

    static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }
}
