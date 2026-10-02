package cgsense.discovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;

public class RepositoryDiscovery {
  private final GitHub gh;

  public RepositoryDiscovery(GitHub gh) {
    this.gh = gh;
  }

  public List<RepoRecord> discover(Criteria criteria, int limit) throws Exception {
    GHRepositorySearchBuilder search = gh.searchRepositories()
        .language("java")
        .sort(GHRepositorySearchBuilder.Sort.STARS)
        .order(GHDirection.DESC);

    if (criteria.minStars() > 0) {
      search = search.stars(">=" + criteria.minStars());
    }

    List<RepoRecord> results = new ArrayList<>();
    PagedIterator<GHRepository> it = search.list().iterator();
    while (it.hasNext() && results.size() < limit) {
      GHRepository repo = it.next();
      RepoRecord evaluation = evaluate(repo, criteria);
      if (evaluation != null) {
        results.add(evaluation);
      }
    }

    return results;
  }

  private RepoRecord evaluate(GHRepository repo, Criteria criteria) throws IOException {
    String sha = repo.getBranch(repo.getDefaultBranch()).getSHA1();
    GHTree tree = repo.getTreeRecursive(sha, 1);
    List<String> paths = tree.getTree().stream()
        .map(GHTreeEntry::getPath)
        .toList();

    String buildSystem = detectBuildSystem(paths, criteria.buildSystems());
    if (buildSystem == null)
      return null;

    int javaFileCount = (int) paths.stream()
        .filter(p -> p.startsWith("src/main/java/") && p.endsWith(".java"))
        .count();
    if (javaFileCount < criteria.minJavaFiles())
      return null;

    boolean hasTests = paths.stream().anyMatch(p -> p.startsWith("src/test/java/") && p.endsWith(".java"));
    String entryPoint = hasTests ? "Test" : "None";

    if (criteria.requireMainOrTestEntryPoint() && entryPoint.equals("None")) {
      return null;
    }

    boolean isAndroid = paths.stream().anyMatch(p -> p.endsWith("AndroidManifest.xml"));
    if (criteria.excludeAndroid() && isAndroid) {
      return null;
    }

    String license = repo.getLicense() != null ? repo.getLicense().getSpdxId() : null;

    if (license == null) {
      return null;
    }

    return new RepoRecord(repo.getFullName(), repo.getHtmlUrl().toString(), repo.getStargazersCount(),
        license, buildSystem, javaFileCount, entryPoint);
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
