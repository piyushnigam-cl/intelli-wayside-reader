package com.intelli.rfid.wayside.pass;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    public record Train(String id, boolean decoded, int tagsExpected, int tagsFound,
                        boolean complete) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tag(String epc, String tid, boolean decoded, String trainId, Instant firstSeen,
                      Instant lastSeen, int reads, double bestRssiDbm) {}

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
