package cgsense.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommitPairTest {
  @Test
  void commitsCarryThePairsBuildTool() {
    CommitPair pair = new CommitPair("a/b", "p1", "c1", 17, "gradle", List.of());
    assertEquals(new Commit("p1", BuildTool.GRADLE), pair.parent());
    assertEquals(new Commit("c1", BuildTool.GRADLE), pair.child());
  }
}
