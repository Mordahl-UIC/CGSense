package cgsense.app.cmd;

import org.apache.commons.cli.Options;

import java.io.File;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

public class MinePairsCommand implements Command {
  @Override
  public String name() {
    return "mine-pairs";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(Option.builder()
            .longOpt("repos")
            .type(File.class)
            .required()
            .build())
        .addOption(Option.builder()
            .longOpt("output")
            .type(File.class)
            .required()
            .build());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    File reposFile = (File) cl.getParsedOptionValue("repos");
    File outputFile = (File) cl.getParsedOptionValue("output");

    return 0;
  }

}
