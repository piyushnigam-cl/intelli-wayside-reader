package com.intelli.rfid.wayside.v1;

import com.intelli.rfid.wayside.wheel.WheelLinkStatus;
import java.time.Instant;

/** {@code GET /api/v1/status}. */
public record WaysideStatus(
        String readerId,
        String readerState,
        boolean reading,
        boolean carrierWanted,
        String carrierMode,
        String pass,
        boolean clockSynced,
        long currentSequence,
        Instant lastPassAt,
        WheelLinkStatus wheelLink,
        Cloud cloud) {

    public record Cloud(boolean enabled, int spoolDepth, int abandoned, String lastOutcome,
                        Instant lastDeliveredAt) {}
}
