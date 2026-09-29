package com.intelli.rfid.wayside.tagtool;

import java.util.Locale;
import java.util.Map;

/**
 * What an EPC is encoded as, as far as the tag itself can say.
 *
 * <p>In order of authority:
 * <ol>
 *   <li><b>The PC word's toggle bit</b> (0x0100). Set = the EPC is ISO-numbered, not GS1, and the
 *       low byte is its AFI. This is the tag declaring it, not an inference.
 *   <li><b>All zeros</b>: blank.
 *   <li><b>The EPC starts with the chip's own TID prefix</b> (allocation class, maker, model): the
 *       factory default, never commissioned. Many chips ship like that; the bench's E2C06892... tags
 *       do.
 *   <li><b>The first byte is a GS1 EPC header</b> (Tag Data Standard): the scheme is named.
 *   <li>Otherwise <b>private</b>: someone wrote their own format, and only their specification
 *       decodes it. The Charkop bench tags (0x8A...) are this.
 * </ol>
 *
 * <p>Only the GS1 headers 0x2C-0x3F are listed: they are the long-established ones. Newer TDS 2.x
 * schemes use headers outside that range and would be reported as unrecognised, which is honest.
 * A wrong scheme name would not be.
 */
public final class EpcEncoding {

    private static final Map<Integer, String> GS1 = Map.ofEntries(
            Map.entry(0x2C, "GDTI-96"), Map.entry(0x2D, "GSRN-96"), Map.entry(0x2E, "GSRNP-96"),
            Map.entry(0x2F, "USDOD-96"), Map.entry(0x30, "SGTIN-96"), Map.entry(0x31, "SSCC-96"),
            Map.entry(0x32, "SGLN-96"), Map.entry(0x33, "GRAI-96"), Map.entry(0x34, "GIAI-96"),
            Map.entry(0x35, "GID-96"), Map.entry(0x36, "SGTIN-198"), Map.entry(0x37, "GRAI-170"),
            Map.entry(0x38, "GIAI-202"), Map.entry(0x39, "SGLN-195"), Map.entry(0x3A, "GDTI-113"),
            Map.entry(0x3B, "ADI-var"), Map.entry(0x3C, "CPI-96"), Map.entry(0x3D, "CPI-var"),
            Map.entry(0x3E, "GDTI-174"), Map.entry(0x3F, "SGCN-96"));

    private EpcEncoding() {}

    /**
     * @param epc hex, required
     * @param pc  the PC word as 4 hex digits, or null if it could not be read
     * @param tid hex, or null
     */
    public static String describe(String epc, String pc, String tid) {
        return describe(epc, pc, tid, null);
    }

    /** @param trainSetNumber the TrainSetNumber this EPC resolves to (e.g. "TS60"), or null */
    public static String describe(String epc, String pc, String tid, String trainSetNumber) {
        String e = epc == null ? "" : epc.toUpperCase(Locale.ROOT);
        if (pc != null && pc.length() == 4) {
            int word = Integer.parseInt(pc, 16);
            if ((word & 0x0100) != 0) {
                return String.format("ISO (not GS1), AFI 0x%02X", word & 0xFF);
            }
        }
        if (e.isEmpty() || e.chars().allMatch(c -> c == '0')) {
            return "Blank (all zeros)";
        }
        if (tid != null && tid.length() >= 8 && e.startsWith(tid.substring(0, 8).toUpperCase(Locale.ROOT))) {
            return "Factory default (from the chip's TID)";
        }
        if (e.length() < 2) {
            return "Unknown";
        }
        com.intelli.rfid.wayside.pass.CarTag car = com.intelli.rfid.wayside.pass.CarTag.parse(e);
        if (car != null) {
            String text = String.format("%s: line %s, set %s, %s, %s side",
                    trainSetNumber != null ? trainSetNumber : "Train-set tag (TS not in lookup)",
                    car.line(), car.trainSet(), car.positionName(), car.side());
            int extra = com.intelli.rfid.wayside.pass.CarTag.extraDigits(e);
            // A tag written as 12 digits into a longer EPC without shortening its PC length: the
            // extra digits are the factory EPC's leftover. Rewriting the 12 digits here fixes it.
            return extra == 0 ? text
                    : text + ". Plus " + extra + " leftover digits: rewrite the first "
                            + com.intelli.rfid.wayside.pass.CarTag.LENGTH + " to trim";
        }
        int header = Integer.parseInt(e.substring(0, 2), 16);
        String scheme = GS1.get(header);
        if (scheme != null) {
            return scheme + " (GS1)";
        }
        return String.format("Private: header 0x%02X is not a GS1 scheme", header);
    }
}
