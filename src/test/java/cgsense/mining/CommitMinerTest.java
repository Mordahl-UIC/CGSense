package cgsense.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cgsense.model.ChangedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitMinerTest {
  private static final String MAIN = "src/main/java/p/A.java";
  private static final String TEST = "src/test/java/p/ATest.java";
  private static final String POM = "pom.xml";

  private static final String A_BEFORE =
      "package p;\n\nclass A {\n  int f() {\n    return 1;\n  }\n}\n";
  private static final String A_AFTER =
      "package p;\n\nclass A {\n  int f() {\n    return 2;\n  }\n}\n";

  @TempDir Path dir;
  private Git git;

  @BeforeEach
  void init() throws Exception {
    git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
  }

  @AfterEach
  void close() {
    git.close();
  }

  private RevCommit commit(Map<String, String> files) throws Exception {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path path = dir.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue());
    }
    git.add().addFilepattern(".").call();
    PersonIdent who = new PersonIdent("test", "test@example.com");
    return git.commit().setMessage("c").setAuthor(who).setCommitter(who).call();
  }

  private RevCommit baseline() throws Exception {
    return commit(
        Map.of(POM, "<project/>", MAIN, A_BEFORE, TEST, "package p;\n\nclass ATest {}\n"));
  }

  private Optional<List<ChangedFile>> candidate(RevCommit parent, RevCommit child)
      throws IOException {
    try (DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
      diff.setRepository(git.getRepository());
      return CommitMiner.candidateFiles(git.getRepository(), diff, parent, child);
    }
  }

  private static Map<String, String> files(String... pathsAndContents) {
    Map<String, String> files = new LinkedHashMap<>();
    for (int i = 0; i < pathsAndContents.length; i += 2) {
      files.put(pathsAndContents[i], pathsAndContents[i + 1]);
    }
    return files;
  }

  /** A.java with {@code n} extra statements added to the method body. */
  private static String withExtraLines(int n) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < n; i++) {
      body.append("    int x").append(i).append(" = ").append(i).append(";\n");
    }
    return "package p;\n\nclass A {\n  int f() {\n" + body + "    return 1;\n  }\n}\n";
  }

  @Test
  void acceptsSmallMainJavaChange() throws Exception {
    RevCommit parent = baseline();
    RevCommit child = commit(files(MAIN, A_AFTER));
    assertEquals(Optional.of(List.of(new ChangedFile(MAIN, 1, 1))), candidate(parent, child));
  }

  @Test
  void rejectsChangeTouchingTests() throws Exception {
    RevCommit parent = baseline();
    RevCommit child = commit(files(MAIN, A_AFTER, TEST, "package p;\n\nclass ATest { int y; }\n"));
    assertTrue(candidate(parent, child).isEmpty());
  }

  @Test
  void rejectsChangeTouchingBuildFile() throws Exception {
    RevCommit parent = baseline();
    RevCommit child = commit(files(MAIN, A_AFTER, POM, "<project><!-- x --></project>"));
    assertTrue(candidate(parent, child).isEmpty());
  }

  @Test
  void rejectsCommentAndFormattingOnlyChange() throws Exception {
    RevCommit parent = baseline();
    RevCommit child =
        commit(
            files(
                MAIN, "package p;\n\n// note\nclass A {\n  /* why */ int f() { return 1; }\n}\n"));
    assertTrue(candidate(parent, child).isEmpty());
  }

  @Test
  void javaLineLimitIsExclusive() throws Exception {
    RevCommit parent = baseline();
    RevCommit justUnder = commit(files(MAIN, withExtraLines(19)));
    assertEquals(Optional.of(List.of(new ChangedFile(MAIN, 19, 0))), candidate(parent, justUnder));

    RevCommit atLimit = commit(files(MAIN, withExtraLines(20 + 19)));
    assertTrue(candidate(justUnder, atLimit).isEmpty());
  }

  @Test
  void rejectsRepoWithoutRootBuildFile() throws Exception {
    RevCommit parent = commit(files(MAIN, A_BEFORE));
    RevCommit child = commit(files(MAIN, A_AFTER));
    assertTrue(candidate(parent, child).isEmpty());
  }

  @Test
  void acceptsGradleBuildFiles() throws Exception {
    RevCommit parent = commit(files("build.gradle.kts", "plugins { java }", MAIN, A_BEFORE));
    RevCommit child = commit(files(MAIN, A_AFTER));
    assertTrue(candidate(parent, child).isPresent());
  }

  @Test
  void rejectsGeneratedOnlyChange() throws Exception {
    String generated = "src/generated/java/p/G.java";
    RevCommit parent = commit(files(POM, "<project/>", MAIN, A_BEFORE, generated, "class G {}"));
    RevCommit child = commit(files(generated, "class G { int x; }"));
    assertTrue(candidate(parent, child).isEmpty());
  }
}
