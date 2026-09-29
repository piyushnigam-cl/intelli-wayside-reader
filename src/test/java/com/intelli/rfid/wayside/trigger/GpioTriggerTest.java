package com.intelli.rfid.wayside.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelli.rfid.wayside.WaysideProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GpioTriggerTest {

    /** The v2 command line, with -l: without it the trigger fires when the signal is RELEASED. */
    @Test
    void watchesBothLinesActiveLowAndLineBuffered() {
        WaysideProperties.Gpio config = new WaysideProperties.Gpio();
        config.setLibgpiodMajor(2);
        assertThat(new GpioTrigger(config).commandLine()).containsExactly(
                "stdbuf", "-oL", "gpiomon", "-l", "--edges=rising", "--format=%o %S",
                "-c", "gpiochip0", "23", "24");
    }

    @Test
    void routesEachLineToItsHandlerAndDebounces() throws Exception {
        WaysideProperties.Gpio config = new WaysideProperties.Gpio();
        GpioTrigger trigger = new GpioTrigger(config);
        List<String> seen = new ArrayList<>();
        java.lang.reflect.Field start = GpioTrigger.class.getDeclaredField("onIn1");
        java.lang.reflect.Field end = GpioTrigger.class.getDeclaredField("onIn2");
        start.setAccessible(true);
        end.setAccessible(true);
        start.set(trigger, (java.util.function.LongConsumer) n -> seen.add("in1@" + n));
        end.set(trigger, (java.util.function.LongConsumer) n -> seen.add("in2@" + n));

        assertThat(trigger.handle("23 1000.000000100")).isTrue();
        assertThat(trigger.handle("23 1000.010000100")).isTrue();   // 10 ms later: bounce
        assertThat(trigger.handle("24 1007.500000000")).isTrue();
        assertThat(trigger.handle("23 %S")).isFalse();                // a v1/v2 format mix-up
        assertThat(seen).containsExactly("in1@1000000000100", "in2@1007500000000");
    }
}
