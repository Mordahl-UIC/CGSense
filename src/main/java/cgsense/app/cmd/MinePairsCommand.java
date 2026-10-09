package cgsense.app.cmd;

import org.apache.commons.cli.Options;

import cgsense.io.JsonlReader;
import cgsense.model.RepoRecord;

import java.io.File;
import java.nio.file.Path;
import java.util.stream.Stream;

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
            .hasArg()
            .type(File.class)
            .required()
            .build())
        .addOption(Option.builder()
            .longOpt("output")
            .hasArg()
            .type(File.class)
            .required()
            .build());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    Path reposFile = Path.of(cl.getOptionValue("repos"));
    Path outputFile = Path.of(cl.getOptionValue("output"));
    System.out.printf("repo file: %s - output file: %s\n", reposFile, outputFile);

    try (Stream<RepoRecord> repos = JsonlReader.read(reposFile, RepoRecord.class)) {
      repos.forEach(repo -> System.out.println(repo.fullName() + '\n'));
    }

    return 0;
  }

}
