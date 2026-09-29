package com.intelli.rfid.wayside.pass;

/**
 * UP and DOWN only when every axle at both heads agrees. MIXED when a head saw the train go both
 * ways (it reversed over the sensors, ordinary in a depot). UNKNOWN whenever the evidence is
 * missing or split. Never a majority vote: a wrong direction is worse than none.
 */
public enum Direction { UP, DOWN, MIXED, UNKNOWN }
