package com.intelli.rfid.wayside.wheel;

@FunctionalInterface
public interface WheelListener {
    void onWheelEvent(WheelEvent event);
}
