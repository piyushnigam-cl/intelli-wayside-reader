package com.intelli.rfid.wayside;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.wayside.cloud.CloudSender;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.trigger.GpioTrigger;
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

    @Bean
    public com.intelli.rfid.wayside.tagtool.TagToolService tagToolService(ReaderService reader,
                                                                          PassService passes,
                                                                          WaysideProperties properties) {
        return new com.intelli.rfid.wayside.tagtool.TagToolService(reader, passes,
                new com.intelli.rfid.wayside.pass.TrainIdDecoder(properties.getTrain()));
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public PassService passService(WaysideProperties properties, ReaderService reader,
                                   WheelSource wheels, CloudSender cloud, ClockSync clockSync,
                                   ObjectMapper mapper) {
        GpioTrigger gpio = properties.getTrigger().getSource() == WaysideProperties.TriggerSource.GPIO
                ? new GpioTrigger(properties.getTrigger().getGpio()) : null;
        return new PassService(properties, reader, wheels, gpio, cloud, clockSync, mapper);
    }
}
