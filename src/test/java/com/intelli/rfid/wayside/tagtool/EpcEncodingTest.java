package com.intelli.rfid.wayside.tagtool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cases are real tags from the intellisbc2 bench, 2026-09-29, plus the GS1 and ISO shapes. */
class EpcEncodingTest {

    /** A 4-byte TID is the model header every tag of the model shares: it must never target a write. */
    @Test
    void aHeaderOnlyTidIsRefusedBeforeTheReaderIsTouched() {
        TagToolService service = new TagToolService(null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.write("E2C06892", "8A8020013A1D00021F0C5233"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model header");
    }

    @Test
    void theCharkopBenchTagsAreTrainSetTags() {
        assertThat(EpcEncoding.describe("8A8020013A1D00021F0C5233", "3000", "E2C06892200009021F0C5233"))
                .isEqualTo("Train-set tag: line 02, set 0013, DMC-1, DOWN side, serial 0002");
    }

    @Test
    void an8aHeaderThatBreaksTheCarRulesIsStillPrivate() {
        assertThat(EpcEncoding.describe("8A8020013A2D00021F0C5233", "3000", null))
                .isEqualTo("Private: header 0x8A is not a GS1 scheme");
    }

    @Test
    void anEpcThatStartsWithTheChipsTidPrefixIsTheFactoryDefault() {
        assertThat(EpcEncoding.describe("E2C06892000000021F0C1400", "3000", "E2C068922000B5021F0C1400"))
                .isEqualTo("Factory default (from the chip's TID)");
    }

    @Test
    void gs1HeadersAreNamed() {
        assertThat(EpcEncoding.describe("3034257BF400B7800004CB2F", "3000", null))
                .isEqualTo("SGTIN-96 (GS1)");
        assertThat(EpcEncoding.describe("31140000000000000000000A", null, null))
                .isEqualTo("SSCC-96 (GS1)");
    }

    /** The toggle bit is the tag's own declaration and outranks the header byte. */
    @Test
    void thePcToggleBitMeansIsoWithItsAfi() {
        assertThat(EpcEncoding.describe("3034257BF400B7800004CB2F", "31A2", null))
                .isEqualTo("ISO (not GS1), AFI 0xA2");
    }

    @Test
    void zerosAreBlank() {
        assertThat(EpcEncoding.describe("000000000000000000000000", "3000", null))
                .isEqualTo("Blank (all zeros)");
    }
}
