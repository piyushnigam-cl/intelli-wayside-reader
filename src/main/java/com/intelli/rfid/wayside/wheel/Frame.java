package com.intelli.rfid.wayside.wheel;

/**
 * One protocol frame, before COBS: {@code ver:u8 | type:u8 | seq:u16 | len:u16 | payload | crc16:u16},
 * little-endian throughout (design sec.4.1).
 */
public record Frame(int version, int type, int seq, byte[] payload) {

    public static final int VERSION = 1;
    static final int HEADER = 6;
    static final int CRC = 2;
}
