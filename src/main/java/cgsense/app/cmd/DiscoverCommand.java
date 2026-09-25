package cgsense.app.cmd;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import cgsense.discovery.Criteria;
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
            .longOpt("limit")
            .hasArg().type(Number.class)
            .build())
        .addOption(Option.builder()
            .longOpt("criteria")
            .hasArg()
            .type(File.class)
            .build());
  }

  private Criteria getRepoCriteria(CommandLine cl) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Criteria criteria = null;
    if (cl.hasOption("criteria")) {
      criteria = mapper.readValue((File) cl.getParsedOptionValue("criteria"), Criteria.class);
    } else {
      try (InputStream is = DiscoverCommand.class.getClassLoader().getResourceAsStream("default-repo-criteria.json")) {
        criteria = mapper.readValue(is, Criteria.class);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return criteria;
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    int limit = Integer.parseInt(cl.getOptionValue("limit"));
    Criteria criteria = getRepoCriteria(cl);
    GitHub gh = GithubClientFactory.create();
    RepositoryDiscovery disc = new RepositoryDiscovery(gh);

    List<GHRepository> repos = disc.discover(criteria, limit);

    for (GHRepository repo : repos) {
      System.out.printf("%s | %d\n", repo.getFullName(), repo.getStargazersCount());
    }

    return 0;
  }
}
