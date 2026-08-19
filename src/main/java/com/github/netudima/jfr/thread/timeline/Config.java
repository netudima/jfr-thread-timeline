package com.github.netudima.jfr.thread.timeline;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The user-supplied YAML configuration: which frames map to which coloured thread state. */
public final class Config {

    public enum MatchStrategy {
        /** Walk the stack leaf &rarr; root; the first frame matching any rule decides. */
        INNERMOST,
        /** The first rule in file order that matches anywhere in the stack decides. */
        CONFIG_ORDER;

        static MatchStrategy parse(String s) {
            String v = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            switch (v) {
                case "innermost": case "innermost-frame": case "leaf": return INNERMOST;
                case "config-order": case "rule-order": case "priority": return CONFIG_ORDER;
                default:
                    throw new ConfigException("matchStrategy must be 'innermost' or 'config-order', got: " + s);
            }
        }
    }

    /**
     * A sequence of frames that must all appear on the stack, innermost first, in this order.
     * Gaps are allowed between the steps, so {@code [park, MemtableShard.put]} matches any stack
     * where a {@code park} frame sits somewhere above a {@code MemtableShard.put} frame.
     *
     * <p>Each step may list alternatives; the step is satisfied when any of them matches.
     */
    public static final class Sequence {
        public final List<List<FrameMatcher>> steps;

        Sequence(List<List<FrameMatcher>> steps) {
            this.steps = steps;
        }

        public int length() {
            return steps.size();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (List<FrameMatcher> step : steps) {
                if (sb.length() > 0) {
                    sb.append(" < ");
                }
                sb.append(step.size() == 1 ? step.get(0).spec() : step.toString());
            }
            return sb.toString();
        }
    }

    /**
     * One coloured state. A state matches a stack when <em>any</em> of its sequences matches;
     * a plain {@code frames:} list compiles into a single one-step sequence.
     */
    public static final class StateRule {
        public final String name;
        public final String color;
        public final String description;
        public final List<Sequence> sequences;

        StateRule(String name, String color, String description, List<Sequence> sequences) {
            this.name = name;
            this.color = color;
            this.description = description;
            this.sequences = sequences;
        }

        /** True if any pattern of this rule matches the frame, ignoring sequence structure. */
        public boolean matchesFrame(String frame) {
            for (Sequence seq : sequences) {
                for (List<FrameMatcher> step : seq.steps) {
                    for (FrameMatcher m : step) {
                        if (m.matches(frame)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public List<FrameMatcher> allPatterns() {
            List<FrameMatcher> out = new ArrayList<>();
            for (Sequence seq : sequences) {
                for (List<FrameMatcher> step : seq.steps) {
                    out.addAll(step);
                }
            }
            return out;
        }

        /** True if this rule uses at least one multi-frame sequence. */
        public boolean hasSequences() {
            for (Sequence seq : sequences) {
                if (seq.length() > 1) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A named bucket of threads, matched on thread name. */
    public static final class ThreadGroup {
        public final String name;
        public final List<FrameMatcher> matchers;

        ThreadGroup(String name, List<FrameMatcher> matchers) {
            this.name = name;
            this.matchers = matchers;
        }

        public boolean matches(String threadName) {
            for (FrameMatcher m : matchers) {
                if (m.matches(threadName)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }

    /** Fallback palette used when a stack matches no rule at all. */
    private static final Map<String, String> DEFAULT_THREAD_STATE_COLORS = new LinkedHashMap<>();
    static {
        DEFAULT_THREAD_STATE_COLORS.put("Running",  "#7f9f6f");
        DEFAULT_THREAD_STATE_COLORS.put("Runnable", "#7f9f6f");
        DEFAULT_THREAD_STATE_COLORS.put("Native",   "#9aa89a");
        DEFAULT_THREAD_STATE_COLORS.put("Sleeping", "#d9d9d9");
        DEFAULT_THREAD_STATE_COLORS.put("Blocked",  "#c98b8b");
        DEFAULT_THREAD_STATE_COLORS.put("Waiting",  "#cfc8b8");
        DEFAULT_THREAD_STATE_COLORS.put("Parked",   "#c6bdd6");
        DEFAULT_THREAD_STATE_COLORS.put("Unknown",  "#c4c4c4");
    }

    /** Where the config came from, and its verbatim text &mdash; both are embedded in the report. */
    public String sourceName = "<built-in default>";
    public String text = "";

    public String title;
    public MatchStrategy matchStrategy = MatchStrategy.INNERMOST;
    public boolean ignoreCase = false;
    /** Event types to build the timeline from; {@code null}/empty means auto-detect. */
    public List<String> eventTypes = new ArrayList<>();
    /** Samples further apart than this leave a blank gap; {@code null} means auto-detect. */
    public Double gapThresholdMs;
    /** Nominal width of a single sample; {@code null} means auto-detect from the data. */
    public Double sampleIntervalMs;
    public int maxStackDepth = 64;

    public boolean useThreadState = true;
    public Map<String, String> threadStateColors = new LinkedHashMap<>(DEFAULT_THREAD_STATE_COLORS);
    public String unmatchedName = "Other";
    public String unmatchedColor = "#c4c4c4";

    public List<FrameMatcher> threadInclude = new ArrayList<>();
    public List<FrameMatcher> threadExclude = new ArrayList<>();

    /** Thread name buckets, in the order they are shown. */
    public List<ThreadGroup> threadGroups = new ArrayList<>();
    /** Name of the bucket collecting threads that no group claimed. */
    public String ungroupedName = "Other";

    public List<StateRule> states = new ArrayList<>();

    /** Colours handed out to rules that do not declare one. */
    private static final String[] AUTO_PALETTE = {
        "#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948",
        "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac", "#86bcb6", "#d37295",
    };

    public static Config loadDefault() {
        try (InputStream in = Config.class.getResourceAsStream("/com/github/netudima/jfr/thread/timeline/default-config.yaml")) {
            if (in == null) {
                throw new ConfigException("built-in default config is missing from the jar");
            }
            return load(slurp(new InputStreamReader(in, StandardCharsets.UTF_8)), "<built-in default>");
        } catch (IOException e) {
            throw new ConfigException("cannot read built-in default config: " + e.getMessage());
        }
    }

    public static Config load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(slurp(r), path.toString());
        }
    }

    public static Config load(Reader reader, String source) {
        try {
            return load(slurp(reader), source);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + source + ": " + e.getMessage());
        }
    }

    private static String slurp(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Config load(String yaml, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            throw new ConfigException("cannot parse " + source + ": " + e.getMessage());
        }
        if (root == null) {
            throw new ConfigException(source + " is empty");
        }
        if (!(root instanceof Map)) {
            throw new ConfigException(source + " must be a YAML mapping at the top level");
        }
        Map<String, Object> map = (Map<String, Object>) root;
        Config c = new Config();
        c.sourceName = source;
        c.text = yaml;

        c.title = str(map, "title", null);
        String strategy = str(map, "matchStrategy", null);
        if (strategy != null) {
            c.matchStrategy = MatchStrategy.parse(strategy);
        }
        c.ignoreCase = bool(map, "ignoreCase", false);
        c.maxStackDepth = (int) num(map, "maxStackDepth", 64);
        if (c.maxStackDepth < 1) {
            throw new ConfigException("maxStackDepth must be >= 1");
        }
        c.gapThresholdMs = optionalNum(map, "gapThresholdMs");
        c.sampleIntervalMs = optionalNum(map, "sampleIntervalMs");
        c.eventTypes = strList(map, "eventTypes");

        Object fallback = map.get("fallback");
        if (fallback != null) {
            if (!(fallback instanceof Map)) {
                throw new ConfigException("'fallback' must be a mapping");
            }
            Map<String, Object> fb = (Map<String, Object>) fallback;
            c.useThreadState = bool(fb, "useThreadState", true);
            c.unmatchedName = str(fb, "name", c.unmatchedName);
            c.unmatchedColor = str(fb, "color", c.unmatchedColor);
            Object colors = fb.get("threadStateColors");
            if (colors != null) {
                if (!(colors instanceof Map)) {
                    throw new ConfigException("'fallback.threadStateColors' must be a mapping");
                }
                for (Map.Entry<?, ?> e : ((Map<?, ?>) colors).entrySet()) {
                    c.threadStateColors.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }

        Object threads = map.get("threads");
        if (threads != null) {
            if (!(threads instanceof Map)) {
                throw new ConfigException("'threads' must be a mapping with 'include' / 'exclude'");
            }
            Map<String, Object> t = (Map<String, Object>) threads;
            for (String p : strList(t, "include")) {
                c.threadInclude.add(FrameMatcher.parse(p, c.ignoreCase));
            }
            for (String p : strList(t, "exclude")) {
                c.threadExclude.add(FrameMatcher.parse(p, c.ignoreCase));
            }
        }

        c.ungroupedName = str(map, "ungroupedName", c.ungroupedName);
        Object groupsNode = map.get("threadGroups");
        if (groupsNode != null) {
            if (!(groupsNode instanceof List)) {
                throw new ConfigException("'threadGroups' must be a list of {name, threads} mappings");
            }
            for (Object o : (List<?>) groupsNode) {
                if (!(o instanceof Map)) {
                    throw new ConfigException("every entry of 'threadGroups' must be a mapping "
                            + "with 'name' and 'threads'");
                }
                Map<String, Object> gm = (Map<String, Object>) o;
                String gname = str(gm, "name", null);
                if (gname == null || gname.trim().isEmpty()) {
                    throw new ConfigException("a thread group is missing its 'name'");
                }
                List<String> gpatterns = strList(gm, "threads");
                if (gpatterns.isEmpty()) {
                    throw new ConfigException("thread group '" + gname + "' has no 'threads' patterns");
                }
                List<FrameMatcher> gm2 = new ArrayList<>(gpatterns.size());
                for (String p : gpatterns) {
                    try {
                        gm2.add(FrameMatcher.parse(p, c.ignoreCase));
                    } catch (RuntimeException e) {
                        throw new ConfigException("thread group '" + gname + "': bad pattern '" + p
                                + "': " + e.getMessage());
                    }
                }
                c.threadGroups.add(new ThreadGroup(gname.trim(), gm2));
            }
        }

        Object statesNode = map.get("states");
        if (statesNode == null) {
            throw new ConfigException(source + " has no 'states' section");
        }
        if (!(statesNode instanceof List)) {
            throw new ConfigException("'states' must be a list");
        }
        int autoColor = 0;
        for (Object o : (List<Object>) statesNode) {
            if (!(o instanceof Map)) {
                throw new ConfigException("every entry of 'states' must be a mapping with 'name' and 'frames'");
            }
            Map<String, Object> sm = (Map<String, Object>) o;
            String name = str(sm, "name", null);
            if (name == null || name.trim().isEmpty()) {
                throw new ConfigException("a state is missing its 'name'");
            }
            String color = str(sm, "color", null);
            if (color == null || color.trim().isEmpty()) {
                color = AUTO_PALETTE[autoColor++ % AUTO_PALETTE.length];
            }
            List<Sequence> sequences = new ArrayList<>();

            // `frames: [a, b]` is a one-step sequence whose step accepts a or b.
            List<String> patterns = strList(sm, "frames");
            if (!patterns.isEmpty()) {
                sequences.add(new Sequence(Collections.singletonList(compileStep(patterns, name, c.ignoreCase))));
            }

            Object seqNode = sm.get("sequences");
            if (seqNode != null) {
                if (!(seqNode instanceof List)) {
                    throw new ConfigException("state '" + name + "': 'sequences' must be a list of sequences, "
                            + "each one a list of frames ordered innermost first");
                }
                for (Object entry : (List<?>) seqNode) {
                    sequences.add(compileSequence(entry, name, c.ignoreCase));
                }
            }

            if (sequences.isEmpty()) {
                throw new ConfigException("state '" + name + "' has no 'frames' or 'sequences' patterns");
            }
            c.states.add(new StateRule(name.trim(), color.trim(), str(sm, "description", null), sequences));
        }
        if (c.states.isEmpty()) {
            throw new ConfigException(source + " defines no states");
        }
        return c;
    }

    /** Compiles one entry of a {@code sequences:} list into a {@link Sequence}. */
    private static Sequence compileSequence(Object entry, String stateName, boolean ignoreCase) {
        if (entry == null) {
            throw new ConfigException("state '" + stateName + "': empty entry in 'sequences'");
        }
        if (entry instanceof String) {
            // a bare string is just a one-frame sequence
            return new Sequence(Collections.singletonList(
                    compileStep(Collections.singletonList((String) entry), stateName, ignoreCase)));
        }
        if (!(entry instanceof List)) {
            throw new ConfigException("state '" + stateName + "': every entry of 'sequences' must be a list of "
                    + "frames ordered innermost first, got: " + entry);
        }
        List<?> steps = (List<?>) entry;
        if (steps.isEmpty()) {
            throw new ConfigException("state '" + stateName + "': a sequence must have at least one frame");
        }
        List<List<FrameMatcher>> compiled = new ArrayList<>(steps.size());
        for (Object step : steps) {
            if (step instanceof String) {
                compiled.add(compileStep(Collections.singletonList((String) step), stateName, ignoreCase));
            } else if (step instanceof List) {
                List<String> alternatives = new ArrayList<>();
                for (Object alt : (List<?>) step) {
                    if (!(alt instanceof String)) {
                        throw new ConfigException("state '" + stateName
                                + "': alternatives inside a sequence step must be strings, got: " + alt);
                    }
                    alternatives.add((String) alt);
                }
                if (alternatives.isEmpty()) {
                    throw new ConfigException("state '" + stateName + "': empty step in a sequence");
                }
                compiled.add(compileStep(alternatives, stateName, ignoreCase));
            } else {
                throw new ConfigException("state '" + stateName + "': a sequence step must be a frame pattern "
                        + "or a list of alternative patterns, got: " + step);
            }
        }
        return new Sequence(compiled);
    }

    private static List<FrameMatcher> compileStep(List<String> patterns, String stateName, boolean ignoreCase) {
        List<FrameMatcher> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            try {
                out.add(FrameMatcher.parse(p, ignoreCase));
            } catch (RuntimeException e) {
                throw new ConfigException("state '" + stateName + "': bad pattern '" + p + "': " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Index of the first group claiming this thread, or {@code threadGroups.size()} for the
     * catch-all bucket. Returns -1 when no groups are configured at all.
     */
    public int groupOf(String threadName) {
        if (threadGroups.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < threadGroups.size(); i++) {
            if (threadGroups.get(i).matches(threadName)) {
                return i;
            }
        }
        return threadGroups.size();
    }

    /** Group display names, with the catch-all bucket appended. Empty when grouping is off. */
    public List<String> groupNames() {
        if (threadGroups.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>(threadGroups.size() + 1);
        for (ThreadGroup g : threadGroups) {
            names.add(g.name);
        }
        names.add(ungroupedName);
        return names;
    }

    public boolean acceptsThread(String threadName) {
        if (!threadInclude.isEmpty()) {
            boolean hit = false;
            for (FrameMatcher m : threadInclude) {
                if (m.matches(threadName)) { hit = true; break; }
            }
            if (!hit) {
                return false;
            }
        }
        for (FrameMatcher m : threadExclude) {
            if (m.matches(threadName)) {
                return false;
            }
        }
        return true;
    }

    /** Maps a raw JFR thread-state token such as {@code STATE_SLEEPING} to a display name. */
    public static String prettyThreadState(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "Unknown";
        }
        String s = raw;
        if (s.startsWith("STATE_")) {
            s = s.substring("STATE_".length());
        }
        if (s.startsWith("_")) {
            s = s.substring(1);
        }
        switch (s.toUpperCase(Locale.ROOT)) {
            // async-profiler tags CPU/ctimer samples STATE_DEFAULT: the thread was on-CPU.
            case "DEFAULT":      return "Running";
            case "RUNNABLE":     return "Runnable";
            case "SLEEPING":     return "Sleeping";
            case "IN_NATIVE":
            case "NATIVE":       return "Native";
            case "BLOCKED_ON_MONITOR_ENTER":
            case "BLOCKED":      return "Blocked";
            case "WAITING":
            case "IN_OBJECT_WAIT":
            case "IN_OBJECT_WAIT_TIMED": return "Waiting";
            case "PARKED":
            case "PARKED_TIMED": return "Parked";
            case "NEW":          return "New";
            case "TERMINATED":   return "Terminated";
            default:
                String lower = s.toLowerCase(Locale.ROOT).replace('_', ' ');
                return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    public String threadStateColor(String prettyName) {
        String c = threadStateColors.get(prettyName);
        return c != null ? c : unmatchedColor;
    }

    // ---- small YAML helpers -------------------------------------------------

    private static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("no")) return false;
        throw new ConfigException("'" + key + "' must be true or false, got: " + v);
    }

    private static double num(Map<String, Object> map, String key, double def) {
        Double v = optionalNum(map, key);
        return v == null ? def : v;
    }

    private static Double optionalNum(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("'" + key + "' must be a number, got: " + v);
        }
    }

    private static List<String> strList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return new ArrayList<>();
        }
        if (v instanceof String) {
            return new ArrayList<>(Collections.singletonList((String) v));
        }
        if (!(v instanceof List)) {
            throw new ConfigException("'" + key + "' must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o == null) {
                throw new ConfigException("'" + key + "' contains an empty entry");
            }
            out.add(String.valueOf(o));
        }
        return out;
    }
}
