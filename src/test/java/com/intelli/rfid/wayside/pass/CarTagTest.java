package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The five tags on the intellisbc2 bench, 2026-09-29, against Table-3 (docs/Screenshot-Notes.md). */
class CarTagTest {

    @Test
    void decodesTheBenchTags() {
        assertThat(CarTag.parse("8A8020013A1D00021F0C5233", true))
                .isEqualTo(new CarTag("02", "0013", "DMC", 1, "DMC-1", "DOWN", "0002"));
        assertThat(CarTag.parse("8A8020008A6D00021F0C11D6", true))
                .isEqualTo(new CarTag("02", "0008", "DMC", 6, "DMC-2", "DOWN", "0002"));
        assertThat(CarTag.parse("8A8070003A1D00021F0C3E62", true).trainId()).isEqualTo("07-0003");
    }

    /** In Table-3's own row order the serial reads D000: the tags put side first. */
    @Test
    void theBenchTagsDoNotParseInTableOrder() {
        assertThat(CarTag.parse("8A8020013A1D00021F0C5233", false)).isNull();
    }

    @Test
    void anythingThatBreaksARuleIsNotACarTag() {
        assertThat(CarTag.parse("E2C06892000000021F0C1400", true)).isNull();   // not 8A8
        assertThat(CarTag.parse("8A8020013A2D00021F0C5233", true)).isNull();   // DMC at position 2
        assertThat(CarTag.parse("8A8020013D1D00021F0C5233", true)).isNull();   // car type D
        assertThat(CarTag.parse("8A8020013A1F00021F0C5233", true)).isNull();   // side F
        assertThat(CarTag.parse("8A80A0013A1D00021F0C5233", true)).isNull();   // line not digits
        assertThat(CarTag.parse("8A80200", true)).isNull();                    // too short
    }

    @Test
    void everyCarTypeSitsOnlyAtItsOwnPositions() {
        assertThat(CarTag.parse("8A8020013B2E01171F0C5233", true).positionName()).isEqualTo("TC-1");
        assertThat(CarTag.parse("8A8020013C4E01221F0C5233", true).positionName()).isEqualTo("MC-2");
        assertThat(CarTag.parse("8A8020013C5E01221F0C5233", true)).isNull();   // MC at a TC slot
    }
}
