package cgsense.discovery;

import cgsense.app.Log;
import cgsense.io.JsonlWriter;
import cgsense.model.RepoRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Logger;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;

public class RepoDiscovery {
  private static final Logger LOG = Log.get(RepoDiscovery.class);
  private static final int PROGRESS_EVERY = 1;

  private final GitHub gh;
  private final Path outputFile;

  public RepoDiscovery(GitHub gh, Path outputFile) {
    this.gh = gh;
    this.outputFile = outputFile;
  }

  private boolean hasEnglishLetters(String s) {
    if (s == null) {
      return false;
    }

    return s.matches(",*[a-zA-Z].*");
  }

  /** Writes matching repos to the output file and returns how many were written. */
  public int discover(RepoCriteria criteria, int limit) throws Exception {
    GHRepositorySearchBuilder search =
        gh.searchRepositories()
            .language("java")
            .sort(GHRepositorySearchBuilder.Sort.STARS)
            .order(GHDirection.DESC);

    if (criteria.minStars() > 0) {
      search = search.stars(">=" + criteria.minStars());
    }
    LOG.info(
        String.format(
            "Searching GitHub: language=java stars>=%d, sorted by stars", criteria.minStars()));

    int scanned = 0;
    int accepted = 0;
    try (JsonlWriter writer =
        JsonlWriter.open(
            outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      PagedIterator<GHRepository> it = search.list().iterator();
      while (it.hasNext() && accepted < limit) {
        GHRepository repo = it.next();
        scanned++;
        if (scanned % PROGRESS_EVERY == 0) {
          LOG.info(String.format("Scanned %d repos, accepted %d/%d", scanned, accepted, limit));
        }

        String description = repo.getDescription();
        if (!hasEnglishLetters(description)) {
          LOG.fine(skip(repo, "description has no English letters"));
          continue;
        }
        RepoRecord evaluation;
        try {
          evaluation = evaluate(repo, criteria);
        } catch (IOException e) {
          LOG.warning(String.format("Failed to evaluate %s: %s", repo.getFullName(), e));
          continue;
        }
        if (evaluation != null) {
          accepted++;
          LOG.info(
              String.format(
                  "Accepted %s (stars=%d, build=%s, javaFiles=%d, license=%s) [%d/%d]",
                  evaluation.fullName(),
                  evaluation.stars(),
                  evaluation.buildSystem(),
                  evaluation.javaFileCount(),
                  evaluation.license(),
                  accepted,
                  limit));
          writer.write(evaluation);
        }
      }
    }
    if (accepted < limit) {
      LOG.warning(
          String.format(
              "Search exhausted after %d repos; only %d/%d accepted", scanned, accepted, limit));
    } else {
      LOG.info(String.format("Done: scanned %d repos, accepted %d", scanned, accepted));
    }
    return accepted;
  }

  private static String skip(GHRepository repo, String reason) {
    return String.format("Skip %s: %s", repo.getFullName(), reason);
  }

  private RepoRecord evaluate(GHRepository repo, RepoCriteria criteria) throws IOException {
    LOG.fine("Evaluating " + repo.getFullName());
    String license = repo.getLicense() != null ? repo.getLicense().getSpdxId() : null;
    if (license == null) {
      LOG.fine(skip(repo, "no license"));
      return null;
    }

    String sha = repo.getBranch(repo.getDefaultBranch()).getSHA1();
    GHTree tree = repo.getTreeRecursive(sha, 1);
    List<String> paths = tree.getTree().stream().map(GHTreeEntry::getPath).toList();

    String buildSystem = detectBuildSystem(paths, criteria.buildSystems());
    if (buildSystem == null) {
      LOG.fine(skip(repo, "no build file for " + criteria.buildSystems()));
      return null;
    }

    int javaFileCount =
        (int)
            paths.stream()
                .filter(p -> p.startsWith("src/main/java/") && p.endsWith(".java"))
                .count();
    if (javaFileCount < criteria.minJavaFiles()) {
      String reason =
          String.format(
              "%d files under src/main/java < min %d", javaFileCount, criteria.minJavaFiles());
      LOG.fine(skip(repo, reason));
      return null;
    }

    boolean hasTests =
        paths.stream().anyMatch(p -> p.startsWith("src/test/java/") && p.endsWith(".java"));
    String entryPoint = hasTests ? "Test" : "None";

    if (criteria.requireMainOrTestEntryPoint() && entryPoint.equals("None")) {
      LOG.fine(skip(repo, "no tests under src/test/java"));
      return null;
    }

    boolean isAndroid = paths.stream().anyMatch(p -> p.endsWith("AndroidManifest.xml"));
    if (criteria.excludeAndroid() && isAndroid) {
      LOG.fine(skip(repo, "Android project"));
      return null;
    }

    return new RepoRecord(
        repo.getFullName(),
        repo.getHtmlUrl().toString(),
        repo.getStargazersCount(),
        license,
        buildSystem,
        javaFileCount,
        entryPoint);
  }

  private String detectBuildSystem(List<String> paths, Iterable<String> buildSystems) {
    if (!buildSystems.iterator().hasNext()) {
      return "unspecified";
    }
    for (String buildSys : buildSystems) {
      if (paths.contains(buildSystemMarkerFile(buildSys))) {
        return buildSys;
      }
    }
    return null;
  }

  private String buildSystemMarkerFile(String buildSys) {
    switch (buildSys.toLowerCase()) {
      case "maven":
        return "pom.xml";
      case "gradle":
        return "build.gradle";
      default:
        throw new IllegalArgumentException("Unrecognized build system\n");
    }
  }
}
