package dyncg.driver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/** Command line: {@code java -jar dyncg.jar {pair,version,rebuild} ...}. */
/*The current project supports three commands:
 * - `pair`: Generate call graphs for a commit pair.
 * - `version`: Generate a call graph for a single version.
 * - `rebuild`: Rebuild the call graphs.
 */
@Command(name = "dyncg", mixinStandardHelpOptions = true, version = "dyncg " + Main.VERSION,
        description = "Dynamic call graphs for the before and after versions of a commit pair.",
        subcommands = {Main.Pair.class, Main.Version.class, Main.Rebuild.class})
public final class Main implements Runnable {
    public static final String VERSION = "0.1.0";

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "missing command: pair, version or rebuild");
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Runs the command line and returns the exit code (0 = success). */
    public static int execute(String... args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((error, command, parsed) -> {
            if (error instanceof UsageException) {
                command.getErr().println("dyncg: " + error.getMessage());
                return 2;
            }
            error.printStackTrace(command.getErr());
            return 1;
        });
        return cli.execute(args);
    }

    static Path abs(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    @Command(name = "pair", mixinStandardHelpOptions = true,
            description = "Call graphs for the before and after commit of a pair.")
    static final class Pair implements Callable<Integer> {
        @Option(names = "--repo", required = true, description = "git repository (bare or not)")
        Path repo;

        @Option(names = "--before", required = true, description = "before (parent) commit")
        String before;

        @Option(names = "--after", required = true, description = "after (fix) commit")
        String after;

        @Option(names = "--out", required = true, description = "output directory")
        Path out;

        @Option(names = "--before-java-home", description = "JDK for the before version (overrides --java-home)")
        Path beforeJavaHome;

        @Option(names = "--after-java-home", description = "JDK for the after version (overrides --java-home)")
        Path afterJavaHome;

        @Option(names = "--workdir", description = "where worktrees are created (default: <out>/worktrees)")
        Path workdir;

        @Option(names = "--keep-worktrees", description = "keep the checkouts for inspection")
        boolean keepWorktrees;

        @Mixin
        RunOptions run;

        @Override
        public Integer call() throws Exception {
            Path outDir = abs(out);
            Map<String, Object> manifest = PairRunner.runPair(abs(repo), before, after, outDir, run.repeats,
                    run.toOptions(run.includes), abs(beforeJavaHome), abs(afterJavaHome),
                    workdir != null ? abs(workdir) : outDir.resolve("worktrees"), keepWorktrees, run.includes);
            System.out.println(Json.pretty(manifest));
            return 0;
        }
    }

    @Command(name = "version", mixinStandardHelpOptions = true,
            description = "Call graph for one already checked-out version.")
    static final class Version implements Callable<Integer> {
        @Option(names = "--project-dir", required = true)
        Path projectDir;

        @Option(names = "--out", required = true)
        Path out;

        @Option(names = "--label", defaultValue = "version")
        String label;

        @Option(names = "--commit", description = "commit id recorded in the output")
        String commit;

        @Mixin
        RunOptions run;

        @Override
        public Integer call() throws Exception {
            Path project = abs(projectDir);
            ProjectSources sources = ProjectSources.scan(project);
            List<String> includes = run.includes.isEmpty() ? sources.detectIncludePrefixes() : run.includes;
            if (includes.isEmpty()) {
                throw new UsageException("could not detect project packages; pass --include");
            }
            PairRunner.runVersion(label, commit, project, abs(out), run.repeats, run.toOptions(includes), sources);
            return 0;
        }
    }

    @Command(name = "rebuild", mixinStandardHelpOptions = true,
            description = "Rebuild callgraph.json from the raw outputs of earlier test runs, without running tests.")
    static final class Rebuild implements Callable<Integer> {
        @Option(names = "--runs-dir", required = true,
                description = "directory holding <i>/dumps and <i>/surefire-reports, e.g. <out>/before/runs")
        Path runsDir;

        @Option(names = "--project-dir", required = true, description = "source checkout, used for node origins")
        Path projectDir;

        @Option(names = "--out", required = true)
        Path out;

        @Option(names = "--include", required = true, description = "include prefixes used for the runs")
        List<String> includes = new ArrayList<>();

        @Option(names = "--label", defaultValue = "version")
        String label;

        @Option(names = "--commit")
        String commit;

        @Override
        public Integer call() throws Exception {
            Path project = abs(projectDir);
            VersionGraph graph = GraphBuilder.build(label, commit, project, includes,
                    TestRunner.existingRuns(abs(runsDir)), ProjectSources.scan(project));
            Path path = graph.write(abs(out));
            System.out.println("[dyncg] " + label + ": wrote " + path + " " + Json.compact(graph.stats()));
            return 0;
        }
    }
}
