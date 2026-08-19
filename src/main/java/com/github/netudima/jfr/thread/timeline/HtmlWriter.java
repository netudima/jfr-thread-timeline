package com.github.netudima.jfr.thread.timeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Serialises the timeline model and drops it into the self-contained HTML template. */
public final class HtmlWriter {

    /** Above this size the embedded JSON is gzipped and base64-encoded. */
    private static final int GZIP_THRESHOLD_BYTES = 4 << 20;

    public enum Compression { AUTO, ALWAYS, NEVER }

    private final Config config;
    private final Classifier classifier;
    private final Timeline timeline;
    private final Recording recording;
    private final Log log;

    public HtmlWriter(Config config, Classifier classifier, Timeline timeline, Recording recording, Log log) {
        this.config = config;
        this.classifier = classifier;
        this.timeline = timeline;
        this.recording = recording;
        this.log = log;
    }

    public void write(Path output, String title, String sourceName, Compression compression) throws IOException {
        write(output, title, sourceName, null, compression);
    }

    public void write(Path output, String title, String sourceName, String commandLine,
                      Compression compression) throws IOException {
        byte[] json = buildJson(title, sourceName, commandLine).getBytes(StandardCharsets.UTF_8);
        boolean gzip = compression == Compression.ALWAYS
                || (compression == Compression.AUTO && json.length > GZIP_THRESHOLD_BYTES);

        String payload;
        String mode;
        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 4);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos, 1 << 16)) {
                gz.write(json);
            }
            payload = Base64.getEncoder().encodeToString(bos.toByteArray());
            mode = "gzip-base64";
            log.info(String.format("data %.1f MB JSON -> %.1f MB gzip+base64",
                    json.length / 1048576d, payload.length() / 1048576d));
        } else {
            payload = new String(json, StandardCharsets.UTF_8);
            mode = "json";
            log.info(String.format("data %.1f MB JSON (uncompressed)", json.length / 1048576d));
        }

        String template = resource("/com/github/netudima/jfr/thread/timeline/timeline.html");
        String css = resource("/com/github/netudima/jfr/thread/timeline/timeline.css");
        String js = resource("/com/github/netudima/jfr/thread/timeline/timeline.js");

        try (OutputStream os = Files.newOutputStream(output);
             Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            expand(w, template, name -> {
                switch (name) {
                    case "TITLE":     return escapeHtml(title);
                    case "CSS":       return css;
                    case "JS":        return js;
                    case "DATA_MODE": return mode;
                    case "DATA":      return payload;
                    default: throw new IllegalStateException("unknown template placeholder " + name);
                }
            });
        }
    }

    private interface Placeholder {
        String value(String name);
    }

    /** Writes the template, substituting {@code {{NAME}}} tokens without copying the payload. */
    private static void expand(Writer w, String template, Placeholder p) throws IOException {
        int i = 0;
        while (true) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                w.write(template, i, template.length() - i);
                return;
            }
            int close = template.indexOf("}}", open);
            if (close < 0) {
                w.write(template, i, template.length() - i);
                return;
            }
            w.write(template, i, open - i);
            w.write(p.value(template.substring(open + 2, close)));
            i = close + 2;
        }
    }

    // ---- JSON model ---------------------------------------------------------

    /**
     * The config as it will be shown (and copied) from the report: the verbatim file, preceded by
     * comment lines recording where it came from. The result is still valid YAML, so a reader can
     * paste it straight back into {@code --config}.
     */
    private String configListing(String sourceName, String commandLine) {
        StringBuilder sb = new StringBuilder(config.text.length() + 512);
        sb.append("# Configuration used to render this timeline.\n");
        sb.append("# recording : ").append(Main.fileName(sourceName)).append('\n');
        sb.append("# config    : ").append(Main.fileName(config.sourceName)).append('\n');
        if (commandLine != null && !commandLine.isEmpty()) {
            sb.append("# command   : ").append(commandLine).append('\n');
        }
        sb.append("# generated : ").append(Instant.now()).append('\n');
        if (!config.eventTypes.isEmpty() || !recording.usedEventTypes.isEmpty()) {
            sb.append("# events    : ").append(String.join(", ", recording.usedEventTypes)).append('\n');
        }
        sb.append("#\n");
        sb.append("# Command-line options can override parts of this file (thread filters, stack depth,\n");
        sb.append("# event types), so re-running with the config alone may not reproduce it exactly.\n");
        sb.append('\n');
        sb.append(config.text);
        if (config.text.length() > 0 && config.text.charAt(config.text.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildJson(String title, String sourceName, String commandLine) {
        int maxDepth = config.maxStackDepth;

        // Only stacks actually referenced by a segment make it into the file, and only the
        // top `maxStackDepth` frames of each.
        int[] stackRemap = new int[recording.stacks.size()];
        Arrays.fill(stackRemap, -1);
        List<Integer> keptStacks = new ArrayList<>();
        for (Timeline.ThreadTimeline t : timeline.threads) {
            for (int i = 0; i < t.count; i++) {
                int s = t.stackId[i];
                if (stackRemap[s] < 0) {
                    stackRemap[s] = keptStacks.size();
                    keptStacks.add(s);
                }
            }
        }

        int[] frameRemap = new int[recording.frames.size()];
        Arrays.fill(frameRemap, -1);
        List<Integer> keptFrames = new ArrayList<>();
        int[][] outStacks = new int[keptStacks.size()][];
        int[][] outMatch = new int[keptStacks.size()][];
        for (int k = 0; k < keptStacks.size(); k++) {
            int[] src = recording.stacks.frames(keptStacks.get(k));
            int[] positions = classifier.matchPositions(keptStacks.get(k));
            // keep enough frames that every matched step of the sequence is still visible
            int depth = Math.min(src.length, maxDepth);
            if (positions.length > 0) {
                depth = Math.min(src.length, Math.max(depth, positions[positions.length - 1] + 1));
            }
            int[] dst = new int[depth];
            for (int i = 0; i < depth; i++) {
                int f = src[i];
                if (frameRemap[f] < 0) {
                    frameRemap[f] = keptFrames.size();
                    keptFrames.add(f);
                }
                dst[i] = frameRemap[f];
            }
            outStacks[k] = dst;
            int kept = 0;
            while (kept < positions.length && positions[kept] < depth) {
                kept++;
            }
            outMatch[k] = kept == positions.length ? positions : Arrays.copyOf(positions, kept);
        }

        StringBuilder sb = new StringBuilder(1 << 22);
        sb.append('{');

        sb.append("\"meta\":{");
        field(sb, "title", title).append(',');
        field(sb, "source", sourceName).append(',');
        field(sb, "generated", Instant.now().toString()).append(',');
        sb.append("\"startEpochMs\":").append(recording.startNanos / 1_000_000L).append(',');
        sb.append("\"durationUs\":").append(us(timeline.durationNanos)).append(',');
        sb.append("\"sampleIntervalUs\":").append(us(timeline.sampleIntervalNanos)).append(',');
        sb.append("\"gapUs\":").append(us(timeline.gapNanos)).append(',');
        sb.append("\"sampleCount\":").append(recording.totalSamples).append(',');
        sb.append("\"segmentCount\":").append(timeline.segmentCount).append(',');
        sb.append("\"threadCount\":").append(timeline.threads.size()).append(',');
        sb.append("\"maxStackDepth\":").append(maxDepth).append(',');
        field(sb, "matchStrategy", config.matchStrategy == Config.MatchStrategy.INNERMOST
                ? "innermost" : "config-order").append(',');
        field(sb, "configSource", Main.fileName(config.sourceName)).append(',');
        field(sb, "configText", configListing(sourceName, commandLine)).append(',');
        sb.append("\"eventTypes\":");
        stringArray(sb, recording.usedEventTypes);
        sb.append('}');

        sb.append(",\"groups\":");
        stringArray(sb, timeline.groupNames);

        sb.append(",\"states\":[");
        List<Classifier.State> states = classifier.states();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Classifier.State s = states.get(i);
            sb.append('{');
            field(sb, "n", s.name).append(',');
            field(sb, "c", s.color).append(',');
            field(sb, "k", s.kind).append(',');
            sb.append("\"t\":").append(us(timeline.totalStateTime[i]));
            if (s.description != null) {
                sb.append(',');
                field(sb, "d", s.description);
            }
            sb.append('}');
        }
        sb.append(']');

        sb.append(",\"frames\":[");
        for (int i = 0; i < keptFrames.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, recording.frames.name(keptFrames.get(i)));
        }
        sb.append(']');

        sb.append(",\"stacks\":[");
        for (int i = 0; i < outStacks.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            int[] st = outStacks[i];
            for (int j = 0; j < st.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(st[j]);
            }
            sb.append(']');
        }
        sb.append(']');

        sb.append(",\"stackMatch\":[");
        for (int i = 0; i < outMatch.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            int[] m = outMatch[i];
            for (int j = 0; j < m.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(m[j]);
            }
            sb.append(']');
        }
        sb.append(']');

        sb.append(",\"threads\":[");
        for (int ti = 0; ti < timeline.threads.size(); ti++) {
            Timeline.ThreadTimeline t = timeline.threads.get(ti);
            if (ti > 0) {
                sb.append(',');
            }
            sb.append('{');
            field(sb, "n", t.name).append(',');
            sb.append("\"j\":").append(t.javaId).append(',');
            sb.append("\"o\":").append(t.osId).append(',');
            sb.append("\"g\":").append(t.group).append(',');
            sb.append("\"s\":").append(t.sampleCount).append(',');
            sb.append("\"st\":[");
            for (int i = 0; i < t.stateTime.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(us(t.stateTime[i]));
            }
            sb.append("],\"seg\":[");
            long prevStart = 0;
            for (int i = 0; i < t.count; i++) {
                long s = us(t.start[i]);
                long e = us(t.end[i]);
                long dur = Math.max(1, e - s);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(s - prevStart).append(',')
                  .append(dur).append(',')
                  .append(t.state[i]).append(',')
                  .append(stackRemap[t.stackId[i]]).append(',')
                  .append(t.samples[i]);
                prevStart = s;
            }
            sb.append("]}");
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private static long us(long nanos) {
        return (nanos + 500) / 1000;
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        string(sb, value);
        return sb;
    }

    private static void stringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, values.get(i));
        }
        sb.append(']');
    }

    /** JSON string literal; {@code <} and {@code >} are escaped so the payload is script-tag safe. */
    static void string(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = HtmlWriter.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource missing from the jar: " + path);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
