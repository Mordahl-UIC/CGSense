package cgsense.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cgsense.app.cmd.CommitMinerCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @Test
  void commandLineExitCodes() {
    // General help and program version still work.
    assertEquals(0, Main.execute("--help"));
    assertEquals(0, Main.execute("--version"));

    assertEquals(0, Main.execute("version", "--help"));

    // Removed commands must be rejected.
    assertEquals(2, Main.execute("pair", "--help"));
    assertEquals(2, Main.execute("rebuild", "--help"));

    // Missing or unknown commands are rejected.
    assertEquals(2, Main.execute());
    assertEquals(2, Main.execute("nope"));

    // --project-dir is required.
    assertEquals(2, Main.execute("version", "--out", "o"));

    // Unknown options are rejected.
    assertEquals(
        2, Main.execute("version", "--project-dir", "p", "--out", "o", "--no-such-option"));

    // Partial option-name matching is disabled.
    assertEquals(2, Main.execute("version", "--project-dir", "p", "--out", "o", "--max", "3"));

    // Extra positional arguments are rejected.
    assertEquals(2, Main.execute("version", "--project-dir", "p", "--out", "o", "extra"));
  }

  @Test
  void everyRegisteredCommandHasHelp() {
    assertEquals(0, Main.execute("discover", "--help"));
    assertEquals(0, Main.execute("mine-pairs", "--help"));
    assertEquals(0, Main.execute("tester", "--help"));
    assertEquals(0, Main.execute("version", "--help"));
  }

  @Test
  void missingRequiredOptionsAreUsageErrors() {
    assertEquals(2, Main.execute("discover"));
    assertEquals(2, Main.execute("tester"));
  }

  @Test
  void pipelinePathsAreNotOptions() {
    assertEquals(2, Main.execute("discover", "--limit", "1", "--output", "o"));
    assertEquals(2, Main.execute("mine-pairs", "--output", "o"));
    assertEquals(2, Main.execute("mine-pairs", "--repos", "r"));
    assertEquals(2, Main.execute("tester", "--output", "o", "--pairs", "p"));
  }

  @Test
  void minePairsFailsWhenReposFileIsMissing(@TempDir Path dir) {
    assertEquals(1, Main.execute(new CommitMinerCommand(new DataDir(dir))));
  }
}
