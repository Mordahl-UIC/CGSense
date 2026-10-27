package dyncg.driver;

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

import dyncg.driver.SurefireReports.TestInvocation;

/** Turning agent dumps and test reports into one call graph per version. */
final class GraphBuilder {
    static final String ROOT = "<root>";
    private static final Map<Integer, String> KIND_NAMES = Map.of(0, "none", 1, "test", 2, "fixture");
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int MAX_REPORTED_NAMES = 20;
    private static final int MAX_TRANSFORM_FAILURES = 50;

    private GraphBuilder() {
    }

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

    static String signature(String owner, String name, String descriptor) {
        return owner.replace('/', '.') + "#" + name + descriptor;
    }

    /** (context, caller, callee); context is null for "no test or fixture". */
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
        final Map<EdgeKey, Long> edges = new HashMap<>();
        final Map<String, Map<String, Object>> nodes = new HashMap<>();
        /** Context name → kind name. */
        final Map<String, String> contexts = new HashMap<>();
        final List<EventLogRef> eventLogs = new ArrayList<>();
        final List<Map<String, Object>> dumps = new ArrayList<>();
        final List<List<String>> transformFailures = new ArrayList<>();
        long transformFailureCount;
        long overflowedEdges;
    }

    /** A JVM's binary event log, with the dump's id tables needed to translate it. */
    static final class EventLogRef {
        final int jvm;
        final Path file;
        final String[] signatures;
        final String[] contexts;
        final AgentDump dump;

        EventLogRef(int jvm, Path file, String[] signatures, String[] contexts, AgentDump dump) {
            this.jvm = jvm;
            this.file = file;
            this.signatures = signatures;
            this.contexts = contexts;
            this.dump = dump;
        }
    }

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
        // The Maven/Gradle build JVM only counts when tests ran in-process (no test worker JVM).
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
        if (dump.eventsFile != null) {
            graph.eventLogs.add(new EventLogRef(jvm, dumpFile.resolveSibling(dump.eventsFile), signatures, contexts, dump));
        }
        graph.transformFailureCount += dump.transformFailureCount;
        graph.transformFailures.addAll(dump.transformFailures);
        graph.overflowedEdges += dump.overflowedEdges;
    }

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

    static VersionGraph build(String label, String commit, Path projectDir, List<String> includes,
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

        // ---- nodes -----------------------------------------------------------------------------
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

        // ---- edges per context, and aggregated over contexts ------------------------------------
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

        // Event logs stay on disk; VersionGraph.write streams them using these id translations.
        List<EventExport.Source> eventSources = new ArrayList<>();
        for (int runIndex = 0; runIndex < nRuns; runIndex++) {
            for (EventLogRef log : runGraphs.get(runIndex).eventLogs) {
                int[] nodeOf = new int[log.signatures.length];
                for (int id = 0; id < nodeOf.length; id++) {
                    nodeOf[id] = log.signatures[id] == null ? -1 : nodeIndex.getOrDefault(log.signatures[id], -1);
                }
                int[] contextOf = new int[log.contexts.length];
                for (int id = 0; id < contextOf.length; id++) {
                    contextOf[id] = contextIndex.getOrDefault(log.contexts[id], 0);
                }
                eventSources.add(new EventExport.Source(runIndex, log.jvm, log.file, nodeOf, contextOf, log.dump));
            }
        }

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
        stats.put("call_events", 0L); // set when the event logs are written

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("schema_version", 1);
        header.put("tool", "dyncg " + Main.VERSION);
        header.put("version_label", label);
        header.put("commit", commit);
        header.put("project_dir", projectDir.toString());
        header.put("include_prefixes", includes);
        header.put("runs", runInfo);
        header.put("stats", stats);
        List<List<String>> failures = runGraphs.isEmpty() ? List.of() : runGraphs.get(0).transformFailures;
        header.put("transform_failures", failures.subList(0, Math.min(MAX_TRANSFORM_FAILURES, failures.size())));
        return new VersionGraph(header, nodes, contexts, edges, aggregate, eventSources);
    }

    private static int runsSeen(long[] counts) {
        int seen = 0;
        for (long c : counts) {
            if (c != 0) {
                seen++;
            }
        }
        return seen;
    }

    private static double mean(long[] counts) {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        return TestRunner.round((double) sum / counts.length, 2);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> entry(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
