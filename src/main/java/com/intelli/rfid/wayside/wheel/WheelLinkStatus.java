package com.intelli.rfid.wayside.wheel;

import java.util.List;

/** The wheel link as {@code /api/v1/status} and {@code /api/v1/wheel/levels} report it. */
public record WheelLinkStatus(
        String source,
        String state,
        String reason,
        Long firmwareVersion,
        Integer protocolVersion,
        Integer resetCause,
        long framesReceived,
        long framesLost,
        long crcErrors,
        long malformed,
        long protocolErrors,
        Long lastFrameAgoMs,
        List<Channel> channels) {

    public record Channel(int channel, String head, int system, Integer meanUa, boolean covered,
                          String fault) {}
}
