package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.app.Log;
import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RepoCriteria;
import cgsense.discovery.RepoDiscovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.kohsuke.github.GitHub;

public class DiscoverCommand implements CliCommand {
  private static final Logger LOG = Log.get(DiscoverCommand.class);
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
      LOG.info("Criteria from " + cl.getOptionValue("criteria"));
      criteria = mapper.readValue((File) cl.getParsedOptionValue("criteria"), RepoCriteria.class);
    } else {
      LOG.info("Criteria from bundled default-repo-criteria.json");
      try (InputStream is =
          DiscoverCommand.class
              .getClassLoader()
              .getResourceAsStream("default-repo-criteria.json")) {
        criteria = mapper.readValue(is, RepoCriteria.class);
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Could not read bundled default-repo-criteria.json", e);
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
    LOG.info(String.format("Discovering up to %d repos into %s", limit, outputFile));
    LOG.info("Criteria: " + criteria);
    GitHub gh = GithubClientFactory.create();
    RepoDiscovery disc = new RepoDiscovery(gh, outputFile);

    int found = disc.discover(criteria, limit);
    LOG.info(String.format("Wrote %d repos to %s", found, outputFile));

    return 0;
  }
}
