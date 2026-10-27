package dyncg.driver;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;

/** Command line: {@code java -jar dyncg.jar {pair,version,rebuild} ...}. */
/*The current project supports three commands:
 * - `pair`: Generate call graphs for a commit pair.
 * - `version`: Generate a call graph for a single version.
 * - `rebuild`: Rebuild the call graphs.
 */
public final class Main {
    public static final String VERSION = "0.1.0";

    private static final Map<String, CliCommand> COMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        register(new PairCommand(), "Call graphs for the before and after commit of a pair.");
        register(new VersionCommand(), "Call graph for one already checked-out version.");
        register(new RebuildCommand(),
                "Rebuild callgraph.json from the raw outputs of earlier test runs, without running tests.");
    }

    private Main() {
    }

    private static void register(CliCommand command, String description) {
        COMMANDS.put(command.name(), command);
        DESCRIPTIONS.put(command.name(), description);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Runs the command line and returns the exit code (0 = success, 2 = usage error, 1 = failure). */
    public static int execute(String... args) {
        if (args.length == 0) {
            printUsage(System.err);
            return 2;
        }
        if (isHelp(args[0])) {
            printUsage(System.out);
            return 0;
        }
        if (args[0].equals("--version") || args[0].equals("-V")) {
            System.out.println("dyncg " + VERSION);
            return 0;
        }
        CliCommand command = COMMANDS.get(args[0]);
        if (command == null) {
            System.err.println("dyncg: unknown command '" + args[0] + "'");
            printUsage(System.err);
            return 2;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (Arrays.stream(rest).anyMatch(Main::isHelp)) {
            return printHelp(command);
        }
        try {
            // No abbreviations: "--max" must not silently mean "--max-events".
            CommandLine cl = DefaultParser.builder().setAllowPartialMatching(false).get()
                    .parse(command.options(), rest);
            if (!cl.getArgList().isEmpty()) {
                throw new UsageException("unexpected argument(s): " + String.join(" ", cl.getArgList()));
            }
            return command.run(cl);
        } catch (ParseException | UsageException error) {
            System.err.println("dyncg " + command.name() + ": " + error.getMessage());
            System.err.println("Run 'dyncg " + command.name() + " --help' for its options.");
            return 2;
        } catch (Exception error) {
            error.printStackTrace();
            return 1;
        }
    }

    static Path abs(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static boolean isHelp(String arg) {
        return arg.equals("--help") || arg.equals("-h");
    }

    private static void printUsage(PrintStream out) {
        out.println("usage: dyncg <command> [options]");
        out.println("Dynamic call graphs for the before and after versions of a commit pair.");
        out.println();
        out.println("Commands:");
        DESCRIPTIONS.forEach((name, description) -> out.printf("  %-9s %s%n", name, description));
        out.println();
        out.println("Run 'dyncg <command> --help' for its options; 'dyncg --version' prints the version.");
    }

    private static int printHelp(CliCommand command) {
        try {
            HelpFormatter.builder().setShowSince(false).setHelpAppendable(new TextHelpAppendable(System.out)).get()
                    .printHelp("dyncg " + command.name(), DESCRIPTIONS.get(command.name()), command.options(),
                            "", true);
            return 0;
        } catch (IOException error) {
            error.printStackTrace();
            return 1;
        }
    }
}
