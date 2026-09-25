package cgsense.discovery;

import java.util.ArrayList;
import java.util.List;

import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterator;

public class RepositoryDiscovery {
  private final GitHub gh;

  public RepositoryDiscovery(GitHub gh) {
    this.gh = gh;
  }

  public List<GHRepository> discover(Criteria criteria, int limit) {
    GHRepositorySearchBuilder search = gh.searchRepositories()
        .language("java")
        .sort(GHRepositorySearchBuilder.Sort.STARS)
        .order(GHDirection.DESC);

    if (criteria.minStars() > 0) {
      search = search.stars(">=" + criteria.minStars());
    }

    List<GHRepository> results = new ArrayList<>();
    PagedIterator<GHRepository> it = search.list().iterator();
    while (it.hasNext() && results.size() < limit) {
      GHRepository repo = it.next();
      results.add(repo);
    }

    return results;
  }
}
