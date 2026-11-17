package cgsense.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cgsense.model.ChangedFile;
import cgsense.model.CommitPair;
import cgsense.model.RepoRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlTest {
  private static <T> List<T> readAll(Path file, Class<T> type) throws IOException {
    try (Stream<T> items = JsonlReader.read(file, type)) {
      return items.collect(Collectors.toList());
    }
  }

  @Test
  void commitPairFormatIsStable(@TempDir Path dir) throws IOException {
    CommitPair pair =
        new CommitPair("o/r", "aaa", "bbb", 17, List.of(new ChangedFile("src/A.java", 2, 1)));
    Path file = dir.resolve("pairs.jsonl");
    try (JsonlWriter writer = JsonlWriter.open(file)) {
      writer.write(pair);
    }
    assertEquals(
        "{\"fullName\":\"o/r\",\"parentSha\":\"aaa\",\"childSha\":\"bbb\",\"javaVersion\":17,"
            + "\"files\":[{\"filePath\":\"src/A.java\",\"numLinesAdded\":2,\"numLinesDeleted\":1}]}\n",
        Files.readString(file));
    assertEquals(List.of(pair), readAll(file, CommitPair.class));
  }

  @Test
  void repoRecordFormatIsStable(@TempDir Path dir) throws IOException {
    RepoRecord repo =
        new RepoRecord("o/r", "https://github.com/o/r", 10, "MIT", "maven", 42, "Test");
    Path file = dir.resolve("repos.jsonl");
    try (JsonlWriter writer = JsonlWriter.open(file)) {
      writer.write(repo);
    }
    assertEquals(
        "{\"fullName\":\"o/r\",\"url\":\"https://github.com/o/r\",\"stars\":10,\"license\":\"MIT\","
            + "\"buildSystem\":\"maven\",\"javaFileCount\":42,\"entryPointKind\":\"Test\"}\n",
        Files.readString(file));
    assertEquals(List.of(repo), readAll(file, RepoRecord.class));
  }

  @Test
  void readsCommittedDiscoveryOutput() throws IOException {
    List<RepoRecord> repos =
        readAll(Path.of("output/discovered-repos/repos.jsonl"), RepoRecord.class);
    assertFalse(repos.isEmpty());
  }

  @Test
  void skipsBlankLines(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("x.jsonl");
    Files.writeString(
        file, "\n{\"filePath\":\"a\",\"numLinesAdded\":1,\"numLinesDeleted\":0}\n  \n");
    assertEquals(List.of(new ChangedFile("a", 1, 0)), readAll(file, ChangedFile.class));
  }

  @Test
  void reportsFileAndLineOfBadJson(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("bad.jsonl");
    Files.writeString(
        file, "{\"filePath\":\"a\",\"numLinesAdded\":1,\"numLinesDeleted\":0}\n\n{not json\n");
    UncheckedIOException error =
        assertThrows(UncheckedIOException.class, () -> readAll(file, ChangedFile.class));
    assertEquals(file + ":3 is not valid ChangedFile", error.getMessage());
  }
}
