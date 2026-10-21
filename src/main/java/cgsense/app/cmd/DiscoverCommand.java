package cgsense.app.cmd;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import cgsense.discovery.RepoCriteria;
import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RepoDiscovery;

public class DiscoverCommand implements CliCommand {
  @Override
  public String name() {
    return "discover";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(Option.builder()
            .longOpt("limit")
            .hasArg()
            .get())
        .addOption(Option.builder()
            .longOpt("criteria")
            .hasArg()
            .get())
        .addOption(Option.builder()
            .longOpt("output")
            .hasArg()
            .required()
            .get());
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

  @Override
  public int run(CommandLine cl) throws Exception {
    int limit = Integer.parseInt(cl.getOptionValue("limit"));

    Path outputDir = Path.of(cl.getOptionValue("output"));
    Path outputFile = outputDir.resolve("repos.jsonl");

    RepoCriteria criteria = getRepoCriteria(cl);
    GitHub gh = GithubClientFactory.create();
    RepoDiscovery disc = new RepoDiscovery(gh, outputFile);

    disc.discover(criteria, limit);

    return 0;
  }
}
