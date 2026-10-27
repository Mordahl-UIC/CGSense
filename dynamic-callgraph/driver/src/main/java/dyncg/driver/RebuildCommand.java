package dyncg.driver;

import java.nio.file.Path;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** {@code dyncg rebuild}: callgraph.json from the raw outputs of earlier test runs, without running tests. */
final class RebuildCommand implements CliCommand {
    @Override
    public String name() {
        return "rebuild";
    }

    @Override
    public Options options() {
        return new Options()
                .addOption(RunOptions.required("runs-dir", "dir",
                        "directory holding <i>/dumps and <i>/surefire-reports, e.g. <out>/before/runs"))
                .addOption(RunOptions.required("project-dir", "dir", "source checkout, used for node origins"))
                .addOption(RunOptions.required("out", "dir", "output directory"))
                .addOption(RunOptions.required("include", "prefix", "include prefixes used for the runs (repeatable)"))
                .addOption(RunOptions.value("label", "name", "version label in the output (default: version)"))
                .addOption(RunOptions.value("commit", "sha", "commit id recorded in the output"));
    }

    @Override
    public int run(CommandLine cl) throws Exception {
        String label = cl.getOptionValue("label", "version");
        Path project = Main.abs(RunOptions.path(cl, "project-dir"));
        VersionGraph graph = GraphBuilder.build(label, cl.getOptionValue("commit"), project,
                RunOptions.values(cl, "include"), TestRunner.existingRuns(Main.abs(RunOptions.path(cl, "runs-dir"))),
                ProjectSources.scan(project));
        Path path = graph.write(Main.abs(RunOptions.path(cl, "out")));
        System.out.println("[dyncg] " + label + ": wrote " + path + " " + Json.compact(graph.stats()));
        return 0;
    }
}
