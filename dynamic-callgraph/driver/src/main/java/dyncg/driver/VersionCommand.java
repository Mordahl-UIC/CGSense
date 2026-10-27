package dyncg.driver;

import java.nio.file.Path;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** {@code dyncg version}: call graph for one already checked-out version. */
final class VersionCommand implements CliCommand {
    @Override
    public String name() {
        return "version";
    }

    @Override
    public Options options() {
        return RunOptions.addTo(new Options()
                .addOption(RunOptions.required("project-dir", "dir", "the checked-out project"))
                .addOption(RunOptions.required("out", "dir", "output directory"))
                .addOption(RunOptions.value("label", "name", "version label in the output (default: version)"))
                .addOption(RunOptions.value("commit", "sha", "commit id recorded in the output")));
    }

    @Override
    public int run(CommandLine cl) throws Exception {
        RunOptions run = RunOptions.from(cl);
        Path project = Main.abs(RunOptions.path(cl, "project-dir"));
        ProjectSources sources = ProjectSources.scan(project);
        List<String> includes = run.includes.isEmpty() ? sources.detectIncludePrefixes() : run.includes;
        if (includes.isEmpty()) {
            throw new UsageException("could not detect project packages; pass --include");
        }
        PairRunner.runVersion(cl.getOptionValue("label", "version"), cl.getOptionValue("commit"), project,
                Main.abs(RunOptions.path(cl, "out")), run.repeats, run.toOptions(includes), sources);
        return 0;
    }
}
