package dyncg.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

//It parses the option string from -javaagent:...=include=...,out=...,flush=... into the include and exclude prefixes, 
// the output folder and the flush interval, rejecting bad input. Its matches() method then tells the agent whether a class should be instrumented.
//So parse runs only once, outDir and flushSeconds are read whenever a dump is written, and matches runs for every loaded class. Nothing in driver/ uses AgentConfig. So its is called from
//Agent.java, Recorder.java, Dumper.java, and CallGraphTransformer.java.
final class AgentConfig {
    /** Never instrumented, whatever the include list says. */
    private static final String[] ALWAYS_EXCLUDED = {
        "java/", "javax/", "jdk/", "sun/", "com/sun/", "dyncg/",
    };

    final String[] includes; // package prefixes to instrument, the driver fills it if i don't, the user can override it with -javaagent:dyncg-agent.jar=include=...
    final String[] excludes; // prefixes to skip, even if included
    final File outDir; // where the Dumper writes the JSON files
    final long flushSeconds; // how often the Dumper writes (default 60 s)

    private AgentConfig(String[] includes, String[] excludes, File outDir, long flushSeconds) {
        this.includes = includes;
        this.excludes = excludes;
        this.outDir = outDir;
        this.flushSeconds = flushSeconds;
    }
//parse reads the agent's option string (like include=demo/,out=/x/dumps,flush=30), splits it into key=value pairs, and collects the include and exclude prefixes, the output folder and the flush interval (default 60 seconds). 
// It rejects anything malformed, unknown or missing (include and out are required) with an IllegalArgumentException; otherwise it returns a ready-to-use AgentConfig object.
    static AgentConfig parse(String args) {
        List<String> includes = new ArrayList<String>();
        List<String> excludes = new ArrayList<String>();
        String out = null;
        long flush = 60;
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
        return new AgentConfig(includes.toArray(new String[0]), excludes.toArray(new String[0]),
                new File(out), flush);
    }
//It splits one option value into separate package prefixes and adds them to a list. parse calls it for include=... and exclude=....
    private static void addPrefixes(List<String> target, String value) {
        for (String prefix : value.split(":")) {
            prefix = prefix.trim().replace('.', '/');//remove spaces, and turn com.foo. into com/foo/
            if (!prefix.isEmpty()) {
                target.add(prefix);
            }
        }
    }

    /** CallGraphTransformer calls this for every class the JVM loads, to decide whether that class should be instrumented. It checks three lists, in order:
     1. starts with an ALWAYS_EXCLUDED prefix (java/, javax/, jdk/, sun/, com/sun/, dyncg/)  → false
    2. starts with a user exclude prefix                                                   → false
     3. starts with a user include prefix                                                   → true
      4. none of the above                                                                   → false
 */
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
