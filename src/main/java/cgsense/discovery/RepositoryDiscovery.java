package cgsense.discovery;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedSearchIterable;

public class RepositoryDiscovery {
  private final GitHub client;

  public RepositoryDiscovery(GitHub client) {
    this.client = client;
  }

  public PagedSearchIterable<GHRepository> topJava(int limit) {
    return client.searchRepositories()
        .language("java")
        .sort(GHRepositorySearchBuilder.Sort.STARS)
        .list()
        .withPageSize(limit);
  }

}
