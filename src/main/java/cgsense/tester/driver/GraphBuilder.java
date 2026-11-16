package cgsense.tester.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import cgsense.tester.driver.SurefireReports.TestInvocation;

/*VersionRunner → GraphBuilder.build(label, commit, project, includes, runs, sources)
   └─ for each RunResult:
        ├─ loadRun(run)
        │    ├─ read each dump → AgentDump (broken → used:false)
        │    ├─ jvmRole() → test-jvm / maven / gradle / other
        │    ├─ skip build JVM if a test JVM exists
        │    └─ addDump()  → edges, nodes, contexts by signature   ← section 2
        └─ parse reports → testId → [TestInvocation]
   └─ merge contexts / statuses / edges ...                         ← sections 3–4
*/
/*
 * GraphBuilder: turns the agent dumps + test reports of all runs of ONE version into a VersionGraph.
 *  1. loadRun(): per run, read dumps (skip the build JVM if a test JVM exists) and convert
 *     local ids to signatures (addDump) -> RunGraph.
 *  2. build(): merge all runs:
 *     contexts (0 = unattributed, tests with status across runs, fixtures),
 *     nodes (0 = <root>, sorted signatures + origin),
 *     edges per context with counts per run, aggregate edges without context,
 *     header with run info and stats.
 *  It does not write files; VersionGraph.write() does.
 */


/** Turning agent dumps and test reports into one call graph per version. */
public final class GraphBuilder {
    public static final String VERSION = "0.1.0";
    static final String ROOT = "<root>";
    private static final Map<Integer, String> KIND_NAMES = Map.of(0, "none", 1, "test", 2, "fixture"); //the agent writes 0, 1, or 2 for the kind of a context. We turn that into a string for the graph.
    private static final int ACC_STATIC = 0x0008; //the agent writes the access flags of a method. We check if the ACC_STATIC bit is set to determine if the method is static.
    private static final int ACC_SYNTHETIC = 0x1000; //the agent writes the access flags of a method. We check if the ACC_SYNTHETIC bit is set to determine if the method is synthetic.
    private static final int MAX_REPORTED_NAMES = 20; //when a test method is parameterized, Gradle reports it multiple times with different display names. We only keep the first 20 reported names in the graph to avoid excessive data.
    private static final int MAX_TRANSFORM_FAILURES = 50; //if the agent fails to instrument many classes, we only keep the first 50 failures in the graph to avoid excessive data.

    private GraphBuilder() {
    }

//Each dump records the command line of the JVM that wrote it. This method guesses which kind of JVM it was
    static String jvmRole(String command) {
        if (command.contains("surefire") || command.contains("failsafe") || command.contains("ForkedBooter")) {
            return "test-jvm";
        }
        if (command.contains("Gradle Test Executor")) {
            return "test-jvm";
        }
        if (command.contains("GradleDaemon") || command.contains("org.gradle.launcher")) {
            return "gradle";
        }
        if (command.contains("plexus.classworlds") || command.startsWith("org.apache.maven")) {
            return "maven";
        }
        return "other";
    }

// Convert a method's owner class, name, and descriptor into a unique signature string. This is used to identify methods in the call graph.
    static String signature(String owner, String name, String descriptor) {
        return owner.replace('/', '.') + "#" + name + descriptor;
    }

    /**The identity of one edge: (context, caller, callee).

1. context = the test or fixture that was running, or null meaning "no test was running" (shown later as <unattributed>).
2. caller = the method that made the call.
3. callee = the method that was called.
equals/hashCode let it work as a HashMap key, so the same edge seen twice adds to the same count. Objects.equals is used for context because it can be null.
So calculate → normalize during testA and the same call during testB are two separate edges. That's how the graph knows which test caused each call.*/
    static final class EdgeKey {
        final String context;
        final String caller;
        final String callee;

        EdgeKey(String context, String caller, String callee) {
            this.context = context;
            this.caller = caller;
            this.callee = callee;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof EdgeKey)) {
                return false;
            }
            EdgeKey that = (EdgeKey) other;
            return Objects.equals(context, that.context) && caller.equals(that.caller) && callee.equals(that.callee);
        }

        @Override
        public int hashCode() {
            return Objects.hash(context, caller, callee);
        }
    }

    /** Edges of one test run, keyed by signatures. */
    static final class RunGraph {
        final Map<EdgeKey, Long> edges = new HashMap<>(); // EdgeKey → count of calls
        final Map<String, Map<String, Object>> nodes = new HashMap<>(); // signature → info about the method (class, name, descriptor, static, synthetic)
        /** Context name → kind name. */
        final Map<String, String> contexts = new HashMap<>(); // context name (class#method) → kind name (test, fixture, none)
        final List<Map<String, Object>> dumps = new ArrayList<>(); // info about each dump file (file name, role, used, complete, pid, java_version, classes_instrumented, edges)
        final List<List<String>> transformFailures = new ArrayList<>(); // list of [class, error] for each class that failed to be instrumented by the agent
        long transformFailureCount; // the number of classes that failed to be instrumented by the agent
        long overflowedEdges; // the number of edges that were dropped because the agent's internal buffer was full and could not record all method calls
    }

/*
 * EdgeKey: the identity of one edge = (context, caller, callee).
 *
 * - context: the test or fixture that was running when the call happened,
 *   or null if no test was running (shown as "<unattributed>" in the output).
 * - equals/hashCode let EdgeKey be a HashMap key, so the same edge seen again
 *   adds to its existing count. Objects.equals is used for context because it can be null.
 * - Example: calculate -> normalize during testA and the same call during testB
 *   are two separate edges. That's how the graph knows which test caused each call.
 */

    static RunGraph loadRun(RunResult run) {
        RunGraph graph = new RunGraph();
        List<AgentDump> loaded = new ArrayList<>();
        List<Path> loadedPaths = new ArrayList<>();
        for (Path path : run.dumpFiles) {
            try {
                loaded.add(Json.MAPPER.readValue(path.toFile(), AgentDump.class));
                loadedPaths.add(path);
            } catch (IOException error) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("file", path.getFileName().toString());
                info.put("used", false);
                info.put("reason", "unreadable: " + error.getMessage());
                graph.dumps.add(info);
            }
        }

//We check if any of the loaded dumps are from a test JVM. If so, we will only use the dumps from the test JVM and ignore any dumps from build JVMs (Maven or Gradle). This is because the test JVM is the one that actually runs the tests and produces the relevant call graph data.
        boolean hasTestJvm = loaded.stream().anyMatch(d -> jvmRole(nullToEmpty(d.command)).equals("test-jvm"));
        for (int i = 0; i < loaded.size(); i++) {
            AgentDump dump = loaded.get(i);
            String role = jvmRole(nullToEmpty(dump.command));
            boolean buildJvm = role.equals("maven") || role.equals("gradle");
            boolean used = !buildJvm || !hasTestJvm;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("file", loadedPaths.get(i).getFileName().toString());
            info.put("role", role);
            info.put("used", used);
            info.put("complete", dump.complete);
            info.put("pid", dump.pid);
            info.put("java_version", dump.javaVersion);
            info.put("classes_instrumented", dump.classesInstrumented);
            info.put("edges", dump.edges.length);
            graph.dumps.add(info);
            if (used) {
                addDump(graph, dump, i, loadedPaths.get(i));
            }
        }
        return graph;
    }

// Adds the data from a single dump file to the graph.
/*
 * addDump: adds one dump file to this run's RunGraph, turning local ids into global names.
 *  1. methods  -> signatures[id] = "pkg.Class#name(desc)"; id 0 = "<root>". Node info
 *                 (class, method, descriptor, static, synthetic) is stored once per signature.
 *  2. contexts -> contexts[id] = "pkg.Class#method"; id 0 stays null (= no test running,
 *                 later "<unattributed>"). Kind 0/1/2 -> none/test/fixture.
 *  3. edges    -> EdgeKey(context, caller, callee); merge() adds counts if the key already exists.
 *  4. adds transform failures and overflowed edges to the run totals.
 * Ids are local to one dump, so signatures are what lets dumps and runs be merged safely.
 */

    private static void addDump(RunGraph graph, AgentDump dump, int jvm, Path dumpFile) {
        int maxMethod = 0;
        for (JsonNode row : dump.methods) {
            maxMethod = Math.max(maxMethod, row.get(0).asInt());
        }
        String[] signatures = new String[maxMethod + 1];
        for (JsonNode row : dump.methods) {
            int id = row.get(0).asInt();
            if (id == 0) {
                signatures[0] = ROOT;
                continue;
            }
            String owner = row.get(1).asText();
            String name = row.get(2).asText();
            String descriptor = row.get(3).asText();
            int access = row.get(4).asInt();
            String sig = signature(owner, name, descriptor).intern();
            signatures[id] = sig;
            graph.nodes.computeIfAbsent(sig, s -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("class", owner.replace('/', '.'));
                info.put("method", name);
                info.put("descriptor", descriptor);
                info.put("static", (access & ACC_STATIC) != 0);
                info.put("synthetic", (access & ACC_SYNTHETIC) != 0);
                return info;
            });
        }
        int maxContext = 0;
        for (JsonNode row : dump.contexts) {
            maxContext = Math.max(maxContext, row.get(0).asInt());
        }
        String[] contexts = new String[maxContext + 1];
        for (JsonNode row : dump.contexts) {
            int id = row.get(0).asInt();
            if (id == 0) {
                continue;
            }
            String name = (row.get(1).asText() + "#" + row.get(2).asText()).intern();
            contexts[id] = name;
            int kind = row.get(3).asInt();
            graph.contexts.put(name, KIND_NAMES.getOrDefault(kind, Integer.toString(kind)));
        }
        for (long[] edge : dump.edges) {
            EdgeKey key = new EdgeKey(contexts[(int) edge[0]], signatures[(int) edge[1]], signatures[(int) edge[2]]);
            graph.edges.merge(key, edge[3], Long::sum);
        }
        graph.transformFailureCount += dump.transformFailureCount;
        graph.transformFailures.addAll(dump.transformFailures);
        graph.overflowedEdges += dump.overflowedEdges;
    }

// Returns the final status of a test based on the statuses from multiple runs. If all runs have the same status, that status is returned. If the statuses include "passed", "failed", and "error", it is considered "flaky". Otherwise, it is considered "inconsistent".
    private static String finalStatus(List<String> runStatuses) {
        Set<String> distinct = new HashSet<>(runStatuses);
        if (distinct.size() == 1) {
            return runStatuses.get(0);
        }
        if (Set.of("passed", "failed", "error").containsAll(distinct)) {
            return "flaky";
        }
        return "inconsistent";
    }

    // Builds a version graph from the given runs and their associated data.
    /*
 * Contexts = every situation an edge can happen in. Ids are given in this order:
 *  0          "<unattributed>" (no test running; EdgeKey context null -> 0)
 *  1..        tests: from the reports + names the agent marked as tests (sorted, so ids are stable)
 *  then       fixtures (@BeforeEach etc.) that are not tests; they get no status
 * For each test and each run: report found -> worst status of its cases;
 *   agent saw it but no report -> "not_reported" (crash/timeout); neither -> "not_run".
 * finalStatus(): all runs agree -> that status; differ but all passed/failed/error -> "flaky";
 *   otherwise -> "inconsistent". Only the first failure is kept.
 */

    public static VersionGraph build(String label, String commit, Path projectDir, List<String> includes,
            List<RunResult> runs, ProjectSources sources) throws IOException {
        List<RunGraph> runGraphs = new ArrayList<>();
        List<Map<String, List<TestInvocation>>> runTests = new ArrayList<>();
        for (RunResult run : runs) {
            runGraphs.add(loadRun(run));
            Map<String, List<TestInvocation>> byTest = new HashMap<>();
            // Gradle: dyncg's listener log (it has the methods of parameterized cases); otherwise XML.
            List<TestInvocation> invocations = run.testLog != null && Files.isRegularFile(run.testLog)
                    ? SurefireReports.parseGradleTestLog(run.testLog) : SurefireReports.parse(run.reportDirs);
            for (TestInvocation invocation : invocations) {
                byTest.computeIfAbsent(invocation.testId, k -> new ArrayList<>()).add(invocation);
            }
            runTests.add(byTest);
        }
        int nRuns = runs.size();

        // ---- contexts: tests (from reports or the agent), fixtures, and "unattributed" ----------
        Map<String, String> contextKinds = new HashMap<>();
        runGraphs.forEach(g -> contextKinds.putAll(g.contexts));
        TreeSet<String> testIds = new TreeSet<>();
        runTests.forEach(byTest -> testIds.addAll(byTest.keySet()));
        contextKinds.forEach((name, kind) -> {
            if (kind.equals("test")) {
                testIds.add(name);
            }
        });
        TreeSet<String> fixtureIds = new TreeSet<>();
        contextKinds.forEach((name, kind) -> {
            if (kind.equals("fixture") && !testIds.contains(name)) {
                fixtureIds.add(name);
            }
        });

        List<Map<String, Object>> contexts = new ArrayList<>();
        Map<String, Integer> contextIndex = new HashMap<>();
        contexts.add(entry("id", 0, "name", "<unattributed>", "kind", "none"));
        contextIndex.put(null, 0);
        for (String name : testIds) {
            List<String> runStatuses = new ArrayList<>();
            int invocations = 0;
            Map<String, Object> failure = null;
            TreeSet<String> reportedNames = new TreeSet<>();
            for (int i = 0; i < nRuns; i++) {
                List<TestInvocation> cases = runTests.get(i).getOrDefault(name, List.of());
                if (!cases.isEmpty()) {
                    List<String> statuses = new ArrayList<>();
                    for (TestInvocation c : cases) {
                        statuses.add(c.status);
                        reportedNames.add(c.reportedName);
                    }
                    runStatuses.add(SurefireReports.worst(statuses));
                    invocations = Math.max(invocations, cases.size());
                    for (TestInvocation c : cases) {
                        if (c.failure != null) {
                            failure = c.failure;
                            break;
                        }
                    }
                } else if (runGraphs.get(i).contexts.containsKey(name)) {
                    runStatuses.add("not_reported"); // ran, but no report (JVM crash, timeout)
                } else {
                    runStatuses.add("not_run");
                }
            }
            contextIndex.put(name, contexts.size());
            Map<String, Object> test = entry("id", contexts.size(), "name", name, "kind", "test");
            test.put("status", finalStatus(runStatuses));
            test.put("run_statuses", runStatuses);
            test.put("invocations", invocations);
            test.put("reported_names", new ArrayList<>(reportedNames).subList(0, Math.min(MAX_REPORTED_NAMES, reportedNames.size())));
            test.put("recorded_by_agent", contextKinds.containsKey(name));
            test.put("failure", failure);
            contexts.add(test);
        }
        for (String name : fixtureIds) {
            contextIndex.put(name, contexts.size());
            contexts.add(entry("id", contexts.size(), "name", name, "kind", "fixture"));
        }

/*
 * Nodes: every signature that appears in some edge (sorted, so ids are stable); id 0 = "<root>".
 *   Each node gets class/method/descriptor/static/synthetic + origin (main/test/unknown).
 * Edges: one row per (context, caller, callee) over all runs, sorted by ids:
 *   [context, caller, callee, runs_seen, mean_count, counts_per_run]; a run without the edge counts 0.
 *   runs_seen < number of runs = edge not seen every run (non-deterministic).
 * Aggregate edges: the same without context, summed per (caller, callee):
 *   [caller, callee, runs_seen, mean_count, contexts]; (caller << 32 | callee) packs the pair into one long key.
 * Stats: counts for the header; incomplete_dumps, transform_failures, overflowed_edges and
 *   runs_without_tests should be 0 for a healthy run.
 */

        Map<String, Map<String, Object>> nodeInfo = new HashMap<>();
        runGraphs.forEach(g -> g.nodes.forEach(nodeInfo::putIfAbsent));
        TreeSet<String> called = new TreeSet<>();
        for (RunGraph g : runGraphs) {
            for (EdgeKey key : g.edges.keySet()) {
                called.add(key.caller);
                called.add(key.callee);
            }
        }
        called.remove(ROOT);
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Integer> nodeIndex = new HashMap<>();
        nodes.add(entry("id", 0, "signature", ROOT));
        nodeIndex.put(ROOT, 0);
        for (String sig : called) {
            Map<String, Object> info = nodeInfo.getOrDefault(sig, Map.of());
            nodeIndex.put(sig, nodes.size());
            Map<String, Object> node = entry("id", nodes.size(), "signature", sig);
            node.putAll(info);
            String className = info.containsKey("class") ? (String) info.get("class") : sig.split("#", 2)[0];
            node.put("origin", sources.originOf(className));
            nodes.add(node);
        }

        // ---- edges: all edges seen in any run ------------------------------------------------------
        Set<EdgeKey> keys = new HashSet<>();
        runGraphs.forEach(g -> keys.addAll(g.edges.keySet()));
        List<EdgeKey> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.<EdgeKey>comparingInt(k -> contextIndex.getOrDefault(k.context, 0))
                .thenComparingInt(k -> nodeIndex.get(k.caller))
                .thenComparingInt(k -> nodeIndex.get(k.callee))
                .thenComparing(k -> k.context, Comparator.nullsFirst(Comparator.<String>naturalOrder())));
        List<VersionGraph.EdgeRow> edges = new ArrayList<>(sorted.size());
        TreeMap<Long, long[]> aggregateCounts = new TreeMap<>();
        Map<Long, Set<Integer>> aggregateContexts = new HashMap<>();
        for (EdgeKey key : sorted) {
            long[] counts = new long[nRuns];
            for (int i = 0; i < nRuns; i++) {
                counts[i] = runGraphs.get(i).edges.getOrDefault(key, 0L);
            }
            int contextId = contextIndex.getOrDefault(key.context, 0);
            int caller = nodeIndex.get(key.caller);
            int callee = nodeIndex.get(key.callee);
            edges.add(new VersionGraph.EdgeRow(contextId, caller, callee, runsSeen(counts), mean(counts), counts));
            long pair = ((long) caller << 32) | callee;
            long[] total = aggregateCounts.computeIfAbsent(pair, p -> new long[nRuns]);
            for (int i = 0; i < nRuns; i++) {
                total[i] += counts[i];
            }
            aggregateContexts.computeIfAbsent(pair, p -> new HashSet<>()).add(contextId);
        }
        List<VersionGraph.AggregateRow> aggregate = new ArrayList<>(aggregateCounts.size());
        aggregateCounts.forEach((pair, counts) -> aggregate.add(new VersionGraph.AggregateRow(
                (int) (pair >>> 32), (int) (long) pair, runsSeen(counts), mean(counts),
                aggregateContexts.get(pair).size())));

        // ---- header ----------------------------------------------------------------------------
        TreeMap<String, Integer> statusCounts = new TreeMap<>();
        for (Map<String, Object> c : contexts) {
            if ("test".equals(c.get("kind"))) {
                statusCounts.merge((String) c.get("status"), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> runInfo = new ArrayList<>();
        for (int i = 0; i < nRuns; i++) {
            RunResult run = runs.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", run.index);
            info.put("exit_code", run.exitCode);
            info.put("timed_out", run.timedOut);
            info.put("duration_s", run.durationSeconds);
            info.put("command", run.command);
            info.put("log", run.runDir.resolve("build.log").toString());
            info.put("dumps", runGraphs.get(i).dumps);
            info.put("tests_reported", runTests.get(i).size());
            runInfo.add(info);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", nodes.size() - 1);
        stats.put("edges", edges.size());
        stats.put("aggregate_edges", aggregate.size());
        stats.put("stable_aggregate_edges", aggregate.stream().filter(e -> e.runsSeen == nRuns).count());
        stats.put("tests", statusCounts.values().stream().mapToInt(Integer::intValue).sum());
        stats.put("test_status", statusCounts);
        stats.put("fixtures", fixtureIds.size());
        stats.put("unattributed_edges", edges.stream().filter(e -> e.context == 0).count());
        stats.put("incomplete_dumps", runGraphs.stream().flatMap(g -> g.dumps.stream())
                .filter(d -> Boolean.TRUE.equals(d.get("used")) && !Boolean.TRUE.equals(d.get("complete"))).count());
        stats.put("transform_failures", runGraphs.stream().mapToLong(g -> g.transformFailureCount).sum());
        stats.put("overflowed_edges", runGraphs.stream().mapToLong(g -> g.overflowedEdges).sum());
        stats.put("runs_without_tests", runTests.stream().filter(Map::isEmpty).count());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("schema_version", 1);
        header.put("tool", "dyncg " + GraphBuilder.VERSION);
        header.put("version_label", label);
        header.put("commit", commit);
        header.put("project_dir", projectDir.toString());
        header.put("include_prefixes", includes);
        header.put("runs", runInfo);
        header.put("stats", stats);
        List<List<String>> failures = runGraphs.isEmpty() ? List.of() : runGraphs.get(0).transformFailures;
        header.put("transform_failures", failures.subList(0, Math.min(MAX_TRANSFORM_FAILURES, failures.size())));
        return new VersionGraph(header, nodes, contexts, edges, aggregate);
    }
// Returns the number of runs in which a particular edge was seen.
    private static int runsSeen(long[] counts) {
        int seen = 0;
        for (long c : counts) {
            if (c != 0) {
                seen++;
            }
        }
        return seen;
    }

    // Returns the average number of times an edge was seen across all runs.
    private static double mean(long[] counts) {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        return TestRunner.round((double) sum / counts.length, 2);
    }
// Returns an empty string if the input value is null, otherwise returns the input value. This is used to avoid null values in certain contexts where an empty string is preferred.
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

//
    private static Map<String, Object> entry(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
