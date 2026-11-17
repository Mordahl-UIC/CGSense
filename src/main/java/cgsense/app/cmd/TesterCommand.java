package cgsense.app.cmd;

import java.nio.file.Path;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

public class TesterCommand implements CliCommand {

  @Override
  public String name() {
    return "tester";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(Option.builder().longOpt("pairs").hasArg().required().get())
        .addOption(Option.builder().longOpt("output").hasArg().required().get());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    Path commitPairsFile = Path.of(cl.getOptionValue("pairs"));
    Path outputDir = Path.of(cl.getOptionValue("output"));

    return 0;
  }
}
