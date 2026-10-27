package dyncg.driver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;

/** The call graph of one version, written as callgraph.json (schema version 1) and summary.json. */
final class VersionGraph {
    static final List<String> EDGE_COLUMNS =
            List.of("context", "caller", "callee", "runs_seen", "mean_count", "counts_per_run");
    static final List<String> AGGREGATE_EDGE_COLUMNS =
            List.of("caller", "callee", "runs_seen", "mean_count", "contexts");

    static final class EdgeRow {
        final int context;
        final int caller;
        final int callee;
        final int runsSeen;
        final double meanCount;
        final long[] counts;

        EdgeRow(int context, int caller, int callee, int runsSeen, double meanCount, long[] counts) {
            this.context = context;
            this.caller = caller;
            this.callee = callee;
            this.runsSeen = runsSeen;
            this.meanCount = meanCount;
            this.counts = counts;
        }
    }

    static final class AggregateRow {
        final int caller;
        final int callee;
        final int runsSeen;
        final double meanCount;
        final int contexts;

        AggregateRow(int caller, int callee, int runsSeen, double meanCount, int contexts) {
            this.caller = caller;
            this.callee = callee;
            this.runsSeen = runsSeen;
            this.meanCount = meanCount;
            this.contexts = contexts;
        }
    }

    /** schema_version … transform_failures, in output order. */
    final Map<String, Object> header;
    final List<Map<String, Object>> nodes;
    final List<Map<String, Object>> contexts;
    final List<EdgeRow> edges;
    final List<AggregateRow> aggregateEdges;
    /** Event logs to export; empty when events were not recorded. */
    final List<EventExport.Source> eventSources;

    VersionGraph(Map<String, Object> header, List<Map<String, Object>> nodes, List<Map<String, Object>> contexts,
            List<EdgeRow> edges, List<AggregateRow> aggregateEdges, List<EventExport.Source> eventSources) {
        this.header = header;
        this.nodes = nodes;
        this.contexts = contexts;
        this.edges = edges;
        this.aggregateEdges = aggregateEdges;
        this.eventSources = eventSources;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> stats() {
        return (Map<String, Object>) header.get("stats");
    }

    Path write(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Object> callEvents = null;
        Files.deleteIfExists(outDir.resolve(EventExport.FILE));
        if (!eventSources.isEmpty()) {
            callEvents = EventExport.write(eventSources, outDir);
            stats().put("call_events", callEvents.get("rows"));
        }
        Path path = outDir.resolve("callgraph.json");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
                JsonGenerator g = Json.MAPPER.getFactory().createGenerator(out)) {
            g.writeStartObject();
            for (Map.Entry<String, Object> field : header.entrySet()) {
                g.writeObjectField(field.getKey(), field.getValue());
            }
            g.writeObjectField("nodes", nodes);
            g.writeObjectField("contexts", contexts);
            g.writeObjectField("edge_columns", EDGE_COLUMNS);
            g.writeArrayFieldStart("edges");
            for (EdgeRow e : edges) {
                g.writeStartArray();
                g.writeNumber(e.context);
                g.writeNumber(e.caller);
                g.writeNumber(e.callee);
                g.writeNumber(e.runsSeen);
                g.writeNumber(e.meanCount);
                g.writeArray(e.counts, 0, e.counts.length);
                g.writeEndArray();
            }
            g.writeEndArray();
            g.writeObjectField("aggregate_edge_columns", AGGREGATE_EDGE_COLUMNS);
            g.writeArrayFieldStart("aggregate_edges");
            for (AggregateRow e : aggregateEdges) {
                g.writeStartArray();
                g.writeNumber(e.caller);
                g.writeNumber(e.callee);
                g.writeNumber(e.runsSeen);
                g.writeNumber(e.meanCount);
                g.writeNumber(e.contexts);
                g.writeEndArray();
            }
            g.writeEndArray();
            // Individual invocations are in a separate file; this is its summary (null when not recorded).
            g.writeObjectField("call_events", callEvents);
            g.writeEndObject();
            g.flush();
            out.write('\n');
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("version_label", "commit", "include_prefixes", "stats")) {
            summary.put(key, header.get(key));
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Object run : (List<?>) header.get("runs")) {
            Map<?, ?> full = (Map<?, ?>) run;
            Map<String, Object> brief = new LinkedHashMap<>();
            for (String key : List.of("index", "exit_code", "timed_out", "duration_s", "tests_reported")) {
                brief.put(key, full.get(key));
            }
            runs.add(brief);
        }
        summary.put("runs", runs);
        Json.writePretty(outDir.resolve("summary.json"), summary);
        return path;
    }
}
