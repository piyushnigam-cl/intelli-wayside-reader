package com.intelli.rfid.wayside.wheel;

/**
 * The seam between the pass logic and whatever produces wheel events: the SAMD21 on UART3, the
 * simulator, or nothing. All pass logic is built and tested against the simulator first, because
 * no firmware exists yet.
 */
public interface WheelSource {

    void start(WheelListener listener);

    void stop();

    boolean isUp();

    WheelLinkStatus status();
}
