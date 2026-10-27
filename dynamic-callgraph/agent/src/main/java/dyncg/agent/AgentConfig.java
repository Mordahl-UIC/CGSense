package dyncg.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Agent options: comma-separated key=value pairs; list values are separated by ':'. */
final class AgentConfig {
    /** Never instrumented, whatever the include list says. */
    private static final String[] ALWAYS_EXCLUDED = {
        "java/", "javax/", "jdk/", "sun/", "com/sun/", "dyncg/",
    };

    final String[] includes;
    final String[] excludes;
    final File outDir;
    final long flushSeconds;
    /** Maximum number of individual invocations logged per JVM; 0 disables the event log. */
    final long maxEvents;

    /** Used for {@code events=true}. */
    static final long DEFAULT_MAX_EVENTS = 5000000L;

    private AgentConfig(String[] includes, String[] excludes, File outDir, long flushSeconds,
            long maxEvents) {
        this.includes = includes;
        this.excludes = excludes;
        this.outDir = outDir;
        this.flushSeconds = flushSeconds;
        this.maxEvents = maxEvents;
    }

    static AgentConfig parse(String args) {
        List<String> includes = new ArrayList<String>();
        List<String> excludes = new ArrayList<String>();
        String out = null;
        long flush = 60;
        long maxEvents = 0;
        if (args != null) {
            for (String part : args.split(",")) {
                part = part.trim();
                if (part.isEmpty()) {
                    continue;
                }
                int eq = part.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("expected key=value, got '" + part + "'");
                }
                String key = part.substring(0, eq);
                String value = part.substring(eq + 1);
                if (key.equals("include")) {
                    addPrefixes(includes, value);
                } else if (key.equals("exclude")) {
                    addPrefixes(excludes, value);
                } else if (key.equals("out")) {
                    out = value;
                } else if (key.equals("flush")) {
                    flush = Long.parseLong(value);
                } else if (key.equals("events")) {
                    // events=<max per JVM>; true/false are accepted for the default limit / off
                    if (value.equalsIgnoreCase("true")) {
                        maxEvents = DEFAULT_MAX_EVENTS;
                    } else if (value.equalsIgnoreCase("false")) {
                        maxEvents = 0;
                    } else {
                        maxEvents = Long.parseLong(value);
                    }
                } else {
                    throw new IllegalArgumentException("unknown option '" + key + "'");
                }
            }
        }
        if (includes.isEmpty()) {
            throw new IllegalArgumentException("no include prefixes given");
        }
        if (out == null) {
            throw new IllegalArgumentException("no out directory given");
        }
        if (maxEvents < 0 || maxEvents > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("events must be between 0 and " + Integer.MAX_VALUE);
        }
        return new AgentConfig(includes.toArray(new String[0]), excludes.toArray(new String[0]),
                new File(out), flush, maxEvents);
    }

    private static void addPrefixes(List<String> target, String value) {
        for (String prefix : value.split(":")) {
            prefix = prefix.trim().replace('.', '/');
            if (!prefix.isEmpty()) {
                target.add(prefix);
            }
        }
    }

    /** Whether a class (internal name) belongs to the instrumented project code. */
    boolean matches(String internalName) {
        for (String prefix : ALWAYS_EXCLUDED) {
            if (internalName.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : excludes) {
            if (internalName.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : includes) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
