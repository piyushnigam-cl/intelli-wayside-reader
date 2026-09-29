package com.intelli.rfid.wayside.bench;

import com.intelli.rfid.wayside.wheel.SimulatedTrain;

/**
 * {@code POST /api/bench/train}. Everything optional: an empty body is a 3-car UP train at 30 km/h
 * carrying two made-up EPCs.
 */
public record TrainRequest(
        SimulatedTrain.Direction direction,
        Double speedKmh,
        Integer cars,
        String frontEpc,
        String rearEpc,
        Long leadMs) {
}
