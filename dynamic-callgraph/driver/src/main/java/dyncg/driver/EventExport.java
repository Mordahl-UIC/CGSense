package dyncg.driver;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Streams the agents' binary event logs of one version into {@code call_events.tsv.gz}, using the
 * node and context ids of callgraph.json. Rows are ordered by run, JVM and sequence number.
 */
final class EventExport {
    static final String FILE = "call_events.tsv.gz";
    static final List<String> COLUMNS = List.of("run", "jvm", "sequence", "thread", "context", "caller", "callee");
    /** Must match the agent's EventLog record layout. */
    private static final int RECORD_BYTES = 28;

    private EventExport() {
    }

    /** One JVM's event log, with dump-local ids mapped to the version graph's ids (-1 = unknown). */
    static final class Source {
        final int run;
        final int jvm;
        final Path file;
        final int[] nodeOf;
        final int[] contextOf;
        final AgentDump dump;

        Source(int run, int jvm, Path file, int[] nodeOf, int[] contextOf, AgentDump dump) {
            this.run = run;
            this.jvm = jvm;
            this.file = file;
            this.nodeOf = nodeOf;
            this.contextOf = contextOf;
            this.dump = dump;
        }
    }

    /** Writes the file and returns the {@code call_events} summary stored in callgraph.json. */
    static Map<String, Object> write(List<Source> sources, Path outDir) throws IOException {
        long rows = 0;
        long unmapped = 0;
        long missing = 0;
        long maxPerJvm = 0;
        int atLimit = 0;
        List<String> errors = new ArrayList<>();
        Path path = outDir.resolve(FILE);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(path), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            out.write(String.join("\t", COLUMNS));
            out.write('\n');
            for (Source source : sources) {
                long[] written = writeSource(source, out);
                rows += written[0];
                unmapped += written[1];
                missing += Math.max(0, source.dump.eventsRecorded - (written[0] + written[1]));
                maxPerJvm = Math.max(maxPerJvm, source.dump.eventsMax);
                if (source.dump.eventsLimitReached) {
                    atLimit++;
                }
                if (source.dump.eventsError != null) {
                    errors.add(source.file.getFileName() + ": " + source.dump.eventsError);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("file", FILE);
        summary.put("columns", COLUMNS);
        summary.put("rows", rows);
        summary.put("max_per_jvm", maxPerJvm);
        summary.put("jvms", sources.size());
        summary.put("jvms_at_limit", atLimit);
        // Events whose ids are not in the last snapshot (logged after it, e.g. in a killed JVM).
        summary.put("unmapped", unmapped);
        // Events counted by the agent but not in the file (buffers lost when a JVM died).
        summary.put("missing", missing);
        summary.put("errors", errors);
        return summary;
    }

    /** Returns {written, unmapped}. */
    private static long[] writeSource(Source source, Writer out) throws IOException {
        if (!Files.isRegularFile(source.file)) {
            return new long[] {0, 0};
        }
        long records = Files.size(source.file) / RECORD_BYTES; // a torn last record is ignored
        if (records > Integer.MAX_VALUE) {
            throw new IOException("too many events in " + source.file);
        }
        int n = (int) records;
        long[] sequence = new long[n];
        long[] thread = new long[n];
        int[] context = new int[n];
        int[] caller = new int[n];
        int[] callee = new int[n];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source.file), 1 << 16))) {
            for (int i = 0; i < n; i++) {
                sequence[i] = in.readLong();
                thread[i] = in.readLong();
                context[i] = in.readInt();
                caller[i] = in.readInt();
                callee[i] = in.readInt();
            }
        }
        // Threads append their buffers independently; restore the global order by sequence number.
        // Sequences are unique and below 2^31 (the agent's limit is at most Integer.MAX_VALUE).
        long[] order = new long[n];
        for (int i = 0; i < n; i++) {
            if (sequence[i] <= 0 || sequence[i] > Integer.MAX_VALUE) {
                throw new IOException("unexpected sequence number " + sequence[i] + " in " + source.file);
            }
            order[i] = (sequence[i] << 32) | i;
        }
        Arrays.sort(order);

        long written = 0;
        long unmapped = 0;
        StringBuilder line = new StringBuilder(64);
        for (long key : order) {
            int i = (int) key;
            int ctx = lookup(source.contextOf, context[i]);
            int from = lookup(source.nodeOf, caller[i]);
            int to = lookup(source.nodeOf, callee[i]);
            if (ctx < 0 || from < 0 || to < 0) {
                unmapped++;
                continue;
            }
            line.setLength(0);
            line.append(source.run).append('\t').append(source.jvm).append('\t').append(sequence[i]).append('\t')
                    .append(thread[i]).append('\t').append(ctx).append('\t').append(from).append('\t').append(to)
                    .append('\n');
            out.append(line);
            written++;
        }
        return new long[] {written, unmapped};
    }

    private static int lookup(int[] table, int id) {
        return id >= 0 && id < table.length ? table[id] : -1;
    }
}
