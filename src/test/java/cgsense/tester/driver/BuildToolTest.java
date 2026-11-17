package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolTest {
  @Test
  void detectsBuildTools(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("pom.xml"), "<project/>");
    assertEquals(BuildTool.MAVEN, BuildTool.AUTO.resolve(dir));
    Files.delete(dir.resolve("pom.xml"));
    Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
    assertEquals(BuildTool.GRADLE, BuildTool.AUTO.resolve(dir));
    Files.writeString(dir.resolve("pom.xml"), "<project/>");
    assertThrows(UsageException.class, () -> BuildTool.AUTO.resolve(dir));
  }
}
