package cgsense.tester.driver;

import cgsense.model.CommitPair;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates call graphs for an already-checked-out commit pair and then generates the call-graph
 * diff.
 *
 * <p>This class does not clone repositories or check out commits.
 */

//
public final class PairCallGraphRunner {

  private PairCallGraphRunner() {}

  public static void run(
      CommitPair pair, Path beforeProject, Path afterProject, Path outputDirectory, RunOptions run)
      throws Exception {

    validateInputs(pair, beforeProject, afterProject, outputDirectory, run);

    Files.createDirectories(outputDirectory);

    ProjectSources beforeSources = ProjectSources.scan(beforeProject);

    ProjectSources afterSources = ProjectSources.scan(afterProject);

    List<String> includes = determineIncludes(run, beforeSources, afterSources);

    /*
     * Convert the common RunOptions into the immutable options used by
     * VersionRunner. These options, including the build tool, are reused
     * for both versions.
     */
    TestRunOptions testOptions = run.toOptions(includes);

    Path beforeOutput = outputDirectory.resolve("before");

    Path afterOutput = outputDirectory.resolve("after");

    VersionRunner.run(
        "before",
        pair.parentSha(),
        beforeProject,
        beforeOutput,
        run.repeats,
        testOptions,
        beforeSources);

    VersionRunner.run(
        "after",
        pair.childSha(),
        afterProject,
        afterOutput,
        run.repeats,
        testOptions,
        afterSources);

    Path beforeGraph = beforeOutput.resolve("callgraph.json");

    Path afterGraph = afterOutput.resolve("callgraph.json");

    Map<String, Object> diff = CallGraphDiffMain.buildDiff(beforeGraph, afterGraph);

    Path diffOutput = outputDirectory.resolve("diff.json");

    Json.writePretty(diffOutput, diff);

    System.out.printf("[dyncg] pair %s: wrote %s%n", pair.fullName(), diffOutput);
  }

  private static void validateInputs(
      CommitPair pair,
      Path beforeProject,
      Path afterProject,
      Path outputDirectory,
      RunOptions run) {

    if (pair == null) {
      throw new UsageException("commit pair is required");
    }

    if (beforeProject == null) {
      throw new UsageException("before project is required");
    }

    if (afterProject == null) {
      throw new UsageException("after project is required");
    }

    if (outputDirectory == null) {
      throw new UsageException("output directory is required");
    }

    if (run == null) {
      throw new UsageException("run options are required");
    }

    if (!Files.isDirectory(beforeProject)) {
      throw new UsageException("before project does not exist: " + beforeProject);
    }

    if (!Files.isDirectory(afterProject)) {
      throw new UsageException("after project does not exist: " + afterProject);
    }

    if (pair.parentSha() == null || pair.parentSha().isBlank()) {
      throw new UsageException("commit pair has no parent SHA");
    }

    if (pair.childSha() == null || pair.childSha().isBlank()) {
      throw new UsageException("commit pair has no child SHA");
    }

    /*
     * A build tool must be supplied explicitly. Both versions will use
     * this same tool.
     */
    BuildTool buildTool = BuildTool.parse(run.buildTool);

    if (buildTool == BuildTool.AUTO) {
      throw new UsageException("--build-tool must be explicitly " + "maven or gradle");
    }
  }

  private static List<String> determineIncludes(
      RunOptions run, ProjectSources beforeSources, ProjectSources afterSources) {

    if (!run.includes.isEmpty()) {
      return List.copyOf(run.includes);
    }

    /*
     * Use the union of packages detected in both versions. This ensures
     * that the before and after graphs use identical instrumentation
     * prefixes.
     */
    Set<String> detected = new LinkedHashSet<>();

    detected.addAll(beforeSources.detectIncludePrefixes());

    detected.addAll(afterSources.detectIncludePrefixes());

    if (detected.isEmpty()) {
      throw new UsageException("could not detect project packages; " + "pass --include");
    }

    return List.copyOf(detected);
  }
}
