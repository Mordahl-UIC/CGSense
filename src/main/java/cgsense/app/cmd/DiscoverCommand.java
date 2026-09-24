package cgsense.app.cmd;

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
            .build());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    int limit = Integer.parseInt(cl.getOptionValue("limit"));

    String fakeJson = """
        {
          "minStars": 10,
          "buildSystems": ["maven", "gradle"]
        }
        """;
    ObjectMapper mapper = new ObjectMapper();
    Criteria criteria = mapper.readValue(fakeJson, Criteria.class);

    System.out.printf("minStars: %d\n", criteria.minStars());
    for (String buildSystem : criteria.buildSystems()) {
      System.out.printf("buildSystem: %s\n", buildSystem);
    }

    return 0;
  }
}
