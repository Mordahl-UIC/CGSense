package dyncg.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Option;

/** Test-execution options shared by the {@code pair} and {@code version} commands. */
final class RunOptions {
    @Option(names = "--include", description = "package prefix to instrument, e.g. org/jsoup/ (repeatable; default: detected)")
    List<String> includes = new ArrayList<>();

    @Option(names = "--exclude", description = "package prefix to skip (repeatable)")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--tests", description = "test class/pattern selection, comma-separated, applied to both versions")
    String tests;

    @Option(names = "--repeats", defaultValue = "2", description = "test runs per version (default 2)")
    int repeats;

    @Option(names = "--java-home", description = "JDK used to run the build and tests")
    Path javaHome;

    @Option(names = "--test-timeout", description = "seconds per forked test JVM (surefire.timeout)")
    Integer testTimeout;

    @Option(names = "--timeout", description = "seconds per build invocation")
    Integer timeout;

    @Option(names = "--offline", description = "run the build tool offline")
    boolean offline;

    @Option(names = "--build-tool", defaultValue = "auto", description = "auto, maven or gradle (default: auto)")
    String buildTool;

    @Option(names = "--maven-arg", description = "extra Maven argument (repeatable; use --maven-arg=-X for dashes)")
    List<String> mavenArgs = new ArrayList<>();

    @Option(names = "--gradle-arg", description = "extra Gradle argument (repeatable; use --gradle-arg=-X for dashes)")
    List<String> gradleArgs = new ArrayList<>();

    @Option(names = "--flush", defaultValue = "60", description = "agent snapshot interval in seconds (0 = only at exit)")
    int flush;

    @Option(names = "--record-events", description = "also log individual invocations in order (see --max-events)")
    boolean recordEvents;

    @Option(names = "--max-events", defaultValue = "5000000",
            description = "with --record-events: invocations logged per test JVM before logging stops (default 5000000)")
    long maxEvents;

    @Option(names = "--agent-jar", description = "default: <project root>/agent/target/dyncg-agent.jar")
    Path agentJar;

    @Option(names = "--mvn", defaultValue = "mvn", description = "Maven executable")
    String mvn;

    @Option(names = "--gradle", defaultValue = "gradle", description = "Gradle executable when no gradlew exists")
    String gradle;

    TestRunOptions toOptions(List<String> includeList) {
        Path agent = agentJar != null ? Main.abs(agentJar) : TestRunOptions.defaultAgentJar();
        if (!Files.isRegularFile(agent)) {
            throw new UsageException("agent jar not found: " + agent + " (build it with: mvn -B package)");
        }
        if (repeats < 1) {
            throw new UsageException("--repeats must be at least 1");
        }
        if (maxEvents < 1 || maxEvents > Integer.MAX_VALUE) {
            throw new UsageException("--max-events must be between 1 and " + Integer.MAX_VALUE);
        }
        return new TestRunOptions(agent, includeList, excludes, Main.abs(javaHome), tests, testTimeout, timeout,
                offline, mavenArgs, gradleArgs, flush, recordEvents ? maxEvents : 0, mvn, gradle, BuildTool.parse(buildTool));
    }
}
