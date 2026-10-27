package dyncg.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs {@code dyncg pair} on the two-commit fixture project in src/test/fixture and checks the graphs.
 * Subclasses choose the build tool. Set DYNCG_JAVA_HOME to run the fixture's tests on a specific JDK,
 * DYNCG_KEEP=1 to keep the output.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EndToEndChecks {
    static final Path FIXTURE = Paths.get("src/test/fixture").toAbsolutePath();
    private static final String T = "sample.CalculatorTest";
    private static final String CALC = "sample.Calculator";

    private Path tmp;
    private Path repo;
    Path out;
    private final Map<String, JsonNode> graphs = new HashMap<>();
    private JsonNode tests;

    /** Turns the checked-out v1 fixture (with its pom.xml) into a project for this build tool. */
    abstract void useBuild(Path repo) throws IOException;

    /** Command-line options selecting the build tool. */
    abstract List<String> buildArgs();

    private List<String> commonArgs() {
        List<String> args = new ArrayList<>(buildArgs());
        String javaHome = System.getenv("DYNCG_JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            args.addAll(List.of("--java-home", javaHome));
        }
        return args;
    }

    @BeforeAll
    void runPair() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(TestRunOptions.defaultAgentJar()),
                "build the agent first: mvn -B package");
        tmp = Files.createTempDirectory("dyncg-e2e-");
        repo = tmp.resolve("repo");
        FileUtil.copyTree(FIXTURE.resolve("v1"), repo);
        useBuild(repo);
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "v1");
        String before = git(repo, "rev-parse", "HEAD");
        FileUtil.copyTree(FIXTURE.resolve("v2"), repo);
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "v2: fix Square.area");
        String after = git(repo, "rev-parse", "HEAD");

        out = tmp.resolve("out");
        List<String> argv = new ArrayList<>(List.of("pair", "--repo", repo.toString(), "--before", before,
                "--after", after, "--out", out.toString(), "--repeats", "2", "--record-events"));
        argv.addAll(commonArgs());
        assertEquals(0, Main.execute(argv.toArray(new String[0])));
        for (String label : List.of("before", "after")) {
            graphs.put(label, Json.MAPPER.readTree(out.resolve(label).resolve("callgraph.json").toFile()));
        }
        tests = Json.MAPPER.readTree(out.resolve("tests.json").toFile());
    }

    @AfterAll
    void cleanUp() {
        if (tmp != null && System.getenv("DYNCG_KEEP") == null) {
            FileUtil.deleteQuietly(tmp);
        }
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("GIT_AUTHOR_NAME", "dyncg");
        env.put("GIT_AUTHOR_EMAIL", "dyncg@example.invalid");
        env.put("GIT_COMMITTER_NAME", "dyncg");
        env.put("GIT_COMMITTER_EMAIL", "dyncg@example.invalid");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static Map<Integer, String> signatures(JsonNode graph) {
        Map<Integer, String> sig = new HashMap<>();
        graph.get("nodes").forEach(n -> sig.put(n.get("id").asInt(), n.get("signature").asText()));
        return sig;
    }

    private static JsonNode context(JsonNode graph, String name) {
        for (JsonNode c : graph.get("contexts")) {
            if (c.get("name").asText().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no context " + name);
    }

    private static Set<List<String>> edgesOf(JsonNode graph, String contextName) {
        Map<Integer, String> sig = signatures(graph);
        int ctx = context(graph, contextName).get("id").asInt();
        Set<List<String>> edges = new HashSet<>();
        for (JsonNode e : graph.get("edges")) {
            if (e.get(0).asInt() == ctx) {
                edges.add(List.of(sig.get(e.get(1).asInt()), sig.get(e.get(2).asInt())));
            }
        }
        return edges;
    }

    private static Set<List<String>> allEdges(JsonNode graph) {
        Map<Integer, String> sig = signatures(graph);
        Set<List<String>> edges = new HashSet<>();
        for (JsonNode e : graph.get("aggregate_edges")) {
            edges.add(List.of(sig.get(e.get(0).asInt()), sig.get(e.get(1).asInt())));
        }
        return edges;
    }

    @Test
    void detectedPrefixAndForkedJvmUsed() {
        for (JsonNode graph : graphs.values()) {
            assertEquals("[\"sample/\"]", graph.get("include_prefixes").toString());
            for (JsonNode run : graph.get("runs")) {
                List<JsonNode> used = new ArrayList<>();
                run.get("dumps").forEach(d -> {
                    if (d.get("used").asBoolean()) {
                        used.add(d);
                    }
                });
                assertFalse(used.isEmpty(), "agent produced no dump despite the fixed <argLine>");
                for (JsonNode d : used) {
                    assertEquals("test-jvm", d.get("role").asText());
                    assertTrue(d.get("complete").asBoolean());
                }
            }
        }
    }

    @Test
    void virtualDispatchThroughLambda() {
        Set<List<String>> edges = edgesOf(graphs.get("after"), T + "#totalOfCircles");
        assertTrue(edges.contains(List.of(T + "#totalOfCircles()V", CALC + "#total(Ljava/util/List;)D")));
        List<String> callers = edges.stream().filter(e -> e.get(1).equals("sample.Circle#area()D"))
                .map(e -> e.get(0)).collect(Collectors.toList());
        assertFalse(callers.isEmpty());
        assertTrue(callers.stream().allMatch(c -> c.startsWith(CALC + "#lambda$total$")), callers.toString());
    }

    @Test
    void caughtExceptionKeepsCaller() {
        Set<List<String>> edges = edgesOf(graphs.get("after"), T + "#safeAreaFallsBack");
        assertTrue(edges.contains(List.of(CALC + "#safeArea(Lsample/Shape;)D", CALC + "#riskyArea(Lsample/Shape;)D")));
        assertTrue(edges.contains(List.of(CALC + "#safeArea(Lsample/Shape;)D", CALC + "#fallback()D")));
        assertFalse(edges.contains(List.of(CALC + "#riskyArea(Lsample/Shape;)D", CALC + "#fallback()D")));
    }

    @Test
    void exceptionThroughSeveralFrames() {
        Set<List<String>> edges = edgesOf(graphs.get("after"), T + "#deepThrowFallsBack");
        assertTrue(edges.contains(List.of(CALC + "#descend(I)D", CALC + "#descend(I)D")));
        Set<String> fallbackCallers = edges.stream().filter(e -> e.get(1).equals(CALC + "#fallback()D"))
                .map(e -> e.get(0)).collect(Collectors.toSet());
        assertEquals(Set.of(CALC + "#deepThrow(I)D"), fallbackCallers);
    }

    @Test
    void threadInheritsTestContext() {
        Set<List<String>> edges = edgesOf(graphs.get("after"), T + "#areaInThread");
        List<List<String>> threadEntry = edges.stream().filter(e -> e.get(1).startsWith(CALC + "#lambda$inThread$"))
                .collect(Collectors.toList());
        assertEquals(1, threadEntry.size());
        assertEquals("<root>", threadEntry.get(0).get(0));
        assertTrue(edges.contains(List.of(threadEntry.get(0).get(1), "sample.Circle#area()D")));
    }

    @Test
    void constructorAndStaticInitializer() {
        Set<List<String>> edges = edgesOf(graphs.get("after"), T + "#negativeRadiusRejected");
        assertTrue(edges.contains(List.of("sample.Circle#<init>(D)V", "sample.Checks#positive(D)D")));
        assertTrue(allEdges(graphs.get("after")).contains(List.of(CALC + "#<clinit>()V", CALC + "#initScale()D")));
    }

    @Test
    void fixtureContext() {
        assertEquals("fixture", context(graphs.get("after"), T + "#setUp").get("kind").asText());
        assertTrue(edgesOf(graphs.get("after"), T + "#setUp").contains(List.of(T + "#setUp()V", CALC + "#<init>()V")));
    }

    @Test
    void failingTestStillRecorded() {
        JsonNode before = context(graphs.get("before"), T + "#squareArea");
        assertEquals("failed", before.get("status").asText());
        assertEquals("[\"failed\",\"failed\"]", before.get("run_statuses").toString());
        assertNotNull(before.get("failure"));
        assertTrue(before.get("failure").get("type").asText().contains("AssertionError"));
        assertTrue(edgesOf(graphs.get("before"), T + "#squareArea")
                .contains(List.of(T + "#squareArea()V", "sample.Square#area()D")));
        assertEquals("passed", context(graphs.get("after"), T + "#squareArea").get("status").asText());
    }

    @Test
    void changedCodeVisible() {
        List<String> newEdge = List.of("sample.Square#area()D", "sample.Checks#squared(D)D");
        assertFalse(edgesOf(graphs.get("before"), T + "#squareArea").contains(newEdge));
        assertTrue(edgesOf(graphs.get("after"), T + "#squareArea").contains(newEdge));
    }

    @Test
    void edgesStableAcrossRuns() {
        for (JsonNode graph : graphs.values()) {
            assertEquals(graph.get("stats").get("aggregate_edges").asLong(),
                    graph.get("stats").get("stable_aggregate_edges").asLong());
        }
    }

    /** Rows of call_events.tsv.gz as numbers: run, jvm, sequence, thread, context, caller, callee. */
    private static List<long[]> readEvents(Path file) throws IOException {
        List<long[]> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            assertEquals(String.join("\t", EventExport.COLUMNS), in.readLine());
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String[] fields = line.split("\t");
                long[] row = new long[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    row[i] = Long.parseLong(fields[i]);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    void everyInvocationIsLoggedInOrder() throws IOException {
        for (String label : List.of("before", "after")) {
            JsonNode graph = graphs.get(label);
            JsonNode summary = graph.get("call_events");
            assertEquals(EventExport.FILE, summary.get("file").asText());
            assertEquals(0, summary.get("unmapped").asLong());
            assertEquals(0, summary.get("missing").asLong());
            assertEquals(0, summary.get("jvms_at_limit").asInt());

            long calls = 0;
            Set<List<Long>> edgeKeys = new HashSet<>();
            for (JsonNode e : graph.get("edges")) {
                for (JsonNode count : e.get(5)) {
                    calls += count.asLong();
                }
                edgeKeys.add(List.of(e.get(0).asLong(), e.get(1).asLong(), e.get(2).asLong()));
            }
            List<long[]> rows = readEvents(out.resolve(label).resolve(EventExport.FILE));
            // Below the limit, every counted call is logged exactly once.
            assertEquals(calls, rows.size());
            assertEquals(calls, summary.get("rows").asLong());
            assertEquals(calls, graph.get("stats").get("call_events").asLong());
            Map<String, Long> nextSequence = new HashMap<>();
            for (long[] row : rows) {
                String jvm = row[0] + "/" + row[1];
                long expected = nextSequence.getOrDefault(jvm, 1L);
                assertEquals(expected, row[2], "sequence numbers of " + jvm + " must be 1, 2, ... in order");
                nextSequence.put(jvm, expected + 1);
                assertTrue(edgeKeys.contains(List.of(row[4], row[5], row[6])), "event without an edge");
            }
        }
    }

    @Test
    void eventLogIsOptionalAndLimited() throws Exception {
        Path limited = tmp.resolve("limited");
        List<String> argv = new ArrayList<>(List.of("version", "--project-dir", repo.toString(), "--out",
                limited.toString(), "--repeats", "1", "--record-events", "--max-events", "25"));
        argv.addAll(commonArgs());
        assertEquals(0, Main.execute(argv.toArray(new String[0])));
        JsonNode graph = Json.MAPPER.readTree(limited.resolve("callgraph.json").toFile());
        JsonNode summary = graph.get("call_events");
        int jvms = summary.get("jvms").asInt();
        assertTrue(jvms >= 1);
        assertEquals(jvms, summary.get("jvms_at_limit").asInt());
        assertEquals(25L * jvms, summary.get("rows").asLong());
        assertEquals(25L * jvms, readEvents(limited.resolve(EventExport.FILE)).size());
        // The limit only applies to the event log; edges and counts are complete.
        assertEquals(graphs.get("after").get("stats").get("aggregate_edges"), graph.get("stats").get("aggregate_edges"));

        Path plain = tmp.resolve("plain");
        argv = new ArrayList<>(List.of("version", "--project-dir", repo.toString(), "--out", plain.toString(),
                "--repeats", "1"));
        argv.addAll(commonArgs());
        assertEquals(0, Main.execute(argv.toArray(new String[0])));
        JsonNode plainGraph = Json.MAPPER.readTree(plain.resolve("callgraph.json").toFile());
        assertTrue(plainGraph.get("call_events").isNull());
        assertFalse(Files.exists(plain.resolve(EventExport.FILE)));
        try (var dumps = Files.list(plain.resolve("runs/0/dumps"))) {
            assertTrue(dumps.noneMatch(p -> p.getFileName().toString().endsWith(".events")));
        }
    }

    @Test
    void outcomeClassification() {
        Map<String, String> categories = new HashMap<>();
        tests.get("tests").forEach(t -> categories.put(t.get("test").asText(), t.get("category").asText()));
        assertEquals("fail_pass", categories.get(T + "#squareArea"));
        assertEquals("{\"fail_pass\":1,\"pass_pass\":5}", tests.get("categories").toString());
    }
}
