package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Table-3's 12-digit car layout. The EPCs are the intellisbc2 bench tags, 2026-09-29. */
class CarTagTest {

    /** The operator's real EPC for this tag is 8A8020013A1D; the rest is factory leftover. */
    @Test
    void decodesTheTwelveDigitsAndIgnoresTheLeftover() {
        CarTag expected = new CarTag("02", "0013", "DMC", 1, "DMC-1", "DOWN", null);
        assertThat(CarTag.parse("8A8020013A1D")).isEqualTo(expected);
        assertThat(CarTag.parse("8A8020013A1D00021F0C5233")).isEqualTo(expected);
        assertThat(CarTag.parse("8A8020008A6D00021F0C11D6").positionName()).isEqualTo("DMC-2");
    }

    /** The leftover "0002" is digits too; it must never be reported as the car serial. */
    @Test
    void theSerialIsNeverTakenFromTheLeftover() {
        assertThat(CarTag.parse("8A8020013A1D00021F0C5233").serial()).isNull();
    }

    @Test
    void countsTheLeftoverDigits() {
        assertThat(CarTag.extraDigits("8A8020013A1D")).isZero();
        assertThat(CarTag.extraDigits("8A8020013A1D00021F0C5233")).isEqualTo(12);
    }

    @Test
    void anythingThatBreaksARuleIsNotACarTag() {
        assertThat(CarTag.parse("E2C06892000000021F0C1400")).isNull();   // not 8A8
        assertThat(CarTag.parse("8A8020013A2D")).isNull();               // DMC at position 2
        assertThat(CarTag.parse("8A8020013D1D")).isNull();               // car type D
        assertThat(CarTag.parse("8A8020013A1F")).isNull();               // side F
        assertThat(CarTag.parse("8A80A0013A1D")).isNull();               // line not digits
        assertThat(CarTag.parse("8A8020013A1")).isNull();                // 11 digits
    }

    @Test
    void everyCarTypeSitsOnlyAtItsOwnPositions() {
        assertThat(CarTag.parse("8A8020013B2E").positionName()).isEqualTo("TC-1");
        assertThat(CarTag.parse("8A8020013C4E").positionName()).isEqualTo("MC-2");
        assertThat(CarTag.parse("8A8020013C5E")).isNull();               // MC at a TC slot
    }
}
