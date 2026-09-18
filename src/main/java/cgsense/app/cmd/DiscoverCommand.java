package cgsense.app.cmd;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RepositoryDiscovery;

public class DiscoverCommand implements Command {
  @Override
  public String name() {
    return "discover";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(Option.builder()
            .longOpt("minstars")
            .hasArg().type(Number.class)
            .build())
        .addOption(Option.builder()
            .longOpt("limit")
            .hasArg().type(Number.class)
            .build());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    int limit = Integer.parseInt(cl.getOptionValue("limit"));

    GitHub client = GithubClientFactory.create();
    RepositoryDiscovery rd = new RepositoryDiscovery(client);

    for (GHRepository repo : rd.topJava(limit)) {
      System.out.printf("%s stars: %d\n", repo.getFullName(), repo.getStargazersCount());
    }

    return 0;
  }
}
