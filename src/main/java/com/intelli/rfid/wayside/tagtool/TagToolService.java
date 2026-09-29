package com.intelli.rfid.wayside.tagtool;

import com.intelli.rfid.core.MemoryBank;
import com.intelli.rfid.core.ReaderException;
import com.intelli.rfid.core.TagOperations;
import com.intelli.rfid.core.model.TagRead;
import com.uhf.api.cls.Reader.Mtr_Param;
import com.uhf.api.cls.Reader.READER_ERR;
import java.util.function.Supplier;
import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.wayside.pass.PassService;
import com.intelli.rfid.wayside.pass.PassTracker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read every tag in the field with its TID, and write a new EPC onto one tag chosen by its TID.
 * The service behind {@code /tags.html}.
 *
 * <p><b>A write targets a TID, never an EPC.</b> The TID is the chip's factory serial and cannot be
 * changed, while two tags can carry the same EPC. The Select filter on TID is measured to land on
 * exactly the targeted tag with the whole population in the field (production module, 2026-08-29,
 * 8 of 8 writes on the right tag and no other tag changed). Single-tag access never leaked through
 * a filter; only filtered inventory did, which is why nothing here trusts a filtered inventory.
 *
 * <p><b>Refused while a train is passing</b>, and serialised with the carrier controller on the
 * {@link ReaderService} lock. A scan or write stops inventory for a second or two, and a pass that
 * lost that second would lose its tags.
 *
 * <p>Filters are sticky module state, so every one is cleared in a {@code finally}.
 *
 * <p><b>Session 0 for the duration, then the configured session back.</b> MEASURED 2026-09-29 on
 * intellisbc2: in the unit's session 1, three of six TID reads straight after the scan failed with
 * {@code MT_CMD_NO_TAG_ERR}. A tag just inventoried in S1 holds its flag at B for 0.5 to 5 s and
 * ignores the target-A query that single-tag access runs. That is the "silenced session" cause of
 * NO_TAG in CLAUDE.md, now met in S1. S0 has no hold-off while the carrier is up. The swap is set and
 * read back both ways, like applyConfig(), and runs under the reader lock, so no pass sees S0.
 */
public class TagToolService {

    private static final Logger log = LoggerFactory.getLogger(TagToolService.class);

    /** Inventory rounds per scan, each this long: several short rounds catch more of a population. */
    private static final int SCAN_ROUNDS = 4;
    private static final int ROUND_MS = 300;
    private static final int OP_TIMEOUT_MS = 1000;
    /** 96-bit TID (Impinj, NXP): 6 blocks. Fallback for a chip with only the 32-bit class header. */
    private static final int TID_BLOCKS = 6;
    private static final int TID_BLOCKS_SHORT = 2;
    private static final int TID_ATTEMPTS = 3;
    /**
     * A write needs a TID that names ONE chip: at least 8 bytes, i.e. past the 4-byte class, maker
     * and model header, which every tag of that model shares. A header-only TID as a Select filter
     * would write to whichever tag of that model answered first. Found 2026-09-29, when a marginal
     * full read fell back to the header and the page offered a write on it.
     */
    static final int MIN_WRITE_TID_HEX = 16;

    public record ScannedTag(String epc, String tid, String pc, String encoding, int bestRssiDbm,
                             int reads, String error) {}

    public record WriteResult(String tid, String epc, boolean verified, String readBack,
                              String encoding) {}

    private final ReaderService reader;
    private final PassService passes;
    private final com.intelli.rfid.wayside.pass.TrainIdDecoder decoder;

    public TagToolService(ReaderService reader, PassService passes,
                          com.intelli.rfid.wayside.pass.TrainIdDecoder decoder) {
        this.reader = reader;
        this.passes = passes;
        this.decoder = decoder;
    }

    private String encoding(String epc, String pc, String tid) {
        return EpcEncoding.describe(epc, pc, tid, decoder == null ? null : decoder.trainSetNumber(epc));
    }

    public List<ScannedTag> scan() {
        requireNoTrain();
        return reader.whilePaused(() -> inSession0(() -> {
            Map<String, int[]> seen = new LinkedHashMap<>();   // epc -> {bestRssi, reads}
            for (int round = 0; round < SCAN_ROUNDS; round++) {
                for (TagRead read : reader.session().inventoryOnce(ROUND_MS)) {
                    int[] s = seen.computeIfAbsent(read.epc(), k -> new int[] {Integer.MIN_VALUE, 0});
                    s[0] = Math.max(s[0], read.rssi());
                    s[1] += Math.max(1, read.readCount());
                }
            }
            TagOperations ops = reader.tagOperations();
            int antenna = antenna();
            List<ScannedTag> tags = new ArrayList<>();
            for (Map.Entry<String, int[]> e : seen.entrySet()) {
                String tid = null;
                String pc = null;
                String error = null;
                try {
                    ops.withFilter(MemoryBank.EPC, 32, e.getKey(), false);
                    tid = readTid(ops, antenna);
                    pc = readPc(ops, antenna);
                    if (tid.length() < MIN_WRITE_TID_HEX) {
                        error = "Only the TID's model header was read; it does not single out this "
                                + "tag, so writing is disabled. Move the tag closer and read again.";
                    }
                } catch (ReaderException ex) {
                    error = (tid == null ? "TID" : "PC") + " not read: " + ex.getMessage();
                } finally {
                    clearQuietly(ops);
                }
                tags.add(new ScannedTag(e.getKey(), tid, pc, encoding(e.getKey(), pc, tid),
                        e.getValue()[0], e.getValue()[1], error));
            }
            log.info("Tag tool scan: {} tag(s)", tags.size());
            return tags;
        }));
    }

    public WriteResult write(String tid, String newEpc) {
        String t = hex(tid, "tid");
        String epc = hex(newEpc, "epc");
        if (epc.length() % 4 != 0 || epc.isEmpty() || epc.length() > 60) {
            throw new IllegalArgumentException("EPC must be 1 to 15 whole 16-bit words: a multiple of "
                    + "4 hex digits, up to 60. Got " + epc.length() + " digits.");
        }
        if (t.length() < MIN_WRITE_TID_HEX) {
            throw new IllegalArgumentException("TID " + t + " is only " + t.length() / 2 + " bytes: "
                    + "that is the chip model header, shared by every tag of the model, so it cannot "
                    + "target one tag. A full TID (12 bytes on these chips) is needed.");
        }
        requireNoTrain();
        return reader.whilePaused(() -> inSession0(() -> {
            TagOperations ops = reader.tagOperations();
            int antenna = antenna();
            try {
                ops.withFilter(MemoryBank.TID, 0, t, false);
                ops.writeEpc(antenna, epc, null, OP_TIMEOUT_MS);
                // Read back through the same TID filter: EPC bank data starts at block 2.
                String back = ops.readBank(antenna, MemoryBank.EPC, 2, epc.length() / 4, null,
                        OP_TIMEOUT_MS);
                boolean ok = back.equalsIgnoreCase(epc);
                String pc = null;
                try {
                    pc = readPc(ops, antenna);
                } catch (ReaderException ex) {
                    log.debug("PC not read after the write: {}", ex.getMessage());
                }
                log.info("Tag tool wrote EPC {} to TID {}: read back {} ({})", epc, t, back,
                        ok ? "verified" : "MISMATCH");
                return new WriteResult(t, epc, ok, back, encoding(epc, pc, t));
            } finally {
                clearQuietly(ops);
            }
        }));
    }

    /** Runs {@code work} in Gen2 session 0 and puts the configured session back, verified. */
    private <T> T inSession0(Supplier<T> work) {
        int configured = reader.properties().getSession();
        if (configured == 0) {
            return work.get();
        }
        setSession(0);
        try {
            return work.get();
        } finally {
            try {
                setSession(configured);
            } catch (RuntimeException e) {
                log.error("Could not restore Gen2 session {} after a tag-tool operation; the reader "
                        + "is on session 0 until the next reconnect", configured, e);
            }
        }
    }

    private void setSession(int session) {
        var raw = reader.session().raw();
        READER_ERR err = raw.ParamSet(Mtr_Param.MTR_PARAM_POTL_GEN2_SESSION, new int[] {session});
        int[] back = new int[1];
        READER_ERR got = raw.ParamGet(Mtr_Param.MTR_PARAM_POTL_GEN2_SESSION, back);
        if (err != READER_ERR.MT_OK_ERR || got != READER_ERR.MT_OK_ERR || back[0] != session) {
            throw new ReaderException("Could not set Gen2 session " + session + ": set " + err
                    + ", read back " + got + " = " + back[0]);
        }
    }

    /** The PC word: EPC bank block 1. Its toggle bit says GS1 or ISO numbering. */
    private String readPc(TagOperations ops, int antenna) {
        return ops.readBank(antenna, MemoryBank.EPC, 1, 1, null, OP_TIMEOUT_MS);
    }

    /**
     * The full TID, retried: a tag at the edge of the field answers some reads and not others. Only
     * when every full read fails is the 4-byte header tried, and the caller then refuses to write.
     */
    private String readTid(TagOperations ops, int antenna) {
        ReaderException last = null;
        for (int attempt = 0; attempt < TID_ATTEMPTS; attempt++) {
            try {
                return ops.readBank(antenna, MemoryBank.TID, 0, TID_BLOCKS, null, OP_TIMEOUT_MS);
            } catch (ReaderException e) {
                last = e;
            }
        }
        log.debug("Full TID read failed {} times ({}); trying the header only", TID_ATTEMPTS,
                last.getMessage());
        return ops.readBank(antenna, MemoryBank.TID, 0, TID_BLOCKS_SHORT, null, OP_TIMEOUT_MS);
    }

    private void requireNoTrain() {
        if (passes.state() != PassTracker.State.IDLE) {
            throw new ReaderException("A train is passing (pass " + passes.state()
                    + "); tag reads and writes wait until it has been published");
        }
    }

    private int antenna() {
        int[] antennas = reader.session().activeAntennas();
        return antennas.length > 0 ? antennas[0] : 1;
    }

    private static void clearQuietly(TagOperations ops) {
        try {
            ops.clearFilter();
        } catch (RuntimeException e) {
            log.error("Could not clear the tag filter; inventory may now see only one tag", e);
        }
    }

    private static String hex(String value, String what) {
        String v = value == null ? "" : value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (v.isEmpty() || !v.matches("[0-9A-F]+") || v.length() % 2 != 0) {
            throw new IllegalArgumentException(what + " must be hex, whole bytes: '" + value + "'");
        }
        return v;
    }
}
