package com.intelli.rfid.wayside.pass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelli.rfid.wayside.WaysideProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TrainSetLookupTest {

    @Test
    void thePackagedTableHasAll63TrainSets() {
        TrainSetLookup lookup = TrainSetLookup.load("");
        assertThat(lookup.size()).isEqualTo(63);
        assertThat(lookup.resolve(2, 1)).isEqualTo("TS01");
        assertThat(lookup.resolve(7, 1)).isEqualTo("TS05");
        assertThat(lookup.resolve(7, 22)).isEqualTo("TS44");
        assertThat(lookup.resolve(2, 41)).isEqualTo("TS63");
        assertThat(lookup.resolve(9, 1)).isNull();
    }

    /** The operator's own example: "8A80 2003 8A6D" should give TS60. */
    @Test
    void theOperatorsExampleGivesTs60() {
        WaysideProperties.Train config = new WaysideProperties.Train();
        config.setDecode(WaysideProperties.DecodeMode.CAR_TAG);
        TrainIdDecoder decoder = new TrainIdDecoder(config);
        assertThat(decoder.trainSetNumber("8A8020038A6D")).isEqualTo("TS60");
        assertThat(decoder.decode("8A80200388A6D00021F0C5233".substring(0, 12))).isEqualTo("TS60");
        // The bench tags.
        assertThat(decoder.trainSetNumber("8A8020013A1D00021F0C5233")).isEqualTo("TS19");
        assertThat(decoder.trainSetNumber("8A8020008A6D00021F0C11D6")).isEqualTo("TS14");
        assertThat(decoder.trainSetNumber("8A8070003A1D00021F0C3E62")).isEqualTo("TS07");
        assertThat(decoder.trainSetNumber("E2C06892000000021F0C1400")).isNull();
        assertThat(decoder.ignores("E2C06892000000021F0C1400")).isTrue();
        assertThat(decoder.ignores("8A8020013A1D00021F0C5233")).isFalse();
    }

    @Test
    void aTableThatMapsOneTagToTwoTrainsRefusesToLoad() {
        String csv = "line,id2,ts\n02,001,01\n02,001,02\n";
        assertThatThrownBy(() -> TrainSetLookup.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "test"))
                .hasMessageContaining("appears twice");
    }

    @Test
    void aTableThatGivesOneTrainToTwoTagsRefusesToLoad() {
        String csv = "line,id2,ts\n02,001,01\n07,001,01\n";
        assertThatThrownBy(() -> TrainSetLookup.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "test"))
                .hasMessageContaining("given to two tags");
    }
}
