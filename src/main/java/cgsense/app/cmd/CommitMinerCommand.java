package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.io.JsonlReader;
import cgsense.io.JsonlWriter;
import cgsense.mining.CommitMiner;
import cgsense.model.RepoRecord;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** Reads data/repos.jsonl from discover and writes data/pairs.jsonl and data/clones. */
public class CommitMinerCommand implements CliCommand {
  private final DataDir data;

  public CommitMinerCommand() {
    this(DataDir.DEFAULT);
  }

  public CommitMinerCommand(DataDir data) {
    this.data = data;
  }

  @Override
  public String name() {
    return "mine-pairs";
  }

  @Override
  public Options options() {
    return new Options();
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    if (!Files.isRegularFile(data.repos())) {
      System.err.println("No " + data.repos() + "; run discover first.");
      return 1;
    }

    boolean overwrite = true;

    if (Files.exists(data.pairs())) {
      Scanner scanner = new Scanner(System.in);
      System.out.println("Commit pairs file already exists. Overwrite? (Y/N)");
      String response = scanner.nextLine();
      switch (response.trim().toUpperCase()) {
        case "Y" -> overwrite = true;
        case "N" -> overwrite = false;
        default -> {
          System.out.println("Response unrecognized. Terminating.");
          scanner.close();
          return 1;
        }
      }
      scanner.close();
    }

    if (overwrite) Files.deleteIfExists(data.pairs());
    Files.createDirectories(data.clones());

    try (JsonlWriter writer =
            JsonlWriter.open(data.pairs(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        Stream<RepoRecord> repos = JsonlReader.read(data.repos(), RepoRecord.class)) {
      CommitMiner miner = new CommitMiner(data, writer);
      repos.forEach(miner::mine);
    }

    return 0;
  }
}
