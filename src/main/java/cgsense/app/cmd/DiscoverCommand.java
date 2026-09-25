package cgsense.app.cmd;

import java.io.File;
import java.io.InputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.fasterxml.jackson.databind.ObjectMapper;

import cgsense.discovery.Criteria;

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

    System.out.printf("limit: %d\n", limit);
    System.out.printf("minStars: %d\n", criteria.minStars());

    for (String buildSystem : criteria.buildSystems()) {
      System.out.printf("buildSystem: %s\n", buildSystem);
    }

    return 0;
  }
}
