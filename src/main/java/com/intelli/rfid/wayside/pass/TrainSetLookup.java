package com.intelli.rfid.wayside.pass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LINE NO + ID-2 → TS NO, from the operator's lookup table (docs/Screenshot-Notes.md, 2026-09-29).
 * The TrainSetNumber is {@code "TS"} + TS NO, e.g. line 02, ID-2 038 → {@code TS60}.
 *
 * <p>The table is data, not code: {@code train-sets.csv} is packaged, and
 * {@code wayside.train.lookup-file} points a site at its own copy. A duplicate (line, ID-2) or a
 * duplicate TS refuses to load: a table that maps one tag to two trains, or two tags to one, would
 * publish wrong identities with nothing to show for it.
 */
public class TrainSetLookup {

    private static final Logger log = LoggerFactory.getLogger(TrainSetLookup.class);
    private static final String PACKAGED = "/train-sets.csv";

    private final Map<String, String> byLineAndId2;

    private TrainSetLookup(Map<String, String> byLineAndId2) {
        this.byLineAndId2 = byLineAndId2;
    }

    /** @param file a site's own CSV, or blank for the packaged table */
    public static TrainSetLookup load(String file) {
        try {
            if (file != null && !file.isBlank()) {
                try (InputStream in = Files.newInputStream(Path.of(file))) {
                    return parse(in, file);
                }
            }
            try (InputStream in = TrainSetLookup.class.getResourceAsStream(PACKAGED)) {
                if (in == null) {
                    throw new IllegalStateException("packaged " + PACKAGED + " is missing");
                }
                return parse(in, "packaged " + PACKAGED);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the train set lookup " + file, e);
        }
    }

    static TrainSetLookup parse(InputStream in, String source) throws IOException {
        Map<String, String> map = new HashMap<>();
        Map<String, String> seenTs = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.toLowerCase(Locale.ROOT).startsWith("line,")) {
                    continue;
                }
                String[] f = t.split(",");
                if (f.length != 3) {
                    throw new IllegalStateException(source + " line " + n + ": expected line,id2,ts: " + t);
                }
                int lineNo = Integer.parseInt(f[0].trim());
                int id2 = Integer.parseInt(f[1].trim());
                String ts = String.format("TS%02d", Integer.parseInt(f[2].trim()));
                String key = key(lineNo, id2);
                if (map.put(key, ts) != null) {
                    throw new IllegalStateException(source + " line " + n + ": line " + lineNo
                            + " ID-2 " + id2 + " appears twice");
                }
                String previous = seenTs.put(ts, key);
                if (previous != null) {
                    throw new IllegalStateException(source + " line " + n + ": " + ts
                            + " is given to two tags (" + previous + " and " + key + ")");
                }
            }
        }
        log.info("Train set lookup: {} train sets from {}", map.size(), source);
        return new TrainSetLookup(Map.copyOf(map));
    }

    /** @return e.g. "TS60", or null when the table has no such train */
    public String resolve(int line, int id2) {
        return byLineAndId2.get(key(line, id2));
    }

    public int size() {
        return byLineAndId2.size();
    }

    private static String key(int line, int id2) {
        return line + "/" + id2;
    }
}
