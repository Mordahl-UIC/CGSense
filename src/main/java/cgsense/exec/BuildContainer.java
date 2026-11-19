package cgsense.exec;

import cgsense.app.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One Docker container per repository. The host's bare clone is copied into the container once at
 * /mirror with docker cp. the network. Each build gets a fresh checkout under /work; /mirror is
 * only read.
 */
public class BuildContainer implements AutoCloseable {
  private static final Logger LOG = Log.get(BuildContainer.class);

  /** Lines of a failed build's output shown at INFO; the full output is logged at FINE. */
  private static final int FAILURE_TAIL_LINES = 20;

  private static final String IMAGE = "cgsense-build";
  private static final String M2_VOLUME = "cgsense-m2";
  private static final String GRADLE_VOLUME = "cgsense-gradle";
  private static final String BUILD_TIMEOUT = "30m";

  private static final int[] JDKS = {26, 25, 21, 17, 11, 8};

  private final String name;
  private int checkouts = 0;

  /** Index into JDKS of the last JDK that built a pair; tried first for the next pair. */
  private int preferredJdk = 0;

  /** Build outcome per "sha@jdk". Adjacent pairs share commits, so this avoids rebuilding them. */
  private final Map<String, Boolean> built = new HashMap<>();

  private static final String BUILD_SCRIPT =
      """
                set -e
                dir=/work/{dir}
                rm -rf "$dir"
                git clone -q --shared --no-checkout /mirror "$dir"
                trap 'rm -rf "$dir"' EXIT
                cd "$dir"
                git checkout -q -f {sha}
                [ -x /opt/jdk/{jdk}/bin/java ] || { echo "cgsense: JDK {jdk} missing from image"; exit 127; }
                echo "cgsense: building {sha} on JDK {jdk}"
                export JAVA_HOME=/opt/jdk/{jdk} PATH=/opt/jdk/{jdk}/bin:$PATH
                if [ -f gradlew ]; then
                  timeout {timeout} bash ./gradlew --no-daemon \\
                    -Porg.gradle.java.installations.paths=/opt/jdk/8,/opt/jdk/11,/opt/jdk/17,/opt/jdk/21 \\
                    testClasses
                else
                  timeout {timeout} mvn -B test-compile
                fi

            """;

  /**
   * @param slug repository slug, used for the container name
   * @param mirror host path of the repository's bare clone
   */
  public BuildContainer(String slug, Path mirror) throws IOException {
    this.name = "cgsense-" + slug;
    docker("rm", "-f", name);
    LOG.info(String.format("Starting container %s from %s", name, IMAGE));

    CommandResult r =
        docker(
            "run",
            "-d",
            "--name",
            name,
            "-v",
            M2_VOLUME + ":/root/.m2",
            "-v",
            GRADLE_VOLUME + ":/root/.gradle",
            IMAGE,
            "sleep",
            "infinity");
    if (r.exitCode() != 0) {
      throw new IOException(String.format("docker run failed: %s", r.output()));
    }
    CommandResult cp = docker("cp", mirror.toAbsolutePath().toString(), name + ":/mirror");
    if (cp.exitCode() != 0) {
      throw new IOException(String.format("docker cp of %s failed: %s", mirror, cp.output()));
    }
    LOG.info(String.format("Copied %s into %s (%.1fs)", mirror, name, cp.elapsedTime() / 1e9));
    LOG.info("Container " + name + " is up");
  }

  static String script(String dir, String sha, int jdk) {
    return BUILD_SCRIPT
        .replace("{dir}", dir)
        .replace("{sha}", sha)
        .replace("{jdk}", Integer.toString(jdk))
        .replace("{timeout}", BUILD_TIMEOUT);
  }

  /** Builds {@code sha} on {@code jdk} in a fresh checkout and reports whether it compiled. */
  public boolean build(String sha, int jdk) throws IOException {
    String key = sha + "@" + jdk;
    Boolean cached = built.get(key);
    if (cached != null) {
      LOG.fine(String.format("%s JDK %d cached: %s", shortSha(sha), jdk, cached ? "OK" : "FAILED"));
      return cached;
    }

    String dir = sha + "-" + ++checkouts;
    LOG.info(String.format("Building %s on JDK %d", shortSha(sha), jdk));
    CommandResult r = sh(script(dir, sha, jdk));

    String tag = String.format("%s JDK %d", shortSha(sha), jdk);
    String took = String.format("%.1fs", r.elapsedTime() / 1e9);
    if (r.exitCode() == 127) {
      LOG.severe(String.format("%s: exit 127 in %s%n%s", tag, name, r.output()));
      throw new IOException("Image is missing a tool or JDK");
    }

    boolean ok = r.exitCode() == 0;
    if (ok) {
      LOG.info(String.format("%s OK (%s)", tag, took));
    } else {
      LOG.info(
          String.format(
              "%s FAILED exit %d (%s); last lines:%n%s",
              tag, r.exitCode(), took, tail(r.output(), FAILURE_TAIL_LINES)));
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine(String.format("%s full output:%n%s", tag, r.output()));
      }
    }
    built.put(key, ok);
    return ok;
  }

  /**
   * Finds a JDK that builds both commits, starting from the one that worked last.
   *
   * @return the JDK version, or -1 if no JDK builds both
   */
  public int buildPair(String parentSha, String childSha) throws IOException {
    int start = preferredJdk;
    for (int tries = 0; tries < JDKS.length; tries++) {
      int idx = (start + tries) % JDKS.length;
      int jdk = JDKS[idx];
      if (build(parentSha, jdk) && build(childSha, jdk)) {
        preferredJdk = idx;
        return jdk;
      }
      if (tries + 1 < JDKS.length) {
        LOG.info(
            String.format(
                "%s..%s does not build on JDK %d, trying JDK %d",
                shortSha(parentSha),
                shortSha(childSha),
                jdk,
                JDKS[(start + tries + 1) % JDKS.length]));
      }
    }
    return -1;
  }

  /** Runs a shell script in the container. */
  public CommandResult sh(String script) throws IOException {
    return docker("exec", name, "bash", "-c", script);
  }

  @Override
  public void close() throws IOException {
    LOG.info("Removing container " + name);
    docker("rm", "-f", name);
  }

  private static String shortSha(String sha) {
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }

  private static String tail(String output, int lines) {
    String[] all = output.split("\\R");
    int from = Math.max(0, all.length - lines);
    return String.join(System.lineSeparator(), List.of(all).subList(from, all.length));
  }

  public CommandResult docker(String... args) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    if (LOG.isLoggable(Level.FINER)) {
      LOG.finer(String.join(" ", cmd));
    }
    CommandResult r = CommandRunner.run(cmd, null);
    if (r.exitCode() == -1) {
      LOG.warning(String.format("Could not run docker: %s", r.output()));
    }
    return r;
  }
}
