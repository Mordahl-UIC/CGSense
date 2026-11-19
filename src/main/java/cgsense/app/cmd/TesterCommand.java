package cgsense.app.cmd;

import cgsense.app.DataDir;
import cgsense.io.JsonlReader;
import cgsense.model.CommitPair;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/** Reads data/pairs.jsonl and data/clones from mine-pairs and writes results to --output. */
public class TesterCommand implements CliCommand {
  private final DataDir data;

  public TesterCommand() {
    this(DataDir.DEFAULT);
  }

  public TesterCommand(DataDir data) {
    this.data = data;
  }

  @Override
  public String name() {
    return "tester";
  }

  @Override
  public Options options() {
    return new Options()
        .addOption(
            Option.builder()
                .longOpt("output")
                .hasArg()
                .required()
                .desc("directory for the test results")
                .get());
  }

  @Override
  public int run(CommandLine cl) throws Exception {
    Path outputDir = Path.of(cl.getOptionValue("output"));

    if (!Files.isRegularFile(data.pairs())) {
      System.err.println("No " + data.pairs() + "; run mine-pairs first.");
      return 1;
    }

    List<String> missing;
    try (Stream<CommitPair> pairs = JsonlReader.read(data.pairs(), CommitPair.class)) {
      missing =
          pairs
              .map(CommitPair::fullName)
              .distinct()
              .filter(name -> !Files.isDirectory(data.clone(name)))
              .toList();
    }
    if (!missing.isEmpty()) {
      System.err.println("Missing clones in " + data.clones() + " for: " + missing);
      System.err.println("Run mine-pairs first.");
      return 1;
    }

    Files.createDirectories(outputDir);
    // TODO: run each pair's tests in its repo's BuildContainer and write results to outputDir.
    return 0;
  }
}
