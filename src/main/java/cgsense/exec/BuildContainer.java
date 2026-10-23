package cgsense.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BuildContainer implements AutoCloseable {

  private static final String IMAGE = "cgsense-build";
  private final String name;

  private static final int[] JDKS = { 26, 25, 21, 17, 11, 8 };

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
        IMAGE, "sleep", "infinity");
    if (code != 0) {
      throw new IOException("docker run failed");
    }

    if (sh("git clone -q /clones/" + slug + " /work/repo") != 0) {
      throw new IOException("git clone inside container fialed for " + slug);
    }
  }

  static String script(String sha, int jdk) {
    return BUILD_SCRIPT.replace("{sha}", sha).replace("{jdk}", Integer.toString(jdk));
  }

  public boolean build(String sha, int jdk) throws IOException {
    return sh(script(sha, jdk)) == 0;
  }

  public int buildAnyJdk(String sha) throws IOException {
    for (int jdk : JDKS) {
      if (build(sha, jdk)) {
        return jdk;
      }
    }

    return -1;
  }

  public boolean buildPair(String parentSha, String childSha) throws IOException {
    int jdk = buildAnyJdk(parentSha);
    if (jdk < 0) {
      return false;
    }
    return build(childSha, jdk);
  }

  private int sh(String script) throws IOException {
    return docker("exec", name, "bash", "-c", script);
  }

  @Override
  public void close() throws IOException {
    docker("rm", "-f", name);
  }

  public int docker(String... args) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    CommandResult result = CommandRunner.run(cmd, null);
    System.out.println(result.output());
    return result.exitCode();
  }
}
