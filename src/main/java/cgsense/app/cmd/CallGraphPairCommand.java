package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.exec.Container;
import cgsense.exec.Containers;
import cgsense.io.JsonlReader;
import cgsense.model.CommitPair;
import cgsense.repo.Checkout;
import cgsense.repo.ContainerRepo;
import cgsense.repo.Repo;
import cgsense.tester.driver.FileUtil;
import cgsense.tester.driver.PairCallGraphRunner;
import cgsense.tester.driver.RunOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * Generates call graphs and a diff for every pair in data/pairs.jsonl. Each repository gets one
 * container both commits of a pair are checked out and tested in it, and only the results are
 * copied back. The sources are also copied out, for the driver to scan.
 */
public final class CallGraphPairCommand implements CliCommand {
  private final DataDir data;

  public CallGraphPairCommand() {
    this(DataDir.DEFAULT);
  }

  public CallGraphPairCommand(DataDir data) {
    this.data = data;
  }

  @Override
  public String name() {
    return "callgraph-pair";
  }

  @Override
  public Options options() {
    return RunOptions.addTo(
        new Options()
            .addOption(
                Option.builder()
                    .longOpt("output")
                    .hasArg()
                    .required()
                    .desc("directory for the call graphs and diffs")
                    .get()));
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    Path outputDir = FileUtil.abs(Path.of(cl.getOptionValue("output")));
    if (!Files.isRegularFile(data.pairs())) {
      System.err.println("No " + data.pairs() + "; run mine-pairs first.");
      return 1;
    }

    // The stream is only read here; all the work below happens on the finished map.
    Map<String, List<CommitPair>> byRepo;
    try (Stream<CommitPair> pairs = JsonlReader.read(data.pairs(), CommitPair.class)) {
      byRepo =
          pairs.collect(
              Collectors.groupingBy(CommitPair::fullName, LinkedHashMap::new, Collectors.toList()));
    }

    RunOptions runOptions = RunOptions.from(cl);
    Path agentJar = runOptions.resolvedAgentJar();
    Files.createDirectories(outputDir);

    int failed = 0;
    for (Map.Entry<String, List<CommitPair>> entry : byRepo.entrySet()) {
      Path mirror = data.clone(entry.getKey());
      if (!Files.isDirectory(mirror)) {
        System.err.println("No clone " + mirror + "; skipping " + entry.getKey());
        failed += entry.getValue().size();
        continue;
      }
      Container.Builder setup = Containers.repo(DataDir.slug(entry.getKey()), mirror);
      try (Container box = ContainerRepo.withTools(setup, agentJar).start()) {
        Repo repo = new ContainerRepo(box);
        for (CommitPair pair : entry.getValue()) {
          try {
            runPair(repo, pair, outputDir, runOptions);
          } catch (Exception e) {
            failed++;
            System.err.printf("[dyncg] pair %s failed: %s%n", pair.ID(), e.getMessage());
          }
        }
      }
    }
    return failed == 0 ? 0 : 1;
  }

  private static void runPair(Repo repo, CommitPair pair, Path outputDir, RunOptions run)
      throws Exception {
    Path pairOut = outputDir.resolve(DataDir.slug(pair.fullName())).resolve(pair.ID());
    Path sources = Files.createTempDirectory("cgsense-sources");
    try (Checkout before = repo.checkout(pair.parent());
        Checkout after = repo.checkout(pair.child())) {
      prepare(before, pair.parentSha(), pair.javaVersion(), sources.resolve("before"));
      prepare(after, pair.childSha(), pair.javaVersion(), sources.resolve("after"));
      PairCallGraphRunner.run(pair, before, after, pairOut, run);
    } finally {
      FileUtil.deleteRecursively(sources);
    }
  }

  /** Copies the sources out for the driver to scan, then builds on the pair's JDK. */
  private static void prepare(Checkout checkout, String sha, int jdk, Path sources)
      throws IOException {
    checkout.copyTo(sources);
    if (!checkout.build(jdk)) {
      throw new IOException(sha + " does not build on JDK " + jdk);
    }
  }
}
