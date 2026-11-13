package dyncg.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compares the aggregate edges of two callgraph.json files.
 *
 * This tool does not run tests or generate call graphs. It only compares
 * call graphs that were generated previously by dyncg version.
 */

public final class CallGraphDiffMain {

    private CallGraphDiffMain() {
    }

    /**
     * An edge identified by stable method signatures rather than the numeric
     * node IDs stored in an individual callgraph.json file.
     */
    private record Edge(String caller, String callee)
            implements Comparable<Edge> {

        @Override
        public int compareTo(Edge other) {
            int callerComparison = caller.compareTo(other.caller);

            if (callerComparison != 0) {
                return callerComparison;
            }

            return callee.compareTo(other.callee);
        }
    }

    public static void main(String[] args) {
        int exitCode = execute(args);

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String... args) {
        if (args.length == 1
                && (args[0].equals("--help") || args[0].equals("-h"))) {
            printUsage();
            return 0;
        }

        if (args.length != 4 || !args[2].equals("--out")) {
            System.err.println(
                    "usage: dyncg-diff <before-callgraph.json> "
                            + "<after-callgraph.json> --out <diff.json>");
            return 2;
        }

        Path beforePath = Path.of(args[0]).toAbsolutePath().normalize();
        Path afterPath = Path.of(args[1]).toAbsolutePath().normalize();
        Path outputPath = Path.of(args[3]).toAbsolutePath().normalize();

        try {
            Map<String, Object> diff = buildDiff(
                    beforePath,
                    afterPath);

            Path parent = outputPath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Json.writePretty(outputPath, diff);

            @SuppressWarnings("unchecked")
            Map<String, Object> stats =
                    (Map<String, Object>) diff.get("stats");

            System.out.println(
                    "[dyncg-diff] wrote "
                            + outputPath
                            + " "
                            + Json.compact(stats));

            return 0;

        } catch (Exception error) {
            System.err.println(
                    "dyncg-diff: " + error.getMessage());
            return 1;
        }
    }

    static Map<String, Object> buildDiff(
            Path beforePath,
            Path afterPath)
            throws IOException {

        JsonNode beforeGraph = readGraph(beforePath);
        JsonNode afterGraph = readGraph(afterPath);

        Set<Edge> beforeEdges = loadAggregateEdges(
                beforeGraph,
                beforePath);

        Set<Edge> afterEdges = loadAggregateEdges(
                afterGraph,
                afterPath);

        TreeSet<Edge> addedEdges = new TreeSet<>(afterEdges);
        addedEdges.removeAll(beforeEdges);

        TreeSet<Edge> removedEdges = new TreeSet<>(beforeEdges);
        removedEdges.removeAll(afterEdges);

        TreeSet<Edge> unchangedEdges = new TreeSet<>(beforeEdges);
        unchangedEdges.retainAll(afterEdges);

        Map<String, Object> output = new LinkedHashMap<>();

        output.put("schema_version", 1);
        output.put(
                "before",
                graphDescription(beforePath, beforeGraph));
        output.put(
                "after",
                graphDescription(afterPath, afterGraph));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("before_edges", beforeEdges.size());
        stats.put("after_edges", afterEdges.size());
        stats.put("added_edges", addedEdges.size());
        stats.put("removed_edges", removedEdges.size());
        stats.put("unchanged_edges", unchangedEdges.size());

        output.put("stats", stats);
        output.put("added_edges", edgeObjects(addedEdges));
        output.put("removed_edges", edgeObjects(removedEdges));

        return output;
    }

    private static JsonNode readGraph(Path path)
            throws IOException {

        if (!Files.isRegularFile(path)) {
            throw new IOException(
                    "call graph does not exist: " + path);
        }

        JsonNode graph = Json.MAPPER.readTree(path.toFile());

        if (graph == null || !graph.isObject()) {
            throw new IOException(
                    "call graph root must be a JSON object: " + path);
        }

        if (!graph.path("nodes").isArray()) {
            throw new IOException(
                    "call graph has no nodes array: " + path);
        }

        if (!graph.path("aggregate_edges").isArray()) {
            throw new IOException(
                    "call graph has no aggregate_edges array: "
                            + path);
        }

        return graph;
    }

    private static Set<Edge> loadAggregateEdges(
            JsonNode graph,
            Path path)
            throws IOException {

        Map<Integer, String> signatures = new LinkedHashMap<>();

        for (JsonNode node : graph.path("nodes")) {
            JsonNode id = node.get("id");
            JsonNode signature = node.get("signature");

            if (id == null
                    || !id.canConvertToInt()
                    || signature == null
                    || !signature.isTextual()) {
                throw new IOException(
                        "invalid node in " + path + ": " + node);
            }

            signatures.put(
                    id.asInt(),
                    signature.asText());
        }

        TreeSet<Edge> edges = new TreeSet<>();

        for (JsonNode row : graph.path("aggregate_edges")) {
            if (!row.isArray() || row.size() < 2) {
                throw new IOException(
                        "invalid aggregate edge in "
                                + path
                                + ": "
                                + row);
            }

            int callerId = row.get(0).asInt();
            int calleeId = row.get(1).asInt();

            String caller = signatures.get(callerId);
            String callee = signatures.get(calleeId);

            if (caller == null || callee == null) {
                throw new IOException(
                        "aggregate edge references an unknown node "
                                + "in "
                                + path
                                + ": "
                                + row);
            }

            edges.add(new Edge(caller, callee));
        }

        return edges;
    }

    private static Map<String, Object> graphDescription(
            Path path,
            JsonNode graph) {

        Map<String, Object> description =
                new LinkedHashMap<>();

        description.put("file", path.toString());
        description.put(
                "label",
                textOrNull(graph.get("version_label")));
        description.put(
                "commit",
                textOrNull(graph.get("commit")));

        return description;
    }

    private static String textOrNull(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }

        return value.asText();
    }

    private static List<Map<String, String>> edgeObjects(
            Set<Edge> edges) {

        List<Map<String, String>> result = new ArrayList<>();

        for (Edge edge : edges) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("caller", edge.caller());
            item.put("callee", edge.callee());
            result.add(item);
        }

        return result;
    }

    private static void printUsage() {
        System.out.println(
                "usage: dyncg-diff <before-callgraph.json> "
                        + "<after-callgraph.json> --out <diff.json>");

        System.out.println();
        System.out.println(
                "Compare two dynamic call graphs and report "
                        + "added and removed aggregate edges.");
    }
}