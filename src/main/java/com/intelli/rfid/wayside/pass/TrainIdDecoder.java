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

    public TrainIdDecoder(WaysideProperties.Train config) {
        this.mode = config.getDecode();
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
}
