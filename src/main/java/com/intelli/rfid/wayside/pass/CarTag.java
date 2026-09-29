package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/**
 * One car tag in the train-set format (Table-3, docs/Screenshot-Notes.md, 2026-09-29):
 *
 * <pre>
 *   8A8 | LINE 2 | TRAIN SET 4 | CAR TYPE 1 | CAR POSITION 1 | CAR SIDE 1 | CAR SERIAL 4 | (8 more)
 * </pre>
 *
 * Sizes are hex digits. <b>CAR SIDE precedes CAR SERIAL on every real tag measured</b>, although
 * Table-3 lists them the other way round: in table order the serial of every bench tag would read
 * {@code D000}. {@code wayside.train.side-before-serial} holds the order, and it defaults to what
 * the tags show. The last 8 hex digits are not in Table-3; on the bench tags they are the last 4
 * bytes of the chip's TID. They are not decoded.
 *
 * <p>A tag that fails any rule is not a car tag. It is reported raw, never guessed: a line or set
 * that is not digits, an unknown car type, a position that contradicts its type (a DMC can only be
 * at 1 or 6), or a side that is not D or E.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CarTag(String line, String trainSet, String carType, int position,
                     String positionName, String side, String serial) {

    public static final String MASK = "8A8";

    /** Line and set, the train's identity: "02-0008". */
    public String trainId() {
        return line + "-" + trainSet;
    }

    /** @return the decoded tag, or null if this EPC is not a valid car tag */
    public static CarTag parse(String epc, boolean sideBeforeSerial) {
        if (epc == null || epc.length() < 16) {
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
        char side = sideBeforeSerial ? e.charAt(11) : e.charAt(15);
        String serial = sideBeforeSerial ? e.substring(12, 16) : e.substring(11, 15);
        if (!digits(line) || !digits(set) || !digits(serial)) {
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
        return new CarTag(line, set, typeName, position - '0', positionName, sideName, serial);
    }

    private static boolean digits(String s) {
        return s.chars().allMatch(Character::isDigit);
    }
}
