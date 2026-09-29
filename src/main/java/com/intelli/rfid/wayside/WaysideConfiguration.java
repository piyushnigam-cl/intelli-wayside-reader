package com.intelli.rfid.wayside;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.wayside.cloud.CloudSender;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.wheel.NoWheelSource;
import com.intelli.rfid.wayside.wheel.SerialWheelSource;
import com.intelli.rfid.wayside.wheel.SimulatedWheelSource;
import com.intelli.rfid.wayside.wheel.WheelSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WaysideConfiguration {

    /** Not started here: {@link PassService#start()} starts it with its listener attached. */
    @Bean
    public WheelSource wheelSource(WaysideProperties properties) {
        WaysideProperties.Wheel wheel = properties.getWheel();
        return switch (wheel.getSource()) {
            case SERIAL -> new SerialWheelSource(wheel);
            case SIMULATED -> new SimulatedWheelSource(wheel.getLinkTimeoutMs());
            case NONE -> new NoWheelSource("wayside.wheel.source is NONE: RFID-only by configuration");
        };
    }

    @Bean(destroyMethod = "shutdown")
    public CloudSender cloudSender(WaysideProperties properties, ObjectMapper mapper) {
        return new CloudSender(properties.getCloud(), mapper);
    }

    @Bean
    public ClockSync clockSync() {
        return new ClockSync();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public PassService passService(WaysideProperties properties, ReaderService reader,
                                   WheelSource wheels, CloudSender cloud, ClockSync clockSync,
                                   ObjectMapper mapper) {
        return new PassService(properties, reader, wheels, cloud, clockSync, mapper);
    }
}
