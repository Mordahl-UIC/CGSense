package cgsense.app.cmd;

import org.apache.commons.cli.Options;

import cgsense.io.JsonlReader;
import cgsense.mining.CommitMiner;
import cgsense.model.RepoRecord;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

public class CommitMinerCommand implements Command {
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
            .required()
            .build());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    Path reposFile = Path.of(cl.getOptionValue("repos"));
    Path outputDir = Path.of(cl.getOptionValue("output"));

    Path outputFile = Path.of(outputDir.toUri() + "pairs.jsonl");
    Path clonesDir = outputDir.resolve("clones");
    boolean overwrite = true;

    if (Files.exists(outputFile)) {
      Scanner scanner = new Scanner(System.in);
      System.out.println("Commit pairs file already exists. Overwrite? (Y/N)");
      String response = scanner.nextLine();
      switch (response) {
        case "Y":
          overwrite = true;
          break;
        case "N":
          overwrite = false;
          break;
        default:
          System.out.println("Response unrecognized. Terminating.");
          break;
      }
      scanner.close();
    }

    CommitMiner miner = new CommitMiner(clonesDir, outputFile, overwrite);

    try (Stream<RepoRecord> repos = JsonlReader.read(reposFile, RepoRecord.class)) {
      repos.forEach(miner::mine);
    }

    return 0;
  }

}
