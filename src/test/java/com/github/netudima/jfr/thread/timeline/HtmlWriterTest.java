package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static com.github.netudima.jfr.thread.timeline.TestRecordings.build;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.config;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.empty;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.sample;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stack;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlWriterTest {

    private static final String CONF =
            "sampleIntervalMs: 10\n" +
            "gapThresholdMs: 25\n" +
            "states:\n" +
            "  - {name: Lock wait, color: \"#e15759\", frames: [LockSupport.park]}\n";

    private String render(@TempDir Path dir, HtmlWriter.Compression mode, String threadName) throws IOException {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park", "com.example.Main.main");
        Recording.ThreadSamples t = thread(rec, threadName);
        for (int i = 0; i < 5; i++) {
            sample(rec, t, i * 10, park, "STATE_RUNNABLE");
        }
        Config c = config(CONF);
        Timeline tl = build(rec, c);
        Path out = dir.resolve("out.html");
        new HtmlWriter(c, new Classifier(c, rec), tl, rec, new Log(Log.Level.QUIET))
                .write(out, "unit test", "synthetic.jfr", mode);
        return new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
    }

    @Test
    void producesASelfContainedPageWithInlineJson(@TempDir Path dir) throws IOException {
        String html = render(dir, HtmlWriter.Compression.NEVER, "main");

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("data-mode=\"json\""));
        assertTrue(html.contains("\"n\":\"Lock wait\""));
        assertTrue(html.contains("java.util.concurrent.locks.LockSupport.park"));
        assertFalse(html.contains("{{"), "every template placeholder must be substituted");
        assertFalse(html.contains("src=\"http"), "the page must not reference anything external");
        assertFalse(html.contains("href=\"http"), "the page must not reference anything external");
    }

    @Test
    void gzippedPayloadRoundTrips(@TempDir Path dir) throws IOException {
        String html = render(dir, HtmlWriter.Compression.ALWAYS, "main");
        assertTrue(html.contains("data-mode=\"gzip-base64\""));

        int start = html.indexOf("data-mode=\"gzip-base64\">") + "data-mode=\"gzip-base64\">".length();
        String payload = html.substring(start, html.indexOf("</script>", start)).trim();
        byte[] gz = Base64.getDecoder().decode(payload);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"meta\":{"));
        assertTrue(json.contains("\"sampleCount\":5"));
        assertTrue(json.contains("\"threadCount\":1"));
    }

    /** A thread name is attacker-controlled data; it must never break out of the script tag. */
    @Test
    void payloadIsScriptTagSafe(@TempDir Path dir) throws IOException {
        String html = render(dir, HtmlWriter.Compression.NEVER, "evil</script><script>alert(1)</script>");
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("\\u003c/script\\u003e"));
    }

    /** The report carries the config verbatim, so a reader can copy it back out and re-run. */
    @Test
    void theConfigIsEmbeddedAndStillParses(@TempDir Path dir) throws IOException {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");

        Config c = Config.load(CONF, "states.yaml");
        Timeline tl = build(rec, c);
        Path out = dir.resolve("out.html");
        new HtmlWriter(c, new Classifier(c, rec), tl, rec, new Log(Log.Level.QUIET))
                .write(out, "t", "synthetic.jfr", "jfr-thread-timeline -c states.yaml synthetic.jfr",
                        HtmlWriter.Compression.NEVER);
        String html = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);

        assertTrue(html.contains("\"configSource\":\"states.yaml\""));
        int i = html.indexOf("\"configText\":\"");
        assertTrue(i > 0, "the config text must be embedded");
        String encoded = html.substring(i + 14, html.indexOf("\",\"", i));
        String listing = encoded.replace("\\n", "\n").replace("\\\"", "\"")
                .replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&")
                .replace("\\\\", "\\");

        assertTrue(listing.contains("# recording : synthetic.jfr"));
        assertTrue(listing.contains("# config    : states.yaml"));
        assertTrue(listing.contains("# command   : jfr-thread-timeline -c states.yaml synthetic.jfr"));
        assertTrue(listing.contains("name: Lock wait"), "the original rules must be present");

        // the listing is only comments plus the original file, so it must still load
        Config reparsed = Config.load(listing, "copied");
        assertEquals(c.states.size(), reparsed.states.size());
        assertEquals("Lock wait", reparsed.states.get(0).name);
    }

    /**
     * Reports get shared, so nothing about the machine that produced them may leak into the
     * page: file arguments are reduced to their bare name, everything else is left alone.
     */
    @Test
    void embeddedPathsAreReducedToFileNames(@TempDir Path dir) throws IOException {
        String[] args = {"-c", "/home/alice/secret-project/states.yaml",
                         "-o", "/home/alice/reports/out.html",
                         "--threads", "re:^worker/pool-[0-9]+",
                         "/mnt/nfs/customer-x/prod-node-7.jfr"};
        java.util.Set<Integer> pathArgs = new java.util.HashSet<>(java.util.Arrays.asList(1, 3, 6));

        String cmd = Main.commandLine(args, pathArgs);

        assertFalse(cmd.contains("/home/alice"), cmd);
        assertFalse(cmd.contains("/mnt/nfs"), cmd);
        assertFalse(cmd.contains("secret-project"), cmd);
        assertFalse(cmd.contains("customer-x"), cmd);
        assertTrue(cmd.contains("states.yaml"));
        assertTrue(cmd.contains("out.html"));
        assertTrue(cmd.contains("prod-node-7.jfr"));
        assertTrue(cmd.contains("re:^worker/pool-[0-9]+"),
                "a non-path argument containing a slash must survive untouched");
    }

    @Test
    void fileNameHandlesAwkwardInput() {
        assertEquals("a.jfr", Main.fileName("/x/y/a.jfr"));
        assertEquals("a.jfr", Main.fileName("C:\\users\\bob\\a.jfr"));
        assertEquals("a.jfr", Main.fileName("a.jfr"));
        assertEquals("/", Main.fileName("/"), "a bare separator has no name to fall back to");
        assertEquals("", Main.fileName(""));
        assertNull(Main.fileName(null));
    }

    @Test
    void theConfigPathInTheReportIsAlsoJustAFileName(@TempDir Path dir) throws IOException {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");

        Config c = Config.load(CONF, "/home/alice/secret-project/states.yaml");
        Timeline tl = build(rec, c);
        Path out = dir.resolve("out.html");
        new HtmlWriter(c, new Classifier(c, rec), tl, rec, new Log(Log.Level.QUIET))
                .write(out, "t", "prod-node-7.jfr", "jfr-thread-timeline states.yaml",
                        HtmlWriter.Compression.NEVER);
        String html = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);

        assertFalse(html.contains("/home/alice"), "the config's directory must not reach the page");
        assertFalse(html.contains("secret-project"));
        assertTrue(html.contains("\"configSource\":\"states.yaml\""));
        assertTrue(html.contains("# config    : states.yaml"));
    }

    @Test
    void segmentStartsAreDeltaEncodedInMicroseconds(@TempDir Path dir) throws IOException {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        int other = stack(rec, "com.example.Busy.spin");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 10, other, "STATE_RUNNABLE");
        sample(rec, t, 20, park, "STATE_RUNNABLE");

        Config c = config(CONF);
        Timeline tl = build(rec, c);
        Path out = dir.resolve("out.html");
        new HtmlWriter(c, new Classifier(c, rec), tl, rec, new Log(Log.Level.QUIET))
                .write(out, "t", "synthetic.jfr", HtmlWriter.Compression.NEVER);
        String html = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);

        int i = html.indexOf("\"seg\":[");
        String seg = html.substring(i + 7, html.indexOf(']', i));
        String[] parts = seg.split(",");
        assertEquals(15, parts.length, "three segments of five numbers each");
        assertEquals("0", parts[0], "the first delta is absolute");
        assertEquals("10000", parts[1], "10 ms expressed in microseconds");
        assertEquals("10000", parts[5], "second segment starts 10 ms after the first");
    }
}
