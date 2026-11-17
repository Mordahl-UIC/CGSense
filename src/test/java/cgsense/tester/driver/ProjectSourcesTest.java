package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSourcesTest {
  private static ProjectSources sources(String... classes) {
    ProjectSources sources = new ProjectSources();
    for (String c : classes) {
      sources.origins.put(c, "main");
    }
    return sources;
  }

  @Test
  void detectsIncludePrefixes() {
    assertEquals(
        List.of("org/jsoup/"), sources("org.jsoup.A", "org.jsoup.nodes.B").detectIncludePrefixes());
    assertEquals(
        List.of("org/a/", "org/b/"), sources("org.a.X", "org.b.Y").detectIncludePrefixes());
    assertEquals(List.of("sample/"), sources("sample.A", "sample.B").detectIncludePrefixes());
    assertEquals(List.of("com/x/y/", "Foo"), sources("Foo", "com.x.y.Z").detectIncludePrefixes());
    assertEquals(List.of(), sources().detectIncludePrefixes());
  }

  @Test
  void scansSourcesAndOrigins(@TempDir Path dir) throws IOException {
    Path main = Files.createDirectories(dir.resolve("src/main/java/p"));
    Path test = Files.createDirectories(dir.resolve("src/test/java/p"));
    Path ignored = Files.createDirectories(dir.resolve("target/generated/p"));
    Files.writeString(main.resolve("A.java"), "// header\npackage p;\nclass A {}\n");
    Files.writeString(test.resolve("ATest.java"), "package p;\nclass ATest {}\n");
    Files.writeString(ignored.resolve("Gen.java"), "package p;\nclass Gen {}\n");
    ProjectSources sources = ProjectSources.scan(dir);
    assertEquals(Map.of("p.A", "main", "p.ATest", "test"), sources.origins);
    assertEquals("main", sources.originOf("p.A$Inner"));
    assertEquals("unknown", sources.originOf("q.B"));
  }
}
