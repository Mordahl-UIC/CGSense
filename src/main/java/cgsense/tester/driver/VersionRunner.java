package cgsense.tester.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs tests and generates a dynamic call graph for one already checked-out
 * project version.
 *
 */
public final class VersionRunner {

    private VersionRunner() {
    }

    /**
     * Runs the project's tests under the dynamic-callgraph Java agent and writes
     * {@code callgraph.json} into {@code outDir}.
     *
     * @param label      label recorded in the graph, such as "before" or "after"
     * @param commit     commit SHA recorded as metadata; it is not checked out or
     *                   verified
     * @param projectDir existing checked-out project directory
     * @param outDir     directory where graph data and run data are written
     * @param repeats    number of times to run the tests
     * @param options    test execution and instrumentation options
     * @param sources    information about the project's source directories
     * @return the generated graph
     */
    public static VersionGraph run(
            String label,
            String commit,
            Path projectDir,
            Path outDir,
            int repeats,
            TestRunOptions options,
            ProjectSources sources)
            throws IOException, InterruptedException {

        BuildTool buildTool = options.buildTool.resolve(projectDir);
        options = options.withBuildTool(buildTool);

        System.out.printf(
                "[dyncg] %s: build tool %s%n",
                label,
                buildTool.name().toLowerCase());

        List<RunResult> runs = new ArrayList<>();

        for (int index = 0; index < repeats; index++) {
            System.out.printf(
                    "[dyncg] %s: test run %d/%d in %s%n",
                    label,
                    index + 1,
                    repeats,
                    projectDir);

            Path runDir = outDir
                    .resolve("runs")
                    .resolve(Integer.toString(index));

            Files.createDirectories(runDir);

            RunResult run = TestRunner.runTests(
                    projectDir,
                    runDir,
                    index,
                    options);

            System.out.printf(
                    "[dyncg] %s: run %d exit=%s timed_out=%s %ss "
                            + "dumps=%d report_dirs=%d%n",
                    label,
                    index + 1,
                    run.exitCode,
                    run.timedOut,
                    run.durationSeconds,
                    run.dumpFiles.size(),
                    run.reportDirs.size());

            runs.add(run);
        }

        VersionGraph graph = GraphBuilder.build(
                label,
                commit,
                projectDir,
                options.includes,
                runs,
                sources);

        Path graphPath = graph.write(outDir);

        long runsWithoutTests =
                ((Number) graph.stats().get("runs_without_tests")).longValue();

        if (runsWithoutTests > 0) {
            for (RunResult run : runs) {
                System.out.printf(
                        "[dyncg] %s: run %d exit=%s (see %s)%n",
                        label,
                        run.index + 1,
                        run.exitCode,
                        run.runDir.resolve("build.log"));
            }

            System.out.printf(
                    "[dyncg] WARNING: %s: %d of %d runs produced no test "
                            + "results; the build or test setup probably failed%n",
                    label,
                    runsWithoutTests,
                    runs.size());
        }

        long tests = ((Number) graph.stats().get("tests")).longValue();
        long nodes = ((Number) graph.stats().get("nodes")).longValue();

        if (tests > 0 && nodes == 0) {
            System.out.printf(
                    "[dyncg] WARNING: %s: %d tests ran but no calls were "
                            + "recorded; %s classes could not be instrumented "
                            + "(see transform_failures in %s). Check --include "
                            + "%s and the class-file version of the compiled code.%n",
                    label,
                    tests,
                    graph.stats().get("transform_failures"),
                    graphPath,
                    options.includes);
        }

        System.out.printf(
                "[dyncg] %s: wrote %s %s%n",
                label,
                graphPath,
                Json.compact(graph.stats()));

        return graph;
    }
}