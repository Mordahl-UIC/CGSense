package cgsense.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cgsense.model.BuildTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerRepoTest {
  /**
   * Runs the real script with local bash, its container paths moved into a temp directory. An
   * unquoted value (the header holds a newline) used to end the printf line and fail with exit 127.
   */
  @Test
  void scriptRunsTheCommandAndLogsIt(@TempDir Path tmp) throws Exception {
    Path project = Files.createDirectories(tmp.resolve("it's a project"));
    Path out = tmp.resolve("out");
    Path agent = Files.createFile(tmp.resolve("agent.jar"));

    String script =
        ContainerRepo.testScript(
                project.toString(),
                "/no/jdk",
                "-javaagent:x.jar=include=a/:b/,out=y",
                List.of("echo", "ran with", "$HOME"),
                null)
            .replace("/out/run", out.toString())
            .replace("/opt/cgsense/cgsense-agent.jar", agent.toString());

    Process p = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);

    List<String> log = Files.readAllLines(out.resolve("build.log"));
    assertEquals("$ cd " + project, log.get(0));
    assertTrue(log.get(1).startsWith("$ JAVA_TOOL_OPTIONS='-javaagent:x.jar"), log.get(1));
    // Arguments reach the command as given: not split, not expanded.
    assertTrue(log.contains("ran with $HOME"), log.toString());
  }

  /**
   * One checkout is built on several JDKs, so each build must start without the last one's output.
   */
  @Test
  void buildCleansBeforeBuilding() {
    String maven = ContainerRepo.buildScript("/work/abc", 17, BuildTool.MAVEN);
    int clean = maven.indexOf("git clean -q -fdx");
    assertTrue(clean >= 0, maven);
    assertTrue(clean < maven.indexOf("mvn -B test-compile"), maven);
    assertTrue(maven.contains("/opt/jdk/17/bin/java"), maven);

    String gradle = ContainerRepo.buildScript("/work/abc", 17, BuildTool.GRADLE);
    assertTrue(gradle.indexOf("git clean -q -fdx") < gradle.indexOf("./gradlew"), gradle);
  }

  /** The build uses the pair's tool; the script never guesses from the files. */
  @Test
  void buildRunsOnlyThePairsTool() {
    String maven = ContainerRepo.buildScript("/work/abc", 17, BuildTool.MAVEN);
    assertFalse(maven.contains("gradlew"), maven);
    String gradle = ContainerRepo.buildScript("/work/abc", 17, BuildTool.GRADLE);
    assertFalse(gradle.contains("mvn"), gradle);
  }
}
