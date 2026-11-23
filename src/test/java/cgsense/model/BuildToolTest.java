package cgsense.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BuildToolTest {
  @Test
  void parsesPairBuildTools() {
    assertEquals(BuildTool.MAVEN, BuildTool.parse("maven"));
    assertEquals(BuildTool.GRADLE, BuildTool.parse("GRADLE"));
    assertThrows(IllegalArgumentException.class, () -> BuildTool.parse("ant"));
    assertThrows(IllegalArgumentException.class, () -> BuildTool.parse(null));
  }
}
