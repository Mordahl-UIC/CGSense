package cgsense.app.cmd;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import cgsense.discovery.RepoCriteria;
import cgsense.discovery.GithubClientFactory;
import cgsense.model.RepoRecord;
import cgsense.io.JsonlWriter;
import cgsense.discovery.RepoDiscovery;

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
            .build())
        .addOption(Option.builder()
            .longOpt("output")
            .hasArg()
            .required()
            .type(File.class)
            .build());
  }

  private RepoCriteria getRepoCriteria(CommandLine cl) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RepoCriteria criteria = null;
    if (cl.hasOption("criteria")) {
      criteria = mapper.readValue((File) cl.getParsedOptionValue("criteria"), RepoCriteria.class);
    } else {
      try (InputStream is = DiscoverCommand.class.getClassLoader().getResourceAsStream("default-repo-criteria.json")) {
        criteria = mapper.readValue(is, RepoCriteria.class);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return criteria;
  }

  private void writeResults(CommandLine cl, List<RepoRecord> repos) throws Exception {
    Path output = ((File) cl.getParsedOptionValue("output")).toPath();
    new JsonlWriter<RepoRecord>().writeJsonLines(repos, output);
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    int limit = Integer.parseInt(cl.getOptionValue("limit"));
    RepoCriteria criteria = getRepoCriteria(cl);
    GitHub gh = GithubClientFactory.create();
    RepoDiscovery disc = new RepoDiscovery(gh);

    List<RepoRecord> repos = disc.discover(criteria, limit);

    if (cl.hasOption("output")) {
      writeResults(cl, repos);
    }

    return 0;
  }
}
