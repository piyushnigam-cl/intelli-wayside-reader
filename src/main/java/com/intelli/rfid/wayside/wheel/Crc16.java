package com.intelli.rfid.wayside.wheel;

/**
 * CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR. Check value over
 * ASCII "123456789" is 0x29B1, which is what the test pins, so the firmware can be checked against
 * the same vector.
 */
public final class Crc16 {

    private Crc16() {}

    public static int ccittFalse(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }
}
