package com.intelli.rfid.wayside.pass;

import com.intelli.rfid.wayside.WaysideProperties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EPC → train id. The encoding is a site question (design sec.10 q4), so until it is answered this
 * runs {@code RAW}: no id, every EPC reported as read.
 *
 * <p><b>Decoding never loses a tag.</b> An EPC the rule does not match is still reported, raw,
 * with {@code decoded: false} — the rule carried over unchanged from every earlier wayside draft.
 */
public class TrainIdDecoder {

    private final WaysideProperties.DecodeMode mode;
    private final Pattern pattern;
    private final boolean sideBeforeSerial;
    private final TrainSetLookup lookup;

    public TrainIdDecoder(WaysideProperties.Train config) {
        this.mode = config.getDecode();
        this.sideBeforeSerial = config.isSideBeforeSerial();
        this.lookup = mode == WaysideProperties.DecodeMode.CAR_TAG
                ? TrainSetLookup.load(config.getLookupFile()) : null;
        if (mode == WaysideProperties.DecodeMode.REGEX) {
            if (config.getPattern() == null || config.getPattern().isBlank()) {
                throw new IllegalStateException(
                        "wayside.train.decode is REGEX but wayside.train.pattern is empty");
            }
            this.pattern = Pattern.compile(config.getPattern());
            if (!config.getPattern().contains("(?<id>")) {
                throw new IllegalStateException(
                        "wayside.train.pattern must have a named group (?<id>...)");
            }
        } else {
            this.pattern = null;
        }
    }

    /** @return the train id, or null when the EPC does not decode (always null in RAW) */
    public String decode(String epc) {
        if (mode == WaysideProperties.DecodeMode.CAR_TAG) {
            return trainSetNumber(epc);
        }
        if (pattern == null || epc == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(epc.toUpperCase());
        if (!matcher.matches()) {
            return null;
        }
        String id = matcher.group("id");
        return id == null || id.isBlank() ? null : id;
    }

    /** The car this tag names, in CAR_TAG mode; null in every other mode or when it does not parse. */
    public CarTag car(String epc) {
        return mode == WaysideProperties.DecodeMode.CAR_TAG ? CarTag.parse(epc, sideBeforeSerial) : null;
    }

    /**
     * CAR_TAG: the TrainSetNumber (operator's rule, 2026-09-29). {@code 8A8}, then line (2 digits),
     * then the 4-digit train set field whose value is ID-2; the lookup gives TS NO. Example:
     * {@code 8A8 02 0038 ...} → line 02, ID-2 038 → {@code TS60}. Needs only those fields, so a tag
     * whose later fields are unusual still identifies its train. Null when any of them is missing
     * or the table has no such train.
     */
    public String trainSetNumber(String epc) {
        if (lookup == null || epc == null || epc.length() < 9) {
            return null;
        }
        String e = epc.toUpperCase(java.util.Locale.ROOT);
        if (!e.startsWith(CarTag.MASK)) {
            return null;
        }
        String line = e.substring(3, 5);
        String set = e.substring(5, 9);
        if (!line.chars().allMatch(Character::isDigit) || !set.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return lookup.resolve(Integer.parseInt(line), Integer.parseInt(set));
    }

    /** CAR_TAG: tags whose EPC does not start 8A8 are not train tags and are left out (operator). */
    public boolean ignores(String epc) {
        return mode == WaysideProperties.DecodeMode.CAR_TAG
                && (epc == null || !epc.toUpperCase(java.util.Locale.ROOT).startsWith(CarTag.MASK));
    }
}
