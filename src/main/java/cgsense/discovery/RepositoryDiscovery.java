package cgsense.discovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
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
      String matchedBuildSystem = matchBuildSystem(repo, criteria.buildSystems());
      if (matchedBuildSystem != null) {
        results.add(toRecord(repo, matchedBuildSystem));
      }
    }

    return results;
  }

  private String matchBuildSystem(GHRepository repo, Iterable<String> buildSystems) {
    if (!buildSystems.iterator().hasNext()) {
      return "unspecified";
    }

    for (String buildSys : buildSystems) {
      try {
        repo.getFileContent(buildSystemMarkerFile(buildSys));
        return buildSys;
      } catch (GHFileNotFoundException notFound) {
        continue;
      } catch (IOException other) {
        return null;
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

  private RepoRecord toRecord(GHRepository repo, String buildSystem) throws Exception {
    return new RepoRecord(
        repo.getFullName(),
        repo.getHtmlUrl().toString(),
        repo.getDescription(),
        repo.getStargazersCount(),
        repo.getForksCount(),
        repo.getLicense().getSpdxId(),
        buildSystem);
  }
}
