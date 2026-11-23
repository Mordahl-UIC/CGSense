package cgsense.repo;

import static cgsense.exec.Container.quote;

import cgsense.app.Log;
import cgsense.exec.CommandResult;
import cgsense.exec.Container;
import cgsense.exec.Containers;
import cgsense.model.BuildTool;
import cgsense.model.Commit;
import cgsense.tester.driver.FileUtil;
import cgsense.tester.driver.RunResult;
import cgsense.tester.driver.TestRunOptions;
import cgsense.tester.driver.TestRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A {@link Repo} in a container made by {@link Containers#repo}. The bare clone at {@link
 * Containers#REPO_MIRROR} is only read;
 *
 * All work is a bash
 *
 * script sent with {@link Container#sh}, and files come back with {@link Container#copyOut}.
 *
 * The container belongs to the caller, who closes it.
 *
 * Running tests needs the container to be started {@link #withTools}.
 */
public final class ContainerRepo implements Repo {
  private static final Logger LOG = Log.get(ContainerRepo.class);

  private static final String BUILD_TIMEOUT = "30m";

  private static final String TOOLS = "/opt/cgsense";
  private static final String AGENT_JAR = TOOLS + "/cgsense-agent.jar";
  private static final String GRADLE_LISTENER = TOOLS + "/" + TestRunner.GRADLE_LISTENER;

  /** Where a test run writes; copied back to the run's runDir. Runs are sequential. */
  private static final String OUT = "/out/run";

  /** Exit codes of timeout(1): the command was sent TERM, then KILL. */
  private static final int TIMED_OUT = 124;

  private static final int KILLED = 137;

  private static final String CHECKOUT_SCRIPT =
      """
      set -e
      rm -rf {dir}
      git clone -q --shared --no-checkout {mirror} {dir}
      git -C {dir} checkout -q -f {sha}
      """;

  /** Starts from a clean tree, so nothing compiled on another JDK is left over. */
  private static final String BUILD_SCRIPT =
      """
      set -e
      cd {dir}
      git clean -q -fdx
      [ -x /opt/jdk/{jdk}/bin/java ] || { echo "cgsense: JDK {jdk} missing from image"; exit 127; }
      export JAVA_HOME=/opt/jdk/{jdk} PATH=/opt/jdk/{jdk}/bin:$PATH
      timeout {timeout} {build}
      """;

  private static final String GRADLE_BUILD =
      "bash ./gradlew --no-daemon"
          + " -Porg.gradle.java.installations.paths=/opt/jdk/8,/opt/jdk/11,/opt/jdk/17,/opt/jdk/21"
          + " testClasses";

  private static final String MAVEN_BUILD = "mvn -B test-compile";

  private static final String TEST_SCRIPT =
      """
      set -e
      rm -rf {out}
      [ -f {agentJar} ] || { echo "cgsense: no {agentJar}; start the container withTools"; exit 127; }
      cd {dir}
      mkdir -p {out}/dumps {out}/surefire-reports
      reports() {
        find . \\( -name .git -o -name .gradle \\) -prune -o -type d \\( \\
          -path '*/target/surefire-reports' -o -path '*/target/failsafe-reports' \\
          -o -path '*/build/test-results' \\) -prune "$@"
      }
      reports -exec rm -rf {} +
      printf '%s\\n\\n' {header} > {out}/build.log
      export JAVA_HOME={javaHome} PATH={javaHome}/bin:$PATH
      export JAVA_TOOL_OPTIONS={agent}
      export DYNCG_GRADLE_TEST_LOG={out}/{testLog}
      set +e
      {timeout}{command} >> {out}/build.log 2>&1
      code=$?
      reports -exec cp -r --parents {} {out}/surefire-reports/ \\;
      exit $code
      """;

  private final Container box;

  /** SHAs checked out and not yet closed; two checkouts of one SHA would share a directory. */
  private final Set<String> open = new HashSet<>();

  /**
   * Build outcome per "sha@jdk". It outlives checkouts: adjacent pairs share commits, so this
   * avoids rebuilding them.
   */
  private final Map<String, Boolean> built = new HashMap<>();

  public ContainerRepo(Container box) {
    this.box = box;
  }

  /** Adds what running tests needs to a container: the agent and the Gradle test listener. */
  public static Container.Builder withTools(Container.Builder container, Path agentJar)
      throws IOException {
    byte[] listener;
    try (InputStream in =
        ContainerRepo.class.getResourceAsStream("/cgsense/tester/test-listener.gradle")) {
      listener = in.readAllBytes();
    }
    return container.copyIn(agentJar, AGENT_JAR).write(GRADLE_LISTENER, listener);
  }

  @Override
  public Checkout checkout(Commit commit) throws IOException {
    String sha = commit.sha();
    if (!open.add(sha)) {
      throw new IllegalStateException(sha + " is already checked out in " + box.name());
    }
    String dir = "/work/" + sha;
    CommandResult r =
        box.sh(
            CHECKOUT_SCRIPT
                .replace("{dir}", quote(dir))
                .replace("{mirror}", Containers.REPO_MIRROR)
                .replace("{sha}", quote(sha)));
    if (!r.ok()) {
      open.remove(sha);
      throw new IOException(
          String.format("checkout of %s in %s failed: %s", sha, box.name(), r.output()));
    }
    return new ContainerCheckout(dir, commit);
  }

  /** A checkout at /work/{sha} in the container. */
  private final class ContainerCheckout implements Checkout {
    private final Commit commit;
    private final String dir;
    private Integer jdk;

    /** Where {@link #copyTo} put the checkout on this machine. */
    private Path host;

    ContainerCheckout(String dir, Commit commit) {
      this.dir = dir;
      this.commit = commit;
    }

    @Override
    public Commit commit() {
      return this.commit;
    }

    @Override
    public boolean build(int jdk) throws IOException {
      String key = this.commit.sha() + "@" + jdk;
      Boolean cached = built.get(key);
      if (cached != null) {
        LOG.fine(
            String.format(
                "%s JDK %d cached: %s", this.commit.sha(), jdk, cached ? "OK" : "FAILED"));
      } else {
        cached = compile(jdk);
        built.put(key, cached);
      }
      if (cached) {
        this.jdk = jdk;
      }
      return cached;
    }

    private boolean compile(int jdk) throws IOException {
      LOG.info(String.format("Building %s on JDK %d", this.commit.sha(), jdk));
      CommandResult r = box.sh(buildScript(dir, jdk, this.commit.buildTool()));

      String tag = String.format("%s JDK %d", this.commit.sha(), jdk);
      String took = String.format("%.1fs", r.elapsedTime() / 1e9);
      if (r.exitCode() == 127) {
        LOG.severe(String.format("%s: exit 127 in %s%n%s", tag, box.name(), r.output()));
        throw new IOException("Image is missing a tool or JDK");
      }

      boolean ok = r.exitCode() == 0;
      if (ok) {
        LOG.info(String.format("%s OK (%s)", tag, took));
      } else {
        LOG.info(
            String.format(
                "%s FAILED exit %d (%s); output:%n%s", tag, r.exitCode(), took, r.output()));
        if (LOG.isLoggable(Level.FINE)) {
          LOG.fine(String.format("%s full output:%n%s", tag, r.output()));
        }
      }
      return ok;
    }

    @Override
    public Path copyTo(Path host) throws IOException {
      box.copyOut(dir, host);
      this.host = FileUtil.abs(host);
      return this.host;
    }

    @Override
    public Path dir() {
      if (host == null) {
        throw new IllegalStateException(
            this.commit.sha() + " is only in the container; copyTo first");
      }
      return host;
    }

    @Override
    public RunResult runTests(Path runDir, int index, TestRunOptions options) throws IOException {
      if (jdk == null) {
        throw new IllegalStateException(this.commit.sha() + " has not been built; build it first");
      }
      Path hostDir = dir();

      // the build step only resolved plugins up to test-compile, so surefire and the
      // test runtime may still be missing
      options = options.online();

      // Built against the host copy (which decides e.g. whether gradlew exists), then
      // moved over.
      List<String> command =
          TestRunner.buildCommand(
                  hostDir, this.commit.buildTool(), options, Path.of(GRADLE_LISTENER))
              .stream()
              .map(arg -> toContainer(arg, hostDir, dir))
              .toList();
      String agent = TestRunner.agentOption(options, AGENT_JAR, OUT + "/dumps");
      Integer timeoutSeconds = options.timeoutSeconds();

      long started = System.nanoTime();
      CommandResult r =
          box.sh(testScript(dir, Containers.jdkHome(jdk), agent, command, timeoutSeconds));
      long elapsed = System.nanoTime() - started;
      if (r.exitCode() == -1) {
        throw new IOException("could not run tests in " + box.name() + ": " + r.output());
      }

      box.copyOut(OUT, runDir);
      if (!Files.isRegularFile(runDir.resolve("build.log"))) {
        throw new IOException("test script failed in " + box.name() + ": " + r.output());
      }

      boolean timedOut =
          timeoutSeconds != null && (r.exitCode() == TIMED_OUT || r.exitCode() == KILLED);
      return TestRunner.collect(
          index,
          runDir,
          command,
          timedOut ? null : r.exitCode(),
          timedOut,
          elapsed,
          this.commit.buildTool());
    }

    /** Removes the checkout from the container; a copy made with copyTo is the caller's. */
    @Override
    public void close() throws IOException {
      try {
        box.sh("rm -rf " + quote(dir));
      } finally {
        open.remove(this.commit.sha());
      }
    }
  }

  static String buildScript(String dir, int jdk, BuildTool tool) {
    return BUILD_SCRIPT
        .replace("{build}", tool == BuildTool.GRADLE ? GRADLE_BUILD : MAVEN_BUILD)
        .replace("{dir}", quote(dir))
        .replace("{jdk}", Integer.toString(jdk))
        .replace("{timeout}", BUILD_TIMEOUT);
  }

  /**
   * The test script for one run. Every value is quoted: the header holds a newline, which unquoted
   * would end the printf line and run "$ ..." as a command.
   */
  static String testScript(
      String dir, String javaHome, String agent, List<String> command, Integer timeoutSeconds) {
    String header =
        "$ cd " + dir + "\n$ JAVA_TOOL_OPTIONS='" + agent + "' " + String.join(" ", command);
    String timeout =
        timeoutSeconds == null ? "" : "timeout --kill-after=30 " + timeoutSeconds + " ";
    return TEST_SCRIPT
        .replace("{dir}", quote(dir))
        .replace("{out}", OUT)
        .replace("{agentJar}", AGENT_JAR)
        .replace("{testLog}", TestRunner.GRADLE_TEST_LOG)
        .replace("{header}", quote(header))
        .replace("{javaHome}", quote(javaHome))
        .replace("{agent}", quote(agent))
        .replace("{timeout}", timeout)
        .replace(
            "{command}", command.stream().map(Container::quote).collect(Collectors.joining(" ")));
  }

  /** Rewrites a path below {@code hostDir} to the same path below {@code dir}. */
  private static String toContainer(String arg, Path hostDir, String dir) {
    String prefix = hostDir + "/";
    return arg.startsWith(prefix) ? dir + "/" + arg.substring(prefix.length()) : arg;
  }
}
