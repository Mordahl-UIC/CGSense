package cgsense.tester.driver;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
/*TestRunOptions is a frozen bundle of everything needed to run the tests once. It can't change, so every repeat runs with exactly the same settings. with... makes a modified copy,
 and defaultAgentJar() finds the agent jar next to the driver.*/
 /*
 RunOptions.toOptions(includes)
   ├─ defaultAgentJar()                 → .../agent/target/dyncg-agent.jar
   └─ new TestRunOptions(...)           (buildTool = AUTO)
         ▼
VersionRunner.run
   └─ options.withBuildTool(MAVEN)      → new copy (buildTool = MAVEN)
         ▼
TestRunner.runTests(..., options)       → mvn -B test ... with -javaagent:dyncg-agent.jar=include=demo/,...,flush=60
*/

/** How to run a project's tests under the agent. Immutable. */
public final class TestRunOptions {
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
    final int flushSeconds;
    final String mvn;
    final String gradle;
    final BuildTool buildTool;

    TestRunOptions(Path agentJar, List<String> includes, List<String> excludes, Path javaHome, String tests,
            Integer testTimeoutSeconds, Integer timeoutSeconds, boolean offline,
            int flushSeconds, String mvn, String gradle,
            BuildTool buildTool) {
        this.agentJar = agentJar;
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.javaHome = javaHome;
        this.tests = tests;
        this.testTimeoutSeconds = testTimeoutSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.offline = offline;
        this.flushSeconds = flushSeconds;
        this.mvn = mvn;
        this.gradle = gradle;
        this.buildTool = buildTool;
    }

    /** Returns a new TestRunOptions with the given includes. */
    TestRunOptions withIncludes(List<String> newIncludes) {
        return new TestRunOptions(agentJar, newIncludes, excludes, javaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, flushSeconds, mvn, gradle, buildTool);
    }
//    TestRunOptions withExcludes(List<String> newExcludes) {
    TestRunOptions withJavaHome(Path newJavaHome) {
        return new TestRunOptions(agentJar, includes, excludes, newJavaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, flushSeconds, mvn, gradle, buildTool);
    }

    TestRunOptions withBuildTool(BuildTool newBuildTool) {//Returns a new TestRunOptions with the given build tool. The build tool is used to run the tests, and can be auto, maven or gradle.
        return new TestRunOptions(agentJar, includes, excludes, javaHome, tests, testTimeoutSeconds,
                timeoutSeconds, offline, flushSeconds, mvn, gradle, newBuildTool);
    }

    /** Returns the default path to the dyncg-agent.jar file. */
    static Path defaultAgentJar() {
        String override = System.getProperty("cgsense.agent.jar");
        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        try {
            Path location = Paths.get(TestRunOptions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.getParent().resolve("cgsense-agent.jar");
        } catch (URISyntaxException | RuntimeException e) {
            return Paths.get("target/cgsense-agent.jar").toAbsolutePath();
        }
    }
}
