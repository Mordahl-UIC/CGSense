package cgsense.app.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cgsense.app.DataDir;
import cgsense.app.Main;
import cgsense.io.JsonlWriter;
import cgsense.model.CommitPair;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesterCommandTest {
  @TempDir Path dir;

  private DataDir data() {
    return new DataDir(dir.resolve("data"));
  }

  private int run() {
    return Main.execute(new TesterCommand(data()), "--output", dir.resolve("out").toString());
  }

  private void writePairs(String... repos) throws IOException {
    Files.createDirectories(data().root());
    try (JsonlWriter writer = JsonlWriter.open(data().pairs())) {
      for (String repo : repos) {
        writer.write(new CommitPair(repo, "aaa", "bbb", 17, "maven", List.of()));
      }
    }
  }

  @Test
  void failsWithoutPairs() {
    assertEquals(1, run());
    assertFalse(Files.exists(dir.resolve("out")));
  }

  @Test
  void failsWhenAnyCloneIsMissing() throws IOException {
    writePairs("o/a", "o/b");
    Files.createDirectories(data().clone("o/a"));
    assertEquals(1, run());
    assertFalse(Files.exists(dir.resolve("out")));
  }

  @Test
  void createsOutputWhenPairsAndClonesExist() throws IOException {
    writePairs("o/a", "o/a", "o/b");
    Files.createDirectories(data().clone("o/a"));
    Files.createDirectories(data().clone("o/b"));
    assertEquals(0, run());
    assertTrue(Files.isDirectory(dir.resolve("out")));
  }
}
