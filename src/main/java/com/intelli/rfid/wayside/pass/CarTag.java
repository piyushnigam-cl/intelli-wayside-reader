package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/**
 * One car tag in the train-set format (Table-3, docs/Screenshot-Notes.md):
 *
 * <pre>
 *   8A8 | LINE 2 | TRAIN SET 4 | CAR TYPE 1 | CAR POSITION 1 | CAR SIDE 1      = 12 hex, 48 bits
 * </pre>
 *
 * <b>The train data is the first 12 hex digits, and nothing after them is decoded.</b> Found
 * 2026-09-29: the operator gave one tag's real EPC as {@code 8A8020013A1D}, while the reader read
 * {@code 8A8020013A1D00021F0C5233}. The tag's PC word ({@code 3424}) declares 6 words, and the extra
 * 12 digits are the chip's factory EPC ({@code E2C06892 0000 0002 1F0C xxxx}, built from its TID).
 * The programming tool overwrote the first 3 words and never shortened the PC length.
 *
 * <p>Two earlier readings of this class came from that leftover and were wrong: that CAR SIDE
 * precedes CAR SERIAL, and that the serial was {@code 0002}. CAR SIDE is the last field, as Table-3
 * has it. <b>Table-3's CAR SERIAL NO is not on these tags, so {@code serial} is always null</b>,
 * never read from the leftover digits, which are also digits and would pass for one.
 *
 * <p>A tag that fails any rule is not a car tag, and is reported raw rather than guessed: a line or
 * set that is not digits, an unknown car type, a position that contradicts its type (a DMC can only
 * be at 1 or 6), or a side that is not D or E.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CarTag(String line, String trainSet, String carType, int position,
                     String positionName, String side, String serial) {

    public static final String MASK = "8A8";
    /** Hex digits of train data: mask, line, set, type, position, side. */
    public static final int LENGTH = 12;

    /** @return the decoded tag, or null if this EPC is not a valid car tag */
    public static CarTag parse(String epc) {
        if (epc == null || epc.length() < LENGTH) {
            return null;
        }
        String e = epc.toUpperCase(Locale.ROOT);
        if (!e.startsWith(MASK)) {
            return null;
        }
        String line = e.substring(3, 5);
        String set = e.substring(5, 9);
        char type = e.charAt(9);
        char position = e.charAt(10);
        char side = e.charAt(11);
        if (!digits(line) || !digits(set)) {
            return null;
        }
        String typeName = switch (type) {
            case 'A' -> "DMC";
            case 'B' -> "TC";
            case 'C' -> "MC";
            default -> null;
        };
        String positionName = switch (position) {
            case '1' -> "DMC-1";
            case '2' -> "TC-1";
            case '3' -> "MC-1";
            case '4' -> "MC-2";
            case '5' -> "TC-2";
            case '6' -> "DMC-2";
            default -> null;
        };
        if (typeName == null || positionName == null || !positionName.startsWith(typeName + "-")) {
            return null;
        }
        String sideName = switch (side) {
            case 'D' -> "DOWN";
            case 'E' -> "UP";
            default -> null;
        };
        if (sideName == null) {
            return null;
        }
        return new CarTag(line, set, typeName, position - '0', positionName, sideName, null);
    }

    /**
     * How many hex digits this EPC carries beyond the 12 of train data. On a correctly written tag
     * it is 0; the bench tags carry 12, left over from the factory EPC.
     */
    public static int extraDigits(String epc) {
        return epc == null ? 0 : Math.max(0, epc.length() - LENGTH);
    }

    private static boolean digits(String s) {
        return s.chars().allMatch(Character::isDigit);
    }
}
