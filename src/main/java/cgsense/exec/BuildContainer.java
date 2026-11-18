package cgsense.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One Docker container per repository. The host's bare clone is mounted read-only at /mirror and
 * the Maven/Gradle caches live on named volumes shared by every container, so nothing is cloned.
 * Each build gets a fresh checkout under /work.
 */
public class BuildContainer implements AutoCloseable {

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

    CommandResult r =
        docker(
            "run",
            "-d",
            "--name",
            name,
            "-v",
            mirror.toAbsolutePath() + ":/mirror:ro",
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
    if (cached != null) return cached;

    String dir = sha + "-" + ++checkouts;
    CommandResult r = sh(script(dir, sha, jdk));

    String tag = String.format("  %s %s JDK %d", name, sha, jdk);
    if (r.exitCode() == 127) {
      throw new IOException("Image is missing a tool or JDK");
    }
    boolean ok = r.exitCode() == 0;
    if (ok) {
      System.out.println(tag + "  OK");
    } else {
      System.out.printf("%s  exit %d %n%s%n", tag, r.exitCode(), r.output());
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
    }
    return -1;
  }

  /** Runs a shell script in the container. */
  public CommandResult sh(String script) throws IOException {
    return docker("exec", name, "bash", "-c", script);
  }

  @Override
  public void close() throws IOException {
    docker("rm", "-f", name);
  }

  public CommandResult docker(String... args) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    return CommandRunner.run(cmd, null);
  }
}
