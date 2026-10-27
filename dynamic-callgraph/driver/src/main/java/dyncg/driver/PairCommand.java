package dyncg.driver;

import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** {@code dyncg pair}: call graphs for the before and after commit of a pair. */
final class PairCommand implements CliCommand {
    @Override
    public String name() {
        return "pair";
    }

    @Override
    public Options options() {
        return RunOptions.addTo(new Options()
                .addOption(RunOptions.required("repo", "dir", "git repository (bare or not)"))
                .addOption(RunOptions.required("before", "commit", "before (parent) commit"))
                .addOption(RunOptions.required("after", "commit", "after (fix) commit"))
                .addOption(RunOptions.required("out", "dir", "output directory"))
                .addOption(RunOptions.value("before-java-home", "dir", "JDK for the before version (overrides --java-home)"))
                .addOption(RunOptions.value("after-java-home", "dir", "JDK for the after version (overrides --java-home)"))
                .addOption(RunOptions.value("workdir", "dir", "where worktrees are created (default: <out>/worktrees)"))
                .addOption(RunOptions.flag("keep-worktrees", "keep the checkouts for inspection")));
    }

    @Override
    public int run(CommandLine cl) throws Exception {
        RunOptions run = RunOptions.from(cl);
        Path outDir = Main.abs(RunOptions.path(cl, "out"));
        Path workdir = cl.hasOption("workdir") ? Main.abs(RunOptions.path(cl, "workdir")) : outDir.resolve("worktrees");
        Map<String, Object> manifest = PairRunner.runPair(Main.abs(RunOptions.path(cl, "repo")),
                cl.getOptionValue("before"), cl.getOptionValue("after"), outDir, run.repeats,
                run.toOptions(run.includes), Main.abs(RunOptions.path(cl, "before-java-home")),
                Main.abs(RunOptions.path(cl, "after-java-home")), workdir, cl.hasOption("keep-worktrees"),
                run.includes);
        System.out.println(Json.pretty(manifest));
        return 0;
    }
}
