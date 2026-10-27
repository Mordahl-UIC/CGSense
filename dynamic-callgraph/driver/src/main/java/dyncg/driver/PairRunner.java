package dyncg.driver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Generating call graphs for both versions of a commit pair. */
final class PairRunner {
    private static final Set<String> BAD = Set.of("failed", "error");
    private static final List<String> LABELS = List.of("before", "after");

    private PairRunner() {
    }

    /** Runs git and returns its trimmed standard output; fails with git's error output. */
    static String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Path errors = Files.createTempFile("dyncg-git", ".err");
        try {
            Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(String.join(" ", command) + " failed (" + exitCode + "): "
                        + Files.readString(errors).strip());
            }
            return output.strip();
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    static String addWorktree(Path repo, String commit, Path path) throws IOException, InterruptedException {
        if (Files.exists(path)) {
            removeWorktree(repo, path);
        }
        git("-C", repo.toString(), "worktree", "add", "--detach", "--force", path.toString(), commit);
        return git("-C", path.toString(), "rev-parse", "HEAD");
    }

    static void removeWorktree(Path repo, Path path) {
        try {
            git("-C", repo.toString(), "worktree", "remove", "--force", path.toString());
        } catch (IOException | InterruptedException ignored) {
            // the directory is removed below either way
        }
        FileUtil.deleteQuietly(path);
    }

    /** Runs the tests {@code repeats} times under the agent and writes &lt;outDir&gt;/callgraph.json. */
    static VersionGraph runVersion(String label, String commit, Path projectDir, Path outDir, int repeats,
            TestRunOptions options, ProjectSources sources) throws IOException, InterruptedException {
        BuildTool buildTool = options.buildTool.resolve(projectDir);
        options = options.withBuildTool(buildTool);
        System.out.printf("[dyncg] %s: build tool %s%n", label, buildTool.name().toLowerCase());
        List<RunResult> runs = new ArrayList<>();
        for (int index = 0; index < repeats; index++) {
            System.out.printf("[dyncg] %s: test run %d/%d in %s%n", label, index + 1, repeats, projectDir);
            Path runDir = outDir.resolve("runs").resolve(Integer.toString(index));
            Files.createDirectories(runDir);
            RunResult run = TestRunner.runTests(projectDir, runDir, index, options);
            System.out.printf("[dyncg] %s: run %d exit=%s timed_out=%s %ss dumps=%d report_dirs=%d%n", label,
                    index + 1, run.exitCode, run.timedOut, run.durationSeconds, run.dumpFiles.size(),
                    run.reportDirs.size());
            runs.add(run);
        }
        VersionGraph graph = GraphBuilder.build(label, commit, projectDir, options.includes, runs, sources);
        Path path = graph.write(outDir);
        if (((Number) graph.stats().get("runs_without_tests")).longValue() > 0) {
            for (RunResult run : runs) {
                System.out.printf("[dyncg] %s: run %d exit=%s", label, run.index + 1, run.exitCode);
                System.out.printf(" (see %s)%n", run.runDir.resolve("build.log"));
            }
            System.out.printf("[dyncg] WARNING: %s: %s of %d runs produced no test results; the build or the "
                    + "test setup probably failed%n", label, graph.stats().get("runs_without_tests"), runs.size());
        }
        long tests = ((Number) graph.stats().get("tests")).longValue();
        long nodes = ((Number) graph.stats().get("nodes")).longValue();
        if (tests > 0 && nodes == 0) {
            System.out.printf("[dyncg] WARNING: %s: %d tests ran but no calls were recorded; %s classes could not be "
                    + "instrumented (see transform_failures in %s). Check --include %s and the class-file version "
                    + "of the compiled code.%n", label, tests, graph.stats().get("transform_failures"), path,
                    options.includes);
        }
        System.out.printf("[dyncg] %s: wrote %s %s%n", label, path, Json.compact(graph.stats()));
        return graph;
    }

    static String classify(String before, String after) {
        if (before == null) {
            return "after_only";
        }
        if (after == null) {
            return "before_only";
        }
        if (Set.of("flaky", "inconsistent").contains(before) || Set.of("flaky", "inconsistent").contains(after)) {
            return "unstable";
        }
        if (before.equals("passed") && after.equals("passed")) {
            return "pass_pass";
        }
        if (BAD.contains(before) && after.equals("passed")) {
            return "fail_pass";
        }
        if (before.equals("passed") && BAD.contains(after)) {
            return "pass_fail";
        }
        if (BAD.contains(before) && BAD.contains(after)) {
            return "fail_fail";
        }
        if (before.equals("skipped") || after.equals("skipped")) {
            return "skipped";
        }
        return "not_reported";
    }

    static Map<String, Object> compareTests(VersionGraph before, VersionGraph after) {
        Map<String, String> b = testStatuses(before);
        Map<String, String> a = testStatuses(after);
        TreeSet<String> names = new TreeSet<>(b.keySet());
        names.addAll(a.keySet());
        List<Map<String, Object>> tests = new ArrayList<>();
        TreeMap<String, Integer> categories = new TreeMap<>();
        for (String name : names) {
            String category = classify(b.get(name), a.get(name));
            Map<String, Object> test = new LinkedHashMap<>();
            test.put("test", name);
            test.put("before", b.get(name));
            test.put("after", a.get(name));
            test.put("category", category);
            tests.add(test);
            categories.merge(category, 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", categories);
        result.put("tests", tests);
        return result;
    }

    private static Map<String, String> testStatuses(VersionGraph graph) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (Map<String, Object> c : graph.contexts) {
            if ("test".equals(c.get("kind"))) {
                statuses.put((String) c.get("name"), (String) c.get("status"));
            }
        }
        return statuses;
    }

    static Map<String, Object> runPair(Path repo, String before, String after, Path outDir, int repeats,
            TestRunOptions options, Path beforeJavaHome, Path afterJavaHome, Path workdir, boolean keepWorktrees,
            List<String> includes) throws IOException, InterruptedException {
        Files.createDirectories(outDir);
        long started = System.nanoTime();
        Map<String, Path> worktrees = Map.of("before", workdir.resolve("before"), "after", workdir.resolve("after"));
        Map<String, String> refs = Map.of("before", before, "after", after);
        Map<String, Path> javaHomes = new LinkedHashMap<>();
        javaHomes.put("before", beforeJavaHome);
        javaHomes.put("after", afterJavaHome);
        Map<String, String> commits = new LinkedHashMap<>();
        Map<String, VersionGraph> graphs = new LinkedHashMap<>();
        List<String> includeList = includes;
        try {
            for (String label : LABELS) {
                commits.put(label, addWorktree(repo, refs.get(label), worktrees.get(label)));
            }
            Map<String, ProjectSources> sources = new LinkedHashMap<>();
            for (String label : LABELS) {
                sources.put(label, ProjectSources.scan(worktrees.get(label)));
            }
            if (includeList == null || includeList.isEmpty()) {
                // One prefix list for both versions, so the two graphs cover the same code.
                TreeSet<String> union = new TreeSet<>(sources.get("before").detectIncludePrefixes());
                union.addAll(sources.get("after").detectIncludePrefixes());
                includeList = new ArrayList<>(union);
            }
            if (includeList.isEmpty()) {
                throw new UsageException("could not detect project packages; pass --include");
            }
            System.out.println("[dyncg] include prefixes: " + includeList);

            for (String label : LABELS) {
                Path javaHome = javaHomes.get(label) != null ? javaHomes.get(label) : options.javaHome;
                TestRunOptions versionOptions = options.withIncludes(includeList).withJavaHome(javaHome);
                graphs.put(label, runVersion(label, commits.get(label), worktrees.get(label),
                        outDir.resolve(label), repeats, versionOptions, sources.get(label)));
            }
        } finally {
            if (!keepWorktrees) {
                for (Path path : worktrees.values()) {
                    removeWorktree(repo, path);
                }
                if (Files.isDirectory(workdir)) {
                    try (Stream<Path> entries = Files.list(workdir)) {
                        if (entries.findAny().isEmpty()) {
                            Files.delete(workdir);
                        }
                    }
                }
            }
        }

        Map<String, Object> comparison = compareTests(graphs.get("before"), graphs.get("after"));
        Json.writePretty(outDir.resolve("tests.json"), comparison);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tool", "dyncg " + Main.VERSION);
        manifest.put("repo", repo.toString());
        for (String label : LABELS) {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("ref", refs.get(label));
            version.put("commit", commits.get(label));
            version.put("stats", graphs.get(label).stats());
            manifest.put(label, version);
        }
        manifest.put("include_prefixes", includeList);
        manifest.put("repeats", repeats);
        manifest.put("test_selection", options.tests);
        manifest.put("test_categories", comparison.get("categories"));
        manifest.put("duration_s", TestRunner.round((System.nanoTime() - started) / 1e9, 1));
        manifest.put("worktrees_kept", keepWorktrees);
        Json.writePretty(outDir.resolve("pair.json"), manifest);
        return manifest;
    }
}
