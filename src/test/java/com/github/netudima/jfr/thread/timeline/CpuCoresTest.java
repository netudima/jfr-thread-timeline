package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.netudima.jfr.thread.timeline.TestRecordings.MS;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.build;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.config;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.empty;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.sample;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stack;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-core view reads async-profiler's {@code --record-cpu} synthetic frame. No reference
 * recording carries it, so the frame shapes are asserted directly rather than discovered from
 * data: {@code CPU-7} is what lands in JFR, {@code [CPU-7]} is the text spelling.
 */
class CpuCoresTest {

    private static final String CONF =
            "sampleIntervalMs: 10\n" +
            "gapThresholdMs: 25\n" +
            "states: [{name: Busy, frames: [Busy.loop]}]\n";

    private static final Log QUIET = new Log(Log.Level.QUIET);

    private static CpuCores cores(Recording rec) {
        Config c = config(CONF);
        Timeline tl = build(rec, c);
        return CpuCores.build(rec, tl, new Classifier(c, rec), QUIET);
    }

    /** async-profiler appends the core as a synthetic frame at the root of the stack. */
    private static int stackOn(Recording rec, String cpuFrame, String... javaFrames) {
        String[] all = new String[javaFrames.length + 1];
        System.arraycopy(javaFrames, 0, all, 0, javaFrames.length);
        all[javaFrames.length] = cpuFrame;
        return stack(rec, all);
    }

    private static void run(Recording rec, String threadName, String cpuFrame,
                            long fromMs, int count) {
        int s = stackOn(rec, cpuFrame, "com.example.Busy.loop");
        Recording.ThreadSamples t = thread(rec, threadName);
        for (int i = 0; i < count; i++) {
            sample(rec, t, fromMs + i * 10L, s, "STATE_RUNNABLE");
        }
    }

    @Test
    void withoutRecordCpuThereAreNoCores() {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop", "com.example.Main.main");
        Recording.ThreadSamples t = thread(rec, "main");
        for (int i = 0; i < 5; i++) {
            sample(rec, t, i * 10L, s, "STATE_RUNNABLE");
        }
        CpuCores c = cores(rec);
        assertTrue(c.isEmpty());
        assertEquals(0, c.sampleCount);
    }

    @Test
    void theJfrSpellingIsDetected() {
        Recording rec = empty();
        run(rec, "worker", "CPU-3", 0, 5);
        CpuCores c = cores(rec);
        assertEquals(List.of(3), c.coreIds);
        assertEquals(5, c.sampleCount);
    }

    /** The bracketed form is what async-profiler's own text output uses. */
    @Test
    void theBracketedSpellingIsAlsoDetected() {
        Recording rec = empty();
        run(rec, "worker", "[CPU-11]", 0, 4);
        assertEquals(List.of(11), cores(rec).coreIds);
    }

    @Test
    void coresAreListedInAscendingOrder() {
        Recording rec = empty();
        run(rec, "a", "CPU-7", 0, 3);
        run(rec, "b", "CPU-0", 0, 3);
        run(rec, "c", "CPU-12", 0, 3);
        assertEquals(List.of(0, 7, 12), cores(rec).coreIds);
    }

    @Test
    void eachCoreGetsTheThreadsThatRanOnIt() {
        Recording rec = empty();
        run(rec, "a", "CPU-0", 0, 5);      // 0..50 ms on core 0
        run(rec, "b", "CPU-0", 200, 5);    // 200..250 ms on core 0
        run(rec, "c", "CPU-1", 0, 5);      // core 1 throughout

        CpuCores c = cores(rec);
        assertEquals(List.of(0, 1), c.coreIds);
        assertEquals(2, c.cores.get(0).size(), "two separate threads occupied core 0");
        assertEquals(1, c.cores.get(1).size());
        assertEquals(0, c.cores.get(0).get(0).start);
        assertEquals(200 * MS, c.cores.get(0).get(1).start);
    }

    @Test
    void slicesOnACoreNeverOverlapAndAreOrdered() {
        Recording rec = empty();
        // three threads interleaved on one core, staggered so runs abut
        for (int i = 0; i < 3; i++) {
            run(rec, "t" + i, "CPU-2", i * 10L, 4);
        }
        List<CpuCores.Slice> lane = cores(rec).cores.get(0);
        for (int i = 1; i < lane.size(); i++) {
            assertTrue(lane.get(i).start >= lane.get(i - 1).end,
                    "slice " + i + " starts before the previous one ends");
        }
    }

    /** A run is broken when the thread changes, so a core row reads as a handover sequence. */
    @Test
    void consecutiveSamplesOfOneThreadCollapseIntoOneSlice() {
        Recording rec = empty();
        run(rec, "solo", "CPU-4", 0, 12);
        List<CpuCores.Slice> lane = cores(rec).cores.get(0);
        assertEquals(1, lane.size());
        assertEquals(12, lane.get(0).samples);
        assertEquals(0, lane.get(0).start);
        assertEquals(120 * MS, lane.get(0).end);
    }

    @Test
    void sliceThreadIndicesMatchTheReportedThreadOrder() {
        Recording rec = empty();
        run(rec, "alpha", "CPU-0", 0, 3);
        run(rec, "beta", "CPU-1", 0, 3);

        Config c = config(CONF);
        Timeline tl = build(rec, c);
        CpuCores cc = CpuCores.build(rec, tl, new Classifier(c, rec), QUIET);

        for (int i = 0; i < cc.cores.size(); i++) {
            for (CpuCores.Slice slice : cc.cores.get(i)) {
                assertTrue(slice.thread >= 0 && slice.thread < tl.threads.size());
                // core 0 ran alpha, core 1 ran beta
                String expected = cc.coreIds.get(i) == 0 ? "alpha" : "beta";
                assertEquals(expected, tl.threads.get(slice.thread).name);
            }
        }
    }

    /** The frame is looked for anywhere in the stack, not at a fixed end. */
    @Test
    void theCoreFrameIsFoundWhereverItSits() {
        Recording rec = empty();
        Recording.ThreadSamples t = thread(rec, "worker");
        int leafFirst = stack(rec, "CPU-5", "com.example.Busy.loop");   // at the leaf
        int rootLast = stack(rec, "com.example.Busy.loop", "CPU-5");    // at the root
        sample(rec, t, 0, leafFirst, "STATE_RUNNABLE");
        sample(rec, t, 10, rootLast, "STATE_RUNNABLE");

        CpuCores c = cores(rec);
        assertEquals(List.of(5), c.coreIds);
        assertEquals(2, c.sampleCount);
    }

    /**
     * A real {@code --record-cpu} capture also holds wall-clock samples, and async-profiler
     * writes a {@code CPU-0} frame on those even though a timer thread has no perf context.
     * Taking it at face value reported an entire 16-core recording as a single core.
     */
    @Test
    void wallClockSamplesNeverContributeCores() {
        Recording rec = empty();
        rec.usedEventTypes.clear();
        rec.usedEventTypes.add("profiler.WallClockSample");
        run(rec, "worker", "CPU-0", 0, 20);

        CpuCores c = cores(rec);
        assertTrue(c.isEmpty(), "wall-clock samples carry no real core id");
        assertEquals(0, c.sampleCount);
    }

    @Test
    void perfSamplesDoContributeCores() {
        Recording rec = empty();
        rec.usedEventTypes.clear();
        rec.usedEventTypes.add("jdk.ExecutionSample");
        run(rec, "worker", "CPU-6", 0, 5);
        assertEquals(List.of(6), cores(rec).coreIds);
    }

    @Test
    void lookalikeFramesAreNotMistakenForCores() {
        Recording rec = empty();
        Recording.ThreadSamples t = thread(rec, "worker");
        for (String frame : new String[]{"com.example.CPUMonitor.poll", "libjvm.so.CPU_count",
                                         "CPU-", "org.example.cpu.Sampler.run"}) {
            int s = stack(rec, frame, "com.example.Busy.loop");
            sample(rec, t, 0, s, "STATE_RUNNABLE");
        }
        assertTrue(cores(rec).isEmpty());
    }

    @Test
    void theReportCarriesTheCoreRowsWhenTheyExist() throws Exception {
        Recording rec = empty();
        run(rec, "alpha", "CPU-0", 0, 4);
        run(rec, "beta", "CPU-1", 0, 4);

        Config c = config(CONF);
        Timeline tl = build(rec, c);
        Classifier cl = new Classifier(c, rec);
        java.nio.file.Path out = java.nio.file.Files.createTempFile("cores", ".html");
        new HtmlWriter(c, cl, tl, rec, QUIET)
                .withCpuCores(CpuCores.build(rec, tl, cl, QUIET))
                .write(out, "t", "synthetic.jfr", null, HtmlWriter.Compression.NEVER);
        String html = new String(java.nio.file.Files.readAllBytes(out),
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.deleteIfExists(out);

        assertTrue(html.contains("\"cores\":{"), "the report must carry the core rows");
        assertTrue(html.contains("\"ids\":[0,1]"));
        assertTrue(html.contains("\"sampleCount\":8"));
    }

    @Test
    void aReportWithoutCoreDataOmitsTheBlockEntirely() throws Exception {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, s, "STATE_RUNNABLE");

        Config c = config(CONF);
        Timeline tl = build(rec, c);
        Classifier cl = new Classifier(c, rec);
        java.nio.file.Path out = java.nio.file.Files.createTempFile("nocores", ".html");
        new HtmlWriter(c, cl, tl, rec, QUIET)
                .withCpuCores(CpuCores.build(rec, tl, cl, QUIET))
                .write(out, "t", "synthetic.jfr", null, HtmlWriter.Compression.NEVER);
        String html = new String(java.nio.file.Files.readAllBytes(out),
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.deleteIfExists(out);

        assertFalse(html.contains("\"cores\":{"), "no core data means no core block");
    }
}
