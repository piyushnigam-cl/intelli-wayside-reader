package com.intelli.rfid.wayside.pass;

import com.intelli.rfid.wayside.wheel.Frame;
import com.intelli.rfid.wayside.wheel.SimulatedTrain;
import com.intelli.rfid.wayside.wheel.WheelEvent;
import com.intelli.rfid.wayside.wheel.WheelMessage;
import com.intelli.rfid.wayside.wheel.WheelMessages;
import java.util.ArrayList;
import java.util.List;

/** Simulated trains turned into the pulses the pass logic sees, with 1 tick = 1 µs = 1000 ns. */
final class Trains {

    static final SimulatedTrain.Geometry GEOMETRY =
            new SimulatedTrain.Geometry(20.0, 10.0, 0.14, 0.1, 5.0);

    private Trains() {}

    static SimulatedTrain.Spec spec(SimulatedTrain.Direction direction, double kmh, int cars) {
        List<Double> axles = SimulatedTrain.standardCars(cars);
        return new SimulatedTrain.Spec(direction, kmh, axles, "E2801190000000000000AA01", 0.3,
                "E2801190000000000000AA02", cars * SimulatedTrain.CAR_M - 0.3);
    }

    /** Every wheel message of the timeline, as the events the SAMD21 link would deliver. */
    static List<WheelEvent> events(SimulatedTrain.Spec spec, long tickZero) {
        SimulatedTrain.Timeline timeline = SimulatedTrain.timeline(spec, GEOMETRY, tickZero);
        List<WheelEvent> events = new ArrayList<>();
        for (SimulatedTrain.TimedMessage m : timeline.messages()) {
            WheelMessage message = WheelMessages.decode(new Frame(1, m.type(), 0, m.payload()));
            if (message instanceof WheelMessage.SystemPulse p) {
                events.add(new WheelEvent.Pulse(p.channel(), p.tickOn(), p.tickOff(),
                        p.tickOn() * 1000, p.tickOff() * 1000, p.peakUa(), p.area()));
            } else if (message instanceof WheelMessage.SystemEdge e) {
                events.add(new WheelEvent.Edge(e.channel(), e.covered(), e.tick(), e.tick() * 1000,
                        e.levelUa()));
            }
        }
        return events;
    }

    static List<WheelEvent.Pulse> pulses(SimulatedTrain.Spec spec) {
        return events(spec, 1_000_000).stream()
                .filter(WheelEvent.Pulse.class::isInstance).map(WheelEvent.Pulse.class::cast)
                .toList();
    }
}
