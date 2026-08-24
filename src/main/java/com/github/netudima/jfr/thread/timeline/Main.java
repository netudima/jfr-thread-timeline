package com.github.netudima.jfr.thread.timeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Command line entry point: JFR recording in, interactive HTML thread timeline out. */
public final class Main {

    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Config.ConfigException e) {
            System.err.println("configuration error: " + e.getMessage());
            System.exit(2);
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("run with --help for usage");
            System.exit(2);
        } catch (IOException e) {
            System.err.println("i/o error: " + e.getMessage());
            System.exit(1);
        }
    }

    static final class UsageException extends RuntimeException {
        UsageException(String m) {
            super(m);
        }
    }

    private static int run(String[] args) throws IOException {
        Path input = null;
        Path configPath = null;
        Path output = null;
        String title = null;
        String threadPattern = null;
        double fromMs = 0;
        double toMs = Double.POSITIVE_INFINITY;
        int maxThreads = Integer.MAX_VALUE;
        int topUnmatched = 0;
        Integer stackDepth = null;
        boolean listEvents = false;
        boolean openInBrowser = false;
        HtmlWriter.Compression compression = HtmlWriter.Compression.AUTO;
        Log.Level level = Log.Level.NORMAL;
        List<String> eventTypeOverride = new ArrayList<>();
        // indices of arguments that name a file, so the embedded command line can shorten them
        Set<Integer> pathArgs = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help":
                    printUsage(System.out);
                    return 0;
                case "-V": case "--version":
                    System.out.println("jfr-thread-timeline " + VERSION);
                    return 0;
                case "--dump-config":
                    dumpDefaultConfig(System.out);
                    return 0;
                case "-c": case "--config":
                    configPath = Paths.get(need(args, ++i, a));
                    pathArgs.add(i);
                    break;
                case "-o": case "--output":
                    output = Paths.get(need(args, ++i, a));
                    pathArgs.add(i);
                    break;
                case "-t": case "--title":
                    title = need(args, ++i, a);
                    break;
                case "--threads":
                    threadPattern = need(args, ++i, a);
                    break;
                case "--from":
                    fromMs = parseDouble(need(args, ++i, a), a);
                    break;
                case "--to":
                    toMs = parseDouble(need(args, ++i, a), a);
                    break;
                case "--max-threads":
                    maxThreads = (int) parseDouble(need(args, ++i, a), a);
                    break;
                case "--stack-depth":
                    stackDepth = (int) parseDouble(need(args, ++i, a), a);
                    break;
                case "--event-type":
                    eventTypeOverride.add(need(args, ++i, a));
                    break;
                case "--top-unmatched":
                    topUnmatched = (int) parseDouble(need(args, ++i, a), a);
                    break;
                case "--list-events":
                    listEvents = true;
                    break;
                case "--compress":
                    compression = parseCompression(need(args, ++i, a));
                    break;
                case "--open":
                    openInBrowser = true;
                    break;
                case "-v": case "--verbose":
                    level = Log.Level.VERBOSE;
                    break;
                case "-q": case "--quiet":
                    level = Log.Level.QUIET;
                    break;
                default:
                    if (a.startsWith("-")) {
                        throw new UsageException("unknown option: " + a);
                    }
                    if (input != null) {
                        throw new UsageException("more than one input file given: " + input + " and " + a);
                    }
                    input = Paths.get(a);
                    pathArgs.add(i);
            }
        }

        if (input == null) {
            printUsage(System.err);
            return 2;
        }
        if (!Files.isReadable(input)) {
            throw new UsageException("cannot read " + input);
        }

        Log log = new Log(level);
        Config config = configPath != null ? Config.load(configPath) : Config.loadDefault();
        log.info("config: " + (configPath != null ? configPath.toString() : "built-in default")
                + " (" + config.states.size() + " states, " + config.matchStrategy.name().toLowerCase(Locale.ROOT) + ")");

        if (threadPattern != null) {
            config.threadInclude.add(FrameMatcher.parse(threadPattern, config.ignoreCase));
        }
        if (stackDepth != null) {
            if (stackDepth < 1) {
                throw new UsageException("--stack-depth must be >= 1");
            }
            config.maxStackDepth = stackDepth;
        }
        List<String> eventTypes = !eventTypeOverride.isEmpty() ? eventTypeOverride : config.eventTypes;

        long t0 = System.currentTimeMillis();
        Recording rec = Recording.read(input, config, eventTypes, Long.MIN_VALUE, Long.MAX_VALUE, log);
        log.info(String.format("read %s in %.1fs: %,d samples, %,d threads, %,d unique stacks, %,d unique frames",
                input.getFileName(), (System.currentTimeMillis() - t0) / 1000d,
                rec.totalSamples, rec.threads().size(), rec.stacks.size(), rec.frames.size()));

        if (listEvents) {
            printEventTypes(rec);
            return 0;
        }
        if (rec.totalSamples == 0) {
            System.err.println("no usable sample events found in " + input);
            System.err.println("run with --list-events to see what the recording contains, then set");
            System.err.println("'eventTypes' in the config or pass --event-type <name>");
            return 1;
        }
        if (rec.skippedByThreadFilter > 0) {
            log.info(String.format("%,d samples dropped by the thread filter", rec.skippedByThreadFilter));
        }

        if (fromMs > 0 || Double.isFinite(toMs)) {
            long relFrom = (long) (fromMs * 1_000_000d);
            long relTo = Double.isFinite(toMs) ? (long) (toMs * 1_000_000d) : Long.MAX_VALUE;
            if (relTo <= relFrom) {
                throw new UsageException("--to must be greater than --from");
            }
            rec.applyWindow(relFrom, relTo);
            log.info(String.format("time window %.0f..%s ms: %,d samples kept",
                    fromMs, Double.isFinite(toMs) ? String.format("%.0f", toMs) : "end", rec.totalSamples));
            if (rec.totalSamples == 0) {
                System.err.println("the requested time window contains no samples");
                return 1;
            }
        }

        if (maxThreads < rec.threads().size()) {
            rec.keepBusiestThreads(maxThreads);
            log.info("kept the " + maxThreads + " threads with the most samples");
        }

        Classifier classifier = new Classifier(config, rec);
        log.info(String.format("classified: %,d/%,d frames and %,d/%,d stacks matched a rule",
                classifier.matchedFrameCount(), rec.frames.size(),
                classifier.matchedStackCount(), rec.stacks.size()));

        reportShadowedRules(rec, classifier, log);

        Timeline timeline = Timeline.build(rec, classifier, config, log);
        log.info(String.format("%,d segments across %,d threads, span %.3f s",
                timeline.segmentCount, timeline.threads.size(), timeline.durationNanos / 1e9));

        if (!timeline.groupNames.isEmpty()) {
            int[] counts = new int[timeline.groupNames.size()];
            for (Timeline.ThreadTimeline t : timeline.threads) {
                if (t.group >= 0 && t.group < counts.length) {
                    counts[t.group]++;
                }
            }
            StringBuilder sb = new StringBuilder("thread groups:");
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    sb.append(' ').append(timeline.groupNames.get(i)).append(' ').append(counts[i]).append(',');
                }
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            log.info(sb.toString());
        }

        printStateSummary(classifier, timeline, level != Log.Level.QUIET);
        if (topUnmatched > 0) {
            printUnmatchedFrames(rec, classifier, topUnmatched);
        }

        if (output == null) {
            String base = input.getFileName().toString();
            int dot = base.lastIndexOf('.');
            output = input.resolveSibling((dot > 0 ? base.substring(0, dot) : base) + "-timeline.html");
        }
        String effectiveTitle = title != null ? title
                : (config.title != null ? config.title : input.getFileName().toString());

        CpuCores cores = CpuCores.build(rec, timeline, classifier, log);

        new HtmlWriter(config, classifier, timeline, rec, log)
                .withCpuCores(cores)
                .write(output, effectiveTitle, input.getFileName().toString(),
                        commandLine(args, pathArgs), compression);

        long bytes = Files.size(output);
        System.err.println("wrote " + output.toAbsolutePath() + String.format(" (%.1f MB)", bytes / 1048576d));

        if (openInBrowser) {
            openBrowser(output, log);
        }
        return 0;
    }

    // ---- reporting ----------------------------------------------------------

    private static void printEventTypes(Recording rec) {
        System.out.println("event types in the recording:");
        rec.eventTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-40s %,12d%n", e.getKey(), e.getValue()));
    }

    private static void printStateSummary(Classifier classifier, Timeline timeline, boolean enabled) {
        if (!enabled) {
            return;
        }
        long total = 0;
        for (long t : timeline.totalStateTime) {
            total += t;
        }
        if (total == 0) {
            return;
        }
        System.err.println("thread time by state:");
        List<Classifier.State> states = classifier.states();
        Integer[] order = new Integer[states.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(timeline.totalStateTime[b], timeline.totalStateTime[a]));
        for (int idx : order) {
            long t = timeline.totalStateTime[idx];
            if (t == 0) {
                continue;
            }
            System.err.printf("  %-28s %8.2f s  %5.1f%%%n", states.get(idx).name, t / 1e9, 100.0 * t / total);
        }
    }

    /**
     * Warns about states that matched samples but never won any of them. This is the classic
     * trap when mixing sequences with a broad catch-all: if the catch-all names a frame that
     * sits deeper towards the leaf than the sequence's first step, "innermost" hands it every
     * sample and the specific state silently stays at 0%.
     */
    private static void reportShadowedRules(Recording rec, Classifier classifier, Log log) {
        int n = classifier.ruleCount();
        long[] won = new long[n];
        long[] lost = new long[n];
        long[][] beatenBy = new long[n][n];

        for (Recording.ThreadSamples ts : rec.threads()) {
            for (int i = 0; i < ts.size(); i++) {
                int stack = ts.stackId(i);
                int w = classifier.winningRule(stack);
                if (w < 0) {
                    continue;
                }
                won[w]++;
                int r = classifier.runnerUpRule(stack);
                if (r >= 0) {
                    lost[r]++;
                    beatenBy[r][w]++;
                }
            }
        }

        for (int r = 0; r < n; r++) {
            if (won[r] > 0 || lost[r] == 0) {
                continue;
            }
            int winner = 0;
            for (int w = 1; w < n; w++) {
                if (beatenBy[r][w] > beatenBy[r][winner]) {
                    winner = w;
                }
            }
            log.warn(String.format(
                    "state '%s' matched %,d samples but never won one - '%s' took them all.%n"
                    + "         Both anchor on a frame at a similar depth, and the innermost match wins.%n"
                    + "         Give '%s' the same innermost frames as '%s', or list it earlier.",
                    classifier.ruleName(r), lost[r], classifier.ruleName(winner),
                    classifier.ruleName(r), classifier.ruleName(winner)));
        }

        if (log.verbose()) {
            System.err.println("samples won per state rule:");
            for (int r = 0; r < n; r++) {
                System.err.printf("  %-34s won %,10d   lost %,10d%n", classifier.ruleName(r), won[r], lost[r]);
            }
        }
    }

    /** Lists the hottest leaf frames of stacks that matched no rule &mdash; config-writing fuel. */
    private static void printUnmatchedFrames(Recording rec, Classifier classifier, int top) {
        Map<Integer, Long> counts = new HashMap<>();
        for (Recording.ThreadSamples ts : rec.threads()) {
            for (int i = 0; i < ts.size(); i++) {
                int stack = ts.stackId(i);
                if (classifier.matched(stack)) {
                    continue;
                }
                int[] fs = rec.stacks.frames(stack);
                if (fs.length > 0) {
                    counts.merge(fs[0], 1L, Long::sum);
                }
            }
        }
        System.err.println("top " + top + " unmatched leaf frames (candidates for new states):");
        counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .limit(top)
                .forEach(e -> System.err.printf("  %,10d  %s%n", e.getValue(), rec.frames.name(e.getKey())));
    }

    private static void openBrowser(Path output, Log log) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String[] cmd;
            if (os.contains("mac")) {
                cmd = new String[]{"open", output.toAbsolutePath().toString()};
            } else if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "start", "", output.toAbsolutePath().toString()};
            } else {
                cmd = new String[]{"xdg-open", output.toAbsolutePath().toString()};
            }
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.warn("could not open a browser: " + e.getMessage());
        }
    }

    // ---- argument helpers ---------------------------------------------------

    /**
     * Reproduces the invocation for the record embedded in the report.
     *
     * <p>Reports get shared, so file arguments are reduced to their bare file name: the
     * directories they sit in say more about the machine that ran the tool than about the
     * profile. Only arguments {@code pathArgs} identifies as paths are touched, so thread
     * filters and regexes survive untouched.
     */
    static String commandLine(String[] args, Set<Integer> pathArgs) {
        StringBuilder sb = new StringBuilder("jfr-thread-timeline");
        for (int i = 0; i < args.length; i++) {
            String a = pathArgs.contains(i) ? fileName(args[i]) : args[i];
            sb.append(' ');
            if (a.isEmpty() || a.indexOf(' ') >= 0 || a.indexOf('\'') >= 0) {
                sb.append('"').append(a.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    /** The last segment of a path, with no filesystem access so it never throws. */
    static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = cut >= 0 ? path.substring(cut + 1) : path;
        return name.isEmpty() ? path : name;
    }

    private static String need(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new UsageException(option + " needs a value");
        }
        return args[i];
    }

    private static double parseDouble(String s, String option) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new UsageException(option + " expects a number, got: " + s);
        }
    }

    private static HtmlWriter.Compression parseCompression(String s) {
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "auto":   return HtmlWriter.Compression.AUTO;
            case "always": case "gzip": case "yes": return HtmlWriter.Compression.ALWAYS;
            case "never":  case "none": case "no":  return HtmlWriter.Compression.NEVER;
            default: throw new UsageException("--compress expects auto, always or never");
        }
    }

    private static void dumpDefaultConfig(PrintStream out) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream("/com/github/netudima/jfr/thread/timeline/default-config.yaml")) {
            if (in == null) {
                throw new IOException("built-in config missing from the jar");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("jfr-thread-timeline " + VERSION);
        out.println("Renders an async-profiler / JDK JFR recording as an interactive HTML thread timeline.");
        out.println();
        out.println("usage: jfr-thread-timeline [options] <recording.jfr>");
        out.println();
        out.println("  -c, --config <file>     YAML file mapping frames to coloured states");
        out.println("                          (default: built-in, see --dump-config)");
        out.println("  -o, --output <file>     HTML to write (default: <recording>-timeline.html)");
        out.println("  -t, --title <text>      title shown in the page header");
        out.println("      --threads <pattern> only threads whose name matches (substring, glob or re:...)");
        out.println("      --max-threads <n>   keep only the n threads with the most samples");
        out.println("      --from <ms>         start of the time window, ms from the recording start");
        out.println("      --to <ms>           end of the time window");
        out.println("      --event-type <name> event type to build the timeline from (repeatable)");
        out.println("      --stack-depth <n>   frames kept per stack for the tooltip (default 64)");
        out.println("      --compress <mode>   auto (default) | always | never - gzip the embedded data");
        out.println("      --list-events       print the event types in the recording and exit");
        out.println("      --top-unmatched <n> list the hottest frames that matched no state");
        out.println("      --dump-config       print the built-in configuration and exit");
        out.println("      --open              open the generated HTML in the default browser");
        out.println("  -v, --verbose           more logging      -q, --quiet   less");
        out.println("  -h, --help              this text          -V, --version");
        out.println();
        out.println("examples:");
        out.println("  jfr-thread-timeline profile.jfr");
        out.println("  jfr-thread-timeline -c states.yaml -o out.html --threads 'worker*' profile.jfr");
        out.println("  jfr-thread-timeline --top-unmatched 40 profile.jfr   # what should I add to the config?");
    }

    private Main() {
    }
}
