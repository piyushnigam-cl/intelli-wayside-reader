package com.intelli.rfid.wayside.pass;

/**
 * What closed the pass, and nothing about how complete it is — {@code stopReason} and
 * {@code train.complete} are independent and neither softens the other.
 */
public enum StopReason {
    /** The wheel sensors cleared normally. */
    CLEARED,
    /** The {@code max-pass-ms} backstop: a stopped train, or a stuck channel. */
    TIMEOUT,
    /** Degraded mode (no wheel link): closed on tag silence. */
    TAG_GAP
}
