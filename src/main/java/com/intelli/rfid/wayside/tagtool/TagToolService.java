package com.intelli.rfid.wayside.tagtool;

import com.intelli.rfid.core.MemoryBank;
import com.intelli.rfid.core.ReaderException;
import com.intelli.rfid.core.TagOperations;
import com.intelli.rfid.core.model.TagRead;
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

    public record ScannedTag(String epc, String tid, int bestRssiDbm, int reads, String error) {}

    public record WriteResult(String tid, String epc, boolean verified, String readBack) {}

    private final ReaderService reader;
    private final PassService passes;

    public TagToolService(ReaderService reader, PassService passes) {
        this.reader = reader;
        this.passes = passes;
    }

    public List<ScannedTag> scan() {
        requireNoTrain();
        return reader.whilePaused(() -> {
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
                String error = null;
                try {
                    ops.withFilter(MemoryBank.EPC, 32, e.getKey(), false);
                    tid = readTid(ops, antenna);
                } catch (ReaderException ex) {
                    error = "TID not read: " + ex.getMessage();
                } finally {
                    clearQuietly(ops);
                }
                tags.add(new ScannedTag(e.getKey(), tid, e.getValue()[0], e.getValue()[1], error));
            }
            log.info("Tag tool scan: {} tag(s)", tags.size());
            return tags;
        });
    }

    public WriteResult write(String tid, String newEpc) {
        String t = hex(tid, "tid");
        String epc = hex(newEpc, "epc");
        if (epc.length() % 4 != 0 || epc.isEmpty() || epc.length() > 60) {
            throw new IllegalArgumentException("EPC must be 1 to 15 whole 16-bit words: a multiple of "
                    + "4 hex digits, up to 60. Got " + epc.length() + " digits.");
        }
        requireNoTrain();
        return reader.whilePaused(() -> {
            TagOperations ops = reader.tagOperations();
            int antenna = antenna();
            try {
                ops.withFilter(MemoryBank.TID, 0, t, false);
                ops.writeEpc(antenna, epc, null, OP_TIMEOUT_MS);
                // Read back through the same TID filter: EPC bank data starts at block 2.
                String back = ops.readBank(antenna, MemoryBank.EPC, 2, epc.length() / 4, null,
                        OP_TIMEOUT_MS);
                boolean ok = back.equalsIgnoreCase(epc);
                log.info("Tag tool wrote EPC {} to TID {}: read back {} ({})", epc, t, back,
                        ok ? "verified" : "MISMATCH");
                return new WriteResult(t, epc, ok, back);
            } finally {
                clearQuietly(ops);
            }
        });
    }

    private String readTid(TagOperations ops, int antenna) {
        try {
            return ops.readBank(antenna, MemoryBank.TID, 0, TID_BLOCKS, null, OP_TIMEOUT_MS);
        } catch (ReaderException full) {
            return ops.readBank(antenna, MemoryBank.TID, 0, TID_BLOCKS_SHORT, null, OP_TIMEOUT_MS);
        }
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
