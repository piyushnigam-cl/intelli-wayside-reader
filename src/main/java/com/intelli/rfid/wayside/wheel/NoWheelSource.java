package com.intelli.rfid.wayside.wheel;

import java.util.List;

/** {@code wayside.wheel.source: NONE}: every pass is RFID-only. */
public class NoWheelSource implements WheelSource {

    private final String reason;

    public NoWheelSource(String reason) {
        this.reason = reason;
    }

    @Override
    public void start(WheelListener listener) {}

    @Override
    public void stop() {}

    @Override
    public boolean isUp() {
        return false;
    }

    @Override
    public WheelLinkStatus status() {
        return new WheelLinkStatus("NONE", "DOWN", reason, null, null, null, 0, 0, 0, 0, 0, null,
                List.of());
    }
}
