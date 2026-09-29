package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * One train, one result: the cloud payload of design sec.6, and the local API's shape.
 *
 * <p>Nulls are <b>serialised</b>, not dropped ({@code JsonInclude.ALWAYS}, overriding the app's
 * non_null default). A missing {@code direction} and a {@code direction: null} say different things
 * to a consumer: the second is this reader declining to guess.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PassResult(
        int schemaVersion,
        String id,
        String readerId,
        long sequence,
        Instant startedAt,
        Instant endedAt,
        // The receiving system's own names, capitalised as given (operator, 2026-09-29), and
        // placed immediately above stopReason as asked.
        @JsonProperty("Site") String site,
        @JsonProperty("ToolId") String toolId,
        @JsonProperty("TrainType") String trainType,
        StopReason stopReason,
        boolean clockSynced,
        Train train,
        List<Tag> tags,
        Wheels wheels,
        Reader reader) {

    /**
     * The format version, first in every pass so a consumer can check it before reading anything
     * else. Bump it on any change a consumer could notice (a field removed, renamed or re-typed, or an
     * enum value changing meaning), together with docs/Wayside-Cloud-JSON.md. Adding a field or an
     * enum value does not bump it: the document tells consumers to tolerate both. Records spooled
     * before it existed read back as 0.
     */
    public static final int SCHEMA_VERSION = 1;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    /**
     * @param id          the TrainSetNumber, e.g. "TS60" (CAR_TAG mode), or null
     * @param ignoredTags tags read but left out of {@code tags} because they are not train tags
     *                    (CAR_TAG: EPC not starting 8A8). Counted so the omission is never silent
     */
    public record Train(String id, String line, boolean decoded, int tagsExpected, int tagsFound,
                        int ignoredTags, boolean complete) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tag(String epc, String tid, boolean decoded, String trainId, CarTag car,
                      Instant firstSeen, Instant lastSeen, int reads, double bestRssiDbm) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Wheels(String link, Direction direction, AxleCount axleCount, Speed speedKmh,
                         List<AxleBuilder.Axle> axles, List<String> faults) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AxleCount(int headA, int headB, boolean consistent) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Speed(Double min, Double mean, Double max) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reader(String app, String moduleFw, String region) {}
}
