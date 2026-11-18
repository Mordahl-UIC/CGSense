package cgsense.app;

import java.nio.file.Path;

/**
 * Fixed location of everything the pipeline produces between stages. Each stage reads the previous
 * stage's output from here, so results always come from a full pipeline run. Only the tester's
 * results go somewhere the user chooses.
 */
public record DataDir(Path root) {
  public static final DataDir DEFAULT = new DataDir(Path.of("data"));

  /** Written by discover, read by mine-pairs. */
  public Path repos() {
    return root.resolve("repos.jsonl");
  }

  /** Written by mine-pairs, read by the tester. */
  public Path pairs() {
    return root.resolve("pairs.jsonl");
  }

  /** Bare clones written by mine-pairs; read-only for the tester. */
  public Path clones() {
    return root.resolve("clones");
  }

  public Path clone(String fullName) {
    return clones().resolve(slug(fullName));
  }

  public static String slug(String fullName) {
    return fullName.replace("/", "_");
  }
}
