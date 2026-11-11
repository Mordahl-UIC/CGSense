package cgsense.app;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;

import cgsense.app.cmd.CliCommand;
import cgsense.app.cmd.CommitMinerCommand;
import cgsense.app.cmd.DiscoverCommand;
import cgsense.tester.driver.PairCommand;
import cgsense.tester.driver.RebuildCommand;
import cgsense.tester.driver.VersionCommand;

public class Main {

  private static final Map<String, CliCommand> COMMANDS = new HashMap<>();

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
    if (args.length == 0) {
      System.err.println("Not enough arguments");
      return;
    }

    CliCommand cmd = COMMANDS.get(args[0]);
    if (cmd == null) {
      System.err.printf("Unknown command: %s\n", args[0]);
      return;
    }

    String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

    try {
      CommandLine cl = new DefaultParser().parse(cmd.options(), rest);
      System.exit(cmd.run(cl));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
