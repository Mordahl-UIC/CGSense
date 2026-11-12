package cgsense.app;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;

import cgsense.app.cmd.CliCommand;
import cgsense.app.cmd.CommitMinerCommand;
import cgsense.app.cmd.DiscoverCommand;
import cgsense.app.cmd.PairCommand;
import cgsense.app.cmd.RebuildCommand;
import cgsense.app.cmd.VersionCommand;
import cgsense.tester.driver.UsageException;

public class Main {

  private static final Map<String, CliCommand> COMMANDS = new LinkedHashMap<>();

  // Whenever there is a new command, add it here so it can be ran
  static {
    reigster(new DiscoverCommand());
    reigster(new CommitMinerCommand());
    reigster(new PairCommand());
    reigster(new VersionCommand());
    reigster(new RebuildCommand());
  }

  private static void reigster(CliCommand c) {
    COMMANDS.put(c.name(), c);
  }

  public static void main(String[] args) {
    System.exit(execute(args));
  }

  /**
   * Runs the command line and returns the exit code (0 = success, 2 = usage
   * error, 1 = failure).
   */
  public static int execute(String... args) {
    if (args.length == 0) {
      printUsage(System.err);
      return 2;
    }
    if (isHelp(args[0])) {
      printUsage(System.out);
      return 0;
    }

    CliCommand cmd = COMMANDS.get(args[0]);
    if (cmd == null) {
      System.err.printf("Unknown command: %s\n", args[0]);
      printUsage(System.err);
      return 2;
    }

    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    if (Arrays.stream(rest).anyMatch(Main::isHelp)) {
      return printHelp(cmd);
    }

    try {
      // No abbreviations: "--max" must not silently mean "--max-events".
      CommandLine cl = DefaultParser.builder().setAllowPartialMatching(false).get().parse(cmd.options(), rest);
      if (!cl.getArgList().isEmpty()) {
        throw new UsageException("unexpected argument(s): " + String.join(" ", cl.getArgList()));
      }
      return cmd.run(cl);
    } catch (ParseException | UsageException e) {
      System.err.println(cmd.name() + ": " + e.getMessage());
      System.err.println("Run '" + cmd.name() + " --help' for its options.");
      return 2;
    } catch (Exception e) {
      e.printStackTrace();
      return 1;
    }
  }

  private static boolean isHelp(String arg) {
    return arg.equals("--help") || arg.equals("-h");
  }

  private static void printUsage(PrintStream out) {
    out.println("usage: <command> [options]");
    out.println("Commands: " + String.join(", ", COMMANDS.keySet()));
    out.println("Run '<command> --help' for its options.");
  }

  private static int printHelp(CliCommand cmd) {
    try {
      HelpFormatter.builder().setShowSince(false).setHelpAppendable(new TextHelpAppendable(System.out)).get()
          .printHelp(cmd.name(), "", cmd.options(), "", true);
      return 0;
    } catch (IOException e) {
      e.printStackTrace();
      return 1;
    }
  }
}
