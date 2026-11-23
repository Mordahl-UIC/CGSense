package cgsense.tester.driver;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/*
 * TestRunOptions is a frozen bundle of everything needed to run the tests once
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

  TestRunOptions(
      Path agentJar,
      List<String> includes,
      List<String> excludes,
      Path javaHome,
      String tests,
      Integer testTimeoutSeconds,
      Integer timeoutSeconds,
      boolean offline,
      int flushSeconds,
      String mvn,
      String gradle) {
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
  }

  /** Seconds per build invocation; null = no limit. */
  public Integer timeoutSeconds() {
    return timeoutSeconds;
  }

  /** Returns a new TestRunOptions with the given includes. */
  TestRunOptions withIncludes(List<String> newIncludes) {
    return new TestRunOptions(
        agentJar,
        newIncludes,
        excludes,
        javaHome,
        tests,
        testTimeoutSeconds,
        timeoutSeconds,
        offline,
        flushSeconds,
        mvn,
        gradle);
  }

  //    TestRunOptions withExcludes(List<String> newExcludes) {
  TestRunOptions withJavaHome(Path newJavaHome) {
    return new TestRunOptions(
        agentJar,
        includes,
        excludes,
        newJavaHome,
        tests,
        testTimeoutSeconds,
        timeoutSeconds,
        offline,
        flushSeconds,
        mvn,
        gradle);
  }

  /** Returns a new TestRunOptions that lets the build tool download what its cache lacks. */
  public TestRunOptions online() {
    return new TestRunOptions(
        agentJar,
        includes,
        excludes,
        javaHome,
        tests,
        testTimeoutSeconds,
        timeoutSeconds,
        false,
        flushSeconds,
        mvn,
        gradle);
  }

  /** Returns the default path to the dyncg-agent.jar file. */
  static Path defaultAgentJar() {
    String override = System.getProperty("cgsense.agent.jar");
    if (override != null) {
      return Paths.get(override).toAbsolutePath().normalize();
    }
    try {
      Path location =
          Paths.get(
              TestRunOptions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return location.getParent().resolve("cgsense-agent.jar");
    } catch (URISyntaxException | RuntimeException e) {
      return Paths.get("target/cgsense-agent.jar").toAbsolutePath();
    }
  }
}
