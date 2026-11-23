package cgsense.app.cmd;

import cgsense.model.BuildTool;
import cgsense.model.Commit;
import cgsense.repo.Checkout;
import cgsense.tester.driver.FileUtil;
import cgsense.tester.driver.ProjectSources;
import cgsense.tester.driver.RunOptions;
import cgsense.tester.driver.UsageException;
import cgsense.tester.driver.VersionRunner;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** {@code dyncg version}: call graph for one already checked-out version. */
public final class VersionCommand implements CliCommand {
  @Override
  public String name() {
    return "version";
  }

  @Override
  public Options options() {
    return RunOptions.addTo(
        new Options()
            .addOption(RunOptions.required("project-dir", "dir", "the checked-out project"))
            .addOption(RunOptions.required("out", "dir", "output directory"))
            .addOption(RunOptions.required("build-tool", "tool", "maven or gradle"))
            .addOption(
                RunOptions.value("label", "name", "version label in the output (default: version)"))
            .addOption(RunOptions.value("commit", "sha", "commit id recorded in the output")));
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    RunOptions run = RunOptions.from(cl);
    Path project = FileUtil.abs(RunOptions.path(cl, "project-dir"));
    ProjectSources sources = ProjectSources.scan(project);
    List<String> includes = run.includes.isEmpty() ? sources.detectIncludePrefixes() : run.includes;
    if (includes.isEmpty()) {
      throw new UsageException("could not detect project packages; pass --include");
    }
    Commit commit = new Commit(cl.getOptionValue("commit"), buildTool(cl));
    try (Checkout checkout = Checkout.local(commit, project)) {
      VersionRunner.run(
          cl.getOptionValue("label", "version"),
          commit.sha(),
          checkout,
          FileUtil.abs(RunOptions.path(cl, "out")),
          run.repeats,
          run.toOptions(includes),
          sources);
    }
    return 0;
  }

  private static BuildTool buildTool(CommandLine cl) {
    try {
      return BuildTool.parse(cl.getOptionValue("build-tool"));
    } catch (IllegalArgumentException e) {
      throw new UsageException(e.getMessage());
    }
  }
}
