package com.github.netudima.jfr.thread.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-core view: one row per physical CPU, showing which thread occupied it over time.
 *
 * <p>This needs async-profiler's {@code --record-cpu} (4.3+, perf-events engine on Linux). That
 * option turns on {@code PERF_SAMPLE_CPU} and encodes the core the sample was taken on as a
 * synthetic frame in the stack — written into JFR as {@code CPU-7}, and as {@code [CPU-7]} in
 * async-profiler's own text output. There is no event field to read; the core id has to be
 * picked out of the stack.
 *
 * <p>The frame is looked for anywhere in the stack rather than at a fixed end, so the view keeps
 * working if async-profiler moves it. Without {@code --record-cpu} no stack carries such a frame
 * and this builder simply yields nothing.
 */
public final class CpuCores {

    /**
     * Matches the synthetic core frame. {@code CPU-7} is the JFR spelling, {@code [CPU-7]} the
     * text one; a leading package is tolerated in case the frame ever gains a class. A real Java
     * frame cannot collide: {@code -} is not legal in an identifier.
     */
    private static final Pattern CPU_FRAME = Pattern.compile("^(?:.*\\.)?\\[?CPU-(\\d+)]?$");

    private static final int NO_CORE = -1;

    /** One uninterrupted run of a single thread in a single state on one core. */
    public static final class Slice {
        public final long start;
        public final long end;
        public final int thread;
        public final int state;
        public final int stack;
        public final int samples;

        Slice(long start, long end, int thread, int state, int stack, int samples) {
            this.start = start;
            this.end = end;
            this.thread = thread;
            this.state = state;
            this.stack = stack;
            this.samples = samples;
        }
    }

    /** Core ids as the kernel reported them, ascending. Not necessarily contiguous. */
    public final List<Integer> coreIds = new ArrayList<>();
    /** Slices per core, parallel to {@link #coreIds}, ordered by time and non-overlapping. */
    public final List<List<Slice>> cores = new ArrayList<>();
    public long sampleCount;
    public int sliceCount;
    public long busyTime;

    public boolean isEmpty() {
        return cores.isEmpty();
    }

    /** A raw sample pinned to a core, before run-length encoding. */
    private static final class Point {
        final long time;
        final int thread;
        final int state;
        final int stack;

        Point(long time, int thread, int state, int stack) {
            this.time = time;
            this.thread = thread;
            this.state = state;
            this.stack = stack;
        }
    }

    /**
     * Event types whose samples can carry a real core id. {@code --record-cpu} reads the CPU out
     * of the perf ring buffer, so only the perf-events engine has one. Wall-clock samples are
     * taken by a timer thread with no perf context and async-profiler still writes a {@code CPU-0}
     * frame on them — taking that at face value would report the whole recording as one core.
     */
    private static final List<String> PERF_EVENT_TYPES =
            Arrays.asList("jdk.ExecutionSample", "jdk.NativeMethodSample");

    private static boolean carriesCoreIds(Recording rec) {
        for (String type : rec.usedEventTypes) {
            if (PERF_EVENT_TYPES.contains(type)) {
                return true;
            }
        }
        return false;
    }

    public static CpuCores build(Recording rec, Timeline tl, Classifier classifier, Log log) {
        CpuCores out = new CpuCores();

        if (!carriesCoreIds(rec)) {
            // A --record-cpu recording usually also holds wall-clock samples, and those win the
            // timeline. Say so rather than silently dropping the view.
            for (int f = 0; f < rec.frames.size(); f++) {
                if (CPU_FRAME.matcher(rec.frames.name(f)).matches()) {
                    log.info("the timeline is built from " + String.join(", ", rec.usedEventTypes)
                            + ", which carries no real core id; for the per-core view re-run with"
                            + " --event-type jdk.ExecutionSample");
                    break;
                }
            }
            return out;
        }

        // frame -> core, computed once per unique frame
        int nFrames = rec.frames.size();
        int[] frameCore = new int[nFrames];
        int cpuFrames = 0;
        for (int f = 0; f < nFrames; f++) {
            Matcher m = CPU_FRAME.matcher(rec.frames.name(f));
            if (m.matches()) {
                frameCore[f] = Integer.parseInt(m.group(1));
                cpuFrames++;
            } else {
                frameCore[f] = NO_CORE;
            }
        }
        if (cpuFrames == 0) {
            return out;   // recorded without --record-cpu
        }

        // stack -> core, once per unique stack
        int nStacks = rec.stacks.size();
        int[] stackCore = new int[nStacks];
        for (int s = 0; s < nStacks; s++) {
            stackCore[s] = NO_CORE;
            for (int f : rec.stacks.frames(s)) {
                if (frameCore[f] != NO_CORE) {
                    stackCore[s] = frameCore[f];
                    break;
                }
            }
        }

        // the thread indices used here must match the order threads are written to the report
        IdentityHashMap<Recording.ThreadSamples, Integer> threadIndex = new IdentityHashMap<>();
        for (int i = 0; i < tl.threads.size(); i++) {
            Recording.ThreadSamples src = tl.threads.get(i).source;
            if (src != null) {
                threadIndex.put(src, i);
            }
        }

        long base = rec.startNanos;
        Map<Integer, List<Point>> byCore = new HashMap<>();
        for (Recording.ThreadSamples ts : rec.threads()) {
            Integer ti = threadIndex.get(ts);
            if (ti == null) {
                continue;
            }
            for (int i = 0; i < ts.size(); i++) {
                int core = stackCore[ts.stackId(i)];
                if (core == NO_CORE) {
                    continue;
                }
                byCore.computeIfAbsent(core, k -> new ArrayList<>())
                        .add(new Point(ts.time(i) - base, ti,
                                classifier.stateOf(ts.stackId(i), ts.threadState(i)), ts.stackId(i)));
                out.sampleCount++;
            }
        }
        if (byCore.isEmpty()) {
            return out;
        }

        long nominal = tl.sampleIntervalNanos;
        long gap = tl.gapNanos;
        List<Integer> ids = new ArrayList<>(byCore.keySet());
        ids.sort(null);

        for (int core : ids) {
            List<Point> points = byCore.get(core);
            points.sort((a, b) -> Long.compare(a.time, b.time));

            List<Slice> slices = new ArrayList<>();
            long lastEnd = Long.MIN_VALUE;
            int i = 0;
            while (i < points.size()) {
                Point head = points.get(i);
                // a run is consecutive samples of the same thread in the same state
                int j = i + 1;
                while (j < points.size()
                        && points.get(j).thread == head.thread
                        && points.get(j).state == head.state
                        && points.get(j).time - points.get(j - 1).time <= gap) {
                    j++;
                }
                Point tail = points.get(j - 1);
                long end = j < points.size()
                        ? Math.min(points.get(j).time, tail.time + nominal)
                        : tail.time + nominal;

                // A core runs one thread at a time, so slices must not overlap. Two samples can
                // still share a timestamp on one core - clock granularity, or the profiler
                // catching a context switch - and the later one is then dropped rather than
                // allowed to straddle its neighbour.
                long start = Math.max(head.time, lastEnd);
                if (end > start) {
                    slices.add(new Slice(start, end, head.thread, head.state, head.stack, j - i));
                    out.busyTime += end - start;
                    lastEnd = end;
                }
                i = j;
            }

            out.coreIds.add(core);
            out.cores.add(slices);
            out.sliceCount += slices.size();
        }

        log.info(String.format("cpu cores: %d cores carried %,d samples in %,d slices (--record-cpu)",
                out.coreIds.size(), out.sampleCount, out.sliceCount));
        return out;
    }

    /** Highest core id seen, useful for spotting cores that never ran the profiled process. */
    public int maxCoreId() {
        int max = -1;
        for (int id : coreIds) {
            max = Math.max(max, id);
        }
        return max;
    }

    @Override
    public String toString() {
        return "CpuCores" + Arrays.toString(coreIds.toArray());
    }
}
