package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RepoCriteria;
import cgsense.discovery.RepoDiscovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GitHub;

public class DiscoverCommand implements CliCommand {
  private final DataDir data;

  public DiscoverCommand() {
    this(DataDir.DEFAULT);
  }

  public DiscoverCommand(DataDir data) {
    this.data = data;
  }

  @Override
  public String name() {
    return "discover";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(Option.builder().longOpt("limit").hasArg().required().get())
        .addOption(Option.builder().longOpt("criteria").hasArg().get());
  }

  private RepoCriteria getRepoCriteria(CommandLine cl) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RepoCriteria criteria = null;
    if (cl.hasOption("criteria")) {
      criteria = mapper.readValue((File) cl.getParsedOptionValue("criteria"), RepoCriteria.class);
    } else {
      try (InputStream is =
          DiscoverCommand.class
              .getClassLoader()
              .getResourceAsStream("default-repo-criteria.json")) {
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

    Files.createDirectories(data.root());
    Path outputFile = data.repos();

    RepoCriteria criteria = getRepoCriteria(cl);
    GitHub gh = GithubClientFactory.create();
    RepoDiscovery disc = new RepoDiscovery(gh, outputFile);

    disc.discover(criteria, limit);

    return 0;
  }
}
