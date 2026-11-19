package cgsense.tester.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*VersionRunner is the coordinator for one version. It doesn't do the hard work itself; it calls the other classes in the right order and prints progress:

detect the build tool
run the tests N times (TestRunner)
build the call graph from all runs (GraphBuilder)
write it to disk (VersionGraph)
print warnings and a summary*/

/*VersionCommand.run
  └─ VersionRunner.run
        ├─ BuildTool.resolve + withBuildTool           → MAVEN
        ├─ repeat N times:
        │     create runs/<i>/
        │     TestRunner.runTests(...)                 → RunResult (exit, dumps, reports)
        ├─ GraphBuilder.build(..., runs, sources)      → VersionGraph
        ├─ graph.write(outDir)                         → callgraph.json + summary.json
        ├─ warnings (runs without tests / empty graph)
        └─ print summary, return graph
*/

public final class VersionRunner {

  private VersionRunner() {}

  /**
   * Runs the project's tests under the dynamic-callgraph Java agent and writes {@code
   * callgraph.json} into {@code outDir}.
   *
   * @param label label recorded in the graph, such as "before" or "after"
   * @param commit commit SHA recorded as metadata; it is not checked out or verified
   * @param projectDir existing checked-out project directory
   * @param outDir directory where graph data and run data are written
   * @param repeats number of times to run the tests
   * @param options test execution and instrumentation options
   * @param sources information about the project's source directories
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

    BuildTool buildTool =
        options.buildTool.resolve(
            projectDir); // If the build tool is AUTO, we detect it from the project directory. If
    // it's MAVEN or GRADLE, we just use that.
    options =
        options.withBuildTool(
            buildTool); // We create a new TestRunOptions with the resolved build tool. This ensures
    // that all subsequent test runs use the correct build tool, whether it was
    // specified by the user or detected automatically.

    System.out.printf("[dyncg] %s: build tool %s%n", label, buildTool.name().toLowerCase());

    List<RunResult> runs =
        new ArrayList<>(); // We will store the results of each test run in this list. Each
    // RunResult contains information about the exit code, duration, and any
    // dumps or reports generated during the run.

    for (int index = 0;
        index < repeats;
        index++) { // We loop over the number of repeats specified by the user. Each iteration
      // represents one complete test run under the agent.
      System.out.printf(
          "[dyncg] %s: test run %d/%d in %s%n", label, index + 1, repeats, projectDir);

      Path runDir = outDir.resolve("runs").resolve(Integer.toString(index));

      Files.createDirectories(runDir);

      RunResult run =
          TestRunner
              .runTests( // We call TestRunner.runTests to execute the tests under the agent. This
                  // method handles the actual invocation of Maven or Gradle, sets up the
                  // Java agent, and collects the results.
                  projectDir, runDir, index, options);

      System.out.printf(
          "[dyncg] %s: run %d exit=%s timed_out=%s %ss " + "dumps=%d report_dirs=%d%n",
          label,
          index + 1,
          run.exitCode,
          run.timedOut,
          run.durationSeconds,
          run.dumpFiles.size(),
          run.reportDirs.size());

      runs.add(run);
    }

    VersionGraph graph =
        GraphBuilder
            .build( // After all test runs are complete, we call GraphBuilder.build to construct the
                // call graph from the collected data. This method analyzes the dumps and
                // reports generated during the test runs and produces a VersionGraph object
                // that represents the call graph for this version of the project.
                label, commit, projectDir, options.includes, runs, sources);

    Path graphPath =
        graph.write(
            outDir); // We write the generated call graph to disk in the specified output directory.
    // The graph is typically written as a JSON file named callgraph.json, along
    // with any associated metadata.

    long runsWithoutTests =
        ((Number) graph.stats().get("runs_without_tests"))
            .longValue(); // We check how many of the test runs did not produce any test results.
    // This can happen if the build or test setup failed, and we want to warn
    // the user about it.

    if (runsWithoutTests > 0) {
      for (RunResult run : runs) {
        System.out.printf(
            "[dyncg] %s: run %d exit=%s (see %s)%n",
            label, run.index + 1, run.exitCode, run.runDir.resolve("build.log"));
      }

      System.out.printf(
          "[dyncg] WARNING: %s: %d of %d runs produced no test "
              + "results; the build or test setup probably failed%n",
          label, runsWithoutTests, runs.size());
    }

    long tests =
        ((Number) graph.stats().get("tests"))
            .longValue(); // We check how many tests were actually executed across all runs. If this
    // number is greater than
    // zero but the number of nodes in the call graph is zero, it indicates that the agent did not
    // record any method calls, which could be due to misconfiguration or instrumentation issues.
    long nodes =
        ((Number) graph.stats().get("nodes"))
            .longValue(); // We check how many nodes (methods) were recorded in the call graph. If
    // this number is zero, it means that no
    // method calls were captured during the test runs, which is unexpected if tests were actually
    // executed.

    if (tests > 0 && nodes == 0) {
      System.out.printf(
          "[dyncg] WARNING: %s: %d tests ran but no calls were "
              + "recorded; %s classes could not be instrumented "
              + "(see transform_failures in %s). Check --include "
              + "%s and the class-file version of the compiled code.%n",
          label, tests, graph.stats().get("transform_failures"), graphPath, options.includes);
    }

    System.out.printf("[dyncg] %s: wrote %s %s%n", label, graphPath, Json.compact(graph.stats()));

    return graph;
  }
}
