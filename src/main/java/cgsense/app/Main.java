package cgsense.app;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;

import cgsense.app.cmd.Command;
import cgsense.app.cmd.DiscoverCommand;

public class Main {

  private static final Map<String, Command> COMMANDS = new HashMap<>();
  static {
    reigster(new DiscoverCommand());
  }

  private static void reigster(Command c) {
    COMMANDS.put(c.name(), c);
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Not enough arguments");
      return;
    }

    Command cmd = COMMANDS.get(args[0]);
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
