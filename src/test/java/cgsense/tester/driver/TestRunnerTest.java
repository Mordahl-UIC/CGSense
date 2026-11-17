package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestRunnerTest {
  @Test
  void roundsLikePython() {
    assertEquals(0.12, TestRunner.round(0.125, 2));
    assertEquals(2.5, TestRunner.round(2.5, 2));
    assertEquals(0.33, TestRunner.round(1.0 / 3, 2));
  }

  private static TestRunOptions options(BuildTool tool, String tests) {
    return new TestRunOptions(
        Path.of("agent.jar"),
        List.of("sample/"),
        List.of(),
        null,
        tests,
        null,
        null,
        false,
        60,
        "mvn",
        "gradle",
        tool);
  }

  @Test
  void buildsGradleCommandAndPrefersWrapper(@TempDir Path dir) throws IOException {
    Path wrapper = dir.resolve("gradlew");
    Files.writeString(wrapper, "#!/bin/sh\n");
    Path init = dir.resolve("init.gradle");
    List<String> command =
        TestRunner.gradleCommand(dir, options(BuildTool.GRADLE, "FooTest,Bar*"), init);
    assertEquals(
        List.of(
            "sh",
            wrapper.toString(),
            "--no-daemon",
            "--init-script",
            init.toString(),
            "-Dorg.gradle.configuration-cache=false",
            "-Dorg.gradle.unsafe.configuration-cache=false",
            "--continue",
            "--no-build-cache",
            "cleanTest",
            "test",
            "--tests",
            "FooTest",
            "--tests",
            "Bar*"),
        command);
  }
}
