package cgsense.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BuildContainer implements AutoCloseable {

  private static final String IMAGE = "cgsense-build";
  private final String name;

  private static final int[] JDKS = { 26, 25, 21, 17, 11, 8 };
  private int JDK_IDX = 0;

  private static final String BUILD_SCRIPT = """
          set -e
          cd /work/repo
          git checkout -q -f {sha}
          git clean -q -ffdx
          [ -x /opt/jdk/{jdk}/bin/java ] || { echo "cgsense: JDK {jdk} missing from image"; exit 127; }
          echo "cgsense: building {sha} on JDK {jdk}"
          export JAVA_HOME=/opt/jdk/{jdk} PATH=/opt/jdk/{jdk}/bin:$PATH
          if [ -f gradlew ]; then
            bash ./gradlew --no-daemon \\
              -Porg.gradle.java.installations.paths=/opt/jdk/8,/opt/jdk/11,/opt/jdk/17,/opt/jdk/21 \\
              testClasses
          else
            mvn -B test-compile
          fi

      """;

  public BuildContainer(Path clonesDir, String slug) throws IOException {
    this.name = "cgsense-" + slug;
    docker("rm", "-f", name);

    int code = docker("run", "-d", "--name", name,
        "-v", clonesDir.toAbsolutePath() + ":/clones:ro",
        IMAGE, "sleep", "infinity").exitCode();
    if (code != 0) {
      throw new IOException("docker run failed");
    }

    if (sh("git clone -q /clones/" + slug + " /work/repo").exitCode() != 0) {
      close();
      throw new IOException("git clone inside container fialed for " + slug);
    }
  }

  static String script(String sha, int jdk) {
    return BUILD_SCRIPT
        .replace("{sha}", sha)
        .replace("{jdk}", Integer.toString(jdk));
  }

  public boolean build(String sha) throws IOException {
    int jdk = JDKS[JDK_IDX];
    CommandResult r = sh(script(sha, jdk));

    String tag = String.format("  %s JDK %d", sha, jdk);
    if (r.exitCode() == 0) {
      System.out.println(tag + "  OK");
      return true;
    }

    System.out.printf("%s  exit %d %n",
        tag, r.exitCode());
    System.out.println(r.output());
    if (r.exitCode() == 127) {
      throw new IOException("Image is missing a tool or JDK");
    }
    return false;
  }

  public boolean buildPair(String parentSha, String childSha) throws IOException {
    for (int tries = 0; tries < JDKS.length; tries++) {
      if (build(parentSha)) {
        return build(childSha);
      }
      JDK_IDX = (JDK_IDX + 1) % JDKS.length;
    }
    return false;
  }

  public int jdk() {
    return JDKS[JDK_IDX];
  }

  private CommandResult sh(String script) throws IOException {
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
