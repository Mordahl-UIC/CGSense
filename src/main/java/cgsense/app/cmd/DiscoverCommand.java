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

  @Override
  public int run(CommandLine cl) throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    int limit = Integer.parseInt(cl.getOptionValue("limit"));
    Criteria criteria = null;

    if (cl.hasOption("criteria")) {
      criteria = mapper.readValue(cl.getOptionValue("criteria"), Criteria.class);
    } else {
      try (InputStream is = DiscoverCommand.class.getClassLoader().getResourceAsStream("default-repo-criteria.json")) {
        criteria = mapper.readValue(is, Criteria.class);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    if (criteria == null) {
      System.err.println("Could not load criteria");
      return 1;
    }

    System.out.printf("minStars: %d\n", criteria.minStars());
    for (

    String buildSystem : criteria.buildSystems()) {
      System.out.printf("buildSystem: %s\n", buildSystem);
    }

    return 0;
  }
}
