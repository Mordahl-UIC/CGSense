package cgsense.app;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * One-line java.util.logging setup on stderr. The level comes from the CGSENSE_LOG environment
 * variable (INFO by default; FINE/DEBUG shows why each repo or commit was skipped).
 */
public final class Log {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private Log() {}

  public static Logger get(Class<?> cls) {
    return Logger.getLogger(cls.getName());
  }

  public static void init() {
    Level level = parse(System.getenv("CGSENSE_LOG"));

    Logger root = Logger.getLogger("");
    for (Handler h : root.getHandlers()) {
      root.removeHandler(h);
    }
    Handler handler = new ConsoleHandler();
    handler.setLevel(Level.ALL);
    handler.setFormatter(new OneLine());
    root.addHandler(handler);
    root.setLevel(Level.INFO);

    Logger.getLogger("cgsense").setLevel(level);
    // github-api logs every request at FINE; only its warnings (rate limits, retries) are useful.
    Logger.getLogger("org.kohsuke.github").setLevel(Level.WARNING);
  }

  private static Level parse(String s) {
    if (s == null || s.isBlank()) return Level.INFO;
    return switch (s.trim().toUpperCase(Locale.ROOT)) {
      case "DEBUG", "FINE" -> Level.FINE;
      case "TRACE", "FINER", "FINEST", "ALL" -> Level.FINEST;
      case "WARN", "WARNING" -> Level.WARNING;
      case "ERROR", "SEVERE" -> Level.SEVERE;
      case "OFF" -> Level.OFF;
      default -> Level.INFO;
    };
  }

  private static final class OneLine extends Formatter {
    @Override
    public String format(LogRecord r) {
      String name = r.getLoggerName() == null ? "" : r.getLoggerName();
      String cls = name.substring(name.lastIndexOf('.') + 1);
      StringBuilder sb =
          new StringBuilder()
              .append(LocalTime.now().format(TIME))
              .append(' ')
              .append(String.format("%-5s", label(r.getLevel())))
              .append(" [")
              .append(cls)
              .append("] ")
              .append(formatMessage(r))
              .append(System.lineSeparator());
      if (r.getThrown() != null) {
        java.io.StringWriter sw = new java.io.StringWriter();
        r.getThrown().printStackTrace(new java.io.PrintWriter(sw));
        sb.append(sw);
      }
      return sb.toString();
    }

    private static String label(Level l) {
      if (l.intValue() >= Level.SEVERE.intValue()) return "ERROR";
      if (l.intValue() >= Level.WARNING.intValue()) return "WARN";
      if (l.intValue() >= Level.INFO.intValue()) return "INFO";
      return "DEBUG";
    }
  }
}
