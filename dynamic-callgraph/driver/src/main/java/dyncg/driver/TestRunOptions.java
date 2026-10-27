package dyncg.driver;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** How to run a project's tests under the agent. Immutable. */
final class TestRunOptions {
    final Path agentJar;
    final List<String> includes;
    final List<String> excludes;
    final Path javaHome;
    /** Passed to -Dtest; null runs the whole suite. */
    final String tests;
    /** Seconds per forked test JVM (surefire.timeout); null = no limit. */
    final Integer testTimeoutSeconds;
    /** Seconds per build invocation; null = no limit. */
    final Integer timeoutSeconds;
    final boolean offline;
    final List<String> mavenArgs;
    final List<String> gradleArgs;
    final int flushSeconds;
    /** Maximum individual invocations logged per test JVM; 0 = event log off. */
    final long maxEvents;
    final String mvn;
    final String gradle;
    final BuildTool buildTool;

    TestRunOptions(Path agentJar, List<String> includes, List<String> excludes, Path javaHome, String tests,
            Integer testTimeoutSeconds, Integer timeoutSeconds, boolean offline, List<String> mavenArgs,
            List<String> gradleArgs, int flushSeconds, long maxEvents, String mvn, String gradle,
            BuildTool buildTool) {
        this.agentJar = agentJar;
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.javaHome = javaHome;
        this.tests = tests;
        this.testTimeoutSeconds = testTimeoutSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.offline = offline;
        this.mavenArgs = List.copyOf(mavenArgs);
        this.gradleArgs = List.copyOf(gradleArgs);
        this.flushSeconds = flushSeconds;
        this.maxEvents = maxEvents;
        this.mvn = mvn;
        this.gradle = gradle;
        this.buildTool = buildTool;
    }

    TestRunOptions withIncludes(List<String> newIncludes) {
        return new TestRunOptions(agentJar, newIncludes, excludes, javaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, mavenArgs, gradleArgs, flushSeconds, maxEvents, mvn, gradle, buildTool);
    }

    TestRunOptions withJavaHome(Path newJavaHome) {
        return new TestRunOptions(agentJar, includes, excludes, newJavaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, mavenArgs, gradleArgs, flushSeconds, maxEvents, mvn, gradle, buildTool);
    }

    TestRunOptions withBuildTool(BuildTool newBuildTool) {
        return new TestRunOptions(agentJar, includes, excludes, javaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, mavenArgs, gradleArgs, flushSeconds, maxEvents, mvn, gradle, newBuildTool);
    }

    /**
     * The agent jar next to this driver: {@code driver/target/dyncg.jar} (or {@code driver/target/classes})
     * → {@code agent/target/dyncg-agent.jar}. The system property {@code dyncg.agent.jar} overrides it.
     */
    static Path defaultAgentJar() {
        String override = System.getProperty("dyncg.agent.jar");
        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        try {
            Path location = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.getParent().getParent().getParent().resolve("agent/target/dyncg-agent.jar");
        } catch (URISyntaxException | RuntimeException e) {
            return Paths.get("agent/target/dyncg-agent.jar").toAbsolutePath();
        }
    }
}
