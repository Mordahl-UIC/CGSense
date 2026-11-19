package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.app.Log;
import cgsense.io.JsonlReader;
import cgsense.io.JsonlWriter;
import cgsense.mining.CommitMiner;
import cgsense.model.RepoRecord;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/** Reads data/repos.jsonl from discover and writes data/pairs.jsonl and data/clones. */
public class CommitMinerCommand implements CliCommand {
  private static final Logger LOG = Log.get(CommitMinerCommand.class);
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
      LOG.severe("No " + data.repos() + "; run discover first.");
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
    LOG.info(
        String.format(
            "%s %s; clones in %s",
            overwrite ? "Writing" : "Appending to", data.pairs(), data.clones()));

    try (JsonlWriter writer =
            JsonlWriter.open(data.pairs(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        Stream<RepoRecord> repos = JsonlReader.read(data.repos(), RepoRecord.class)) {
      List<RepoRecord> list = repos.toList();
      LOG.info(String.format("Mining %d repos from %s", list.size(), data.repos()));
      CommitMiner miner = new CommitMiner(data, writer);
      int total = 0;
      for (int i = 0; i < list.size(); i++) {
        RepoRecord repo = list.get(i);
        LOG.info(String.format("[%d/%d] %s", i + 1, list.size(), repo.fullName()));
        total += miner.mine(repo);
      }
      LOG.info(
          String.format(
              "Done: %d pairs from %d repos written to %s", total, list.size(), data.pairs()));
    }

    return 0;
  }
}
