package com.github.netudima.jfr.thread.timeline;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything read out of a JFR file: interned frames, interned stacks and, per thread,
 * the time-ordered list of samples.
 */
public final class Recording {

    /** Sample-carrying event types we look at when the config does not name any. */
    static final List<String> AUTO_EVENT_TYPES = Arrays.asList(
            "profiler.WallClockSample",   // async-profiler wall-clock engine (best: covers every thread)
            "jdk.ExecutionSample",        // cpu / itimer / ctimer engine, and the JDK's own sampler
            "jdk.NativeMethodSample");

    // ---- interning tables ---------------------------------------------------

    /** Frame strings ({@code pkg.Class.method}), interned to dense ids. */
    public static final class FrameTable {
        private final Map<String, Integer> index = new HashMap<>(1 << 14);
        private final List<String> names = new ArrayList<>();

        int intern(String frame) {
            Integer id = index.get(frame);
            if (id != null) {
                return id;
            }
            int next = names.size();
            names.add(frame);
            index.put(frame, next);
            return next;
        }

        public int size() {
            return names.size();
        }

        public String name(int id) {
            return names.get(id);
        }

        public List<String> names() {
            return names;
        }
    }

    /** Stacks (leaf-first arrays of frame ids), interned to dense ids. */
    public static final class StackTable {
        private final Map<Key, Integer> index = new HashMap<>(1 << 14);
        private final List<int[]> stacks = new ArrayList<>();

        private static final class Key {
            final int[] frames;
            final int hash;
            Key(int[] frames) {
                this.frames = frames;
                this.hash = Arrays.hashCode(frames);
            }
            @Override public boolean equals(Object o) {
                return o instanceof Key && Arrays.equals(frames, ((Key) o).frames);
            }
            @Override public int hashCode() {
                return hash;
            }
        }

        int intern(int[] frames) {
            Key key = new Key(frames);
            Integer id = index.get(key);
            if (id != null) {
                return id;
            }
            int next = stacks.size();
            stacks.add(frames);
            index.put(key, next);
            return next;
        }

        public int size() {
            return stacks.size();
        }

        public int[] frames(int id) {
            return stacks.get(id);
        }
    }

    /** Time-ordered samples belonging to one thread. */
    public static final class ThreadSamples {
        public final String name;
        public final long javaId;
        public final long osId;

        long[] time = new long[64];        // nanos since epoch
        long[] duration = new long[64];    // nanos, 0 for instantaneous samples
        int[] stackId = new int[64];
        int[] threadState = new int[64];   // index into Recording.threadStates
        int[] eventType = new int[64];     // index into Recording.eventTypeNames
        int size;

        ThreadSamples(String name, long javaId, long osId) {
            this.name = name;
            this.javaId = javaId;
            this.osId = osId;
        }

        void add(long t, long dur, int stack, int state, int type) {
            if (size == time.length) {
                int cap = size + (size >> 1) + 16;
                time = Arrays.copyOf(time, cap);
                duration = Arrays.copyOf(duration, cap);
                stackId = Arrays.copyOf(stackId, cap);
                threadState = Arrays.copyOf(threadState, cap);
                eventType = Arrays.copyOf(eventType, cap);
            }
            time[size] = t;
            duration[size] = dur;
            stackId[size] = stack;
            threadState[size] = state;
            eventType[size] = type;
            size++;
        }

        public int size() { return size; }
        public long time(int i) { return time[i]; }
        public long duration(int i) { return duration[i]; }
        public int stackId(int i) { return stackId[i]; }
        public int threadState(int i) { return threadState[i]; }

        /** Sorts samples by timestamp; JFR only guarantees ordering within a chunk. */
        void sortByTime() {
            Integer[] order = new Integer[size];
            boolean sorted = true;
            for (int i = 0; i < size; i++) {
                order[i] = i;
                if (i > 0 && time[i] < time[i - 1]) {
                    sorted = false;
                }
            }
            if (sorted) {
                return;
            }
            Arrays.sort(order, (a, b) -> Long.compare(time[a], time[b]));
            long[] t2 = new long[size];
            long[] d2 = new long[size];
            int[] s2 = new int[size];
            int[] st2 = new int[size];
            int[] e2 = new int[size];
            for (int i = 0; i < size; i++) {
                int src = order[i];
                t2[i] = time[src];
                d2[i] = duration[src];
                s2[i] = stackId[src];
                st2[i] = threadState[src];
                e2[i] = eventType[src];
            }
            time = t2; duration = d2; stackId = s2; threadState = st2; eventType = e2;
        }

        /** Drops every sample whose event type is not in {@code keep}, preserving order. */
        void retainEventTypes(boolean[] keep) {
            int w = 0;
            for (int i = 0; i < size; i++) {
                if (keep[eventType[i]]) {
                    time[w] = time[i];
                    duration[w] = duration[i];
                    stackId[w] = stackId[i];
                    threadState[w] = threadState[i];
                    eventType[w] = eventType[i];
                    w++;
                }
            }
            size = w;
        }
    }

    public final FrameTable frames = new FrameTable();
    public final StackTable stacks = new StackTable();
    /** Raw JFR thread-state tokens, interned. */
    public final List<String> threadStates = new ArrayList<>();
    public final List<String> eventTypeNames = new ArrayList<>();
    /** The subset of {@link #eventTypeNames} that actually fed the timeline. */
    public final List<String> usedEventTypes = new ArrayList<>();
    public final Map<String, Long> eventTypeCounts = new LinkedHashMap<>();

    private final Map<Long, ThreadSamples> threadsByKey = new LinkedHashMap<>();
    private final Map<String, Integer> threadStateIndex = new HashMap<>();
    private final Map<String, Integer> eventTypeIndex = new HashMap<>();

    public long startNanos = Long.MAX_VALUE;
    public long endNanos = Long.MIN_VALUE;
    public long totalSamples;
    /** Samples skipped because they fell outside {@code --from}/{@code --to}. */
    public long skippedOutOfWindow;
    public long skippedByThreadFilter;

    public Collection<ThreadSamples> threads() {
        return threadsByKey.values();
    }

    /** Registers (or looks up) a thread. Also the entry point used when building test data. */
    ThreadSamples thread(String name, long javaId, long osId) {
        long key = osId != 0 ? osId : -(javaId + 1);
        ThreadSamples ts = threadsByKey.get(key);
        if (ts == null) {
            ts = new ThreadSamples(name, javaId, osId);
            threadsByKey.put(key, ts);
        }
        return ts;
    }

    public String threadStateName(int id) {
        return threadStates.get(id);
    }

    // ---- reading ------------------------------------------------------------

    /**
     * Reads {@code file}, keeping only sample events of the requested types.
     *
     * @param requestedTypes event types named in the config; empty means auto-detect
     * @param fromNanos      absolute lower time bound, or {@code Long.MIN_VALUE}
     * @param toNanos        absolute upper time bound, or {@code Long.MAX_VALUE}
     */
    public static Recording read(Path file, Config config, List<String> requestedTypes,
                                 long fromNanos, long toNanos, Log log) throws IOException {
        Recording rec = new Recording();
        boolean auto = requestedTypes.isEmpty();
        List<String> candidates = auto ? AUTO_EVENT_TYPES : requestedTypes;

        int[] scratch = new int[1024];
        long read = 0;
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e;
                try {
                    e = rf.readEvent();
                } catch (IOException io) {
                    log.warn("stopped reading after " + read + " events: " + io.getMessage());
                    break;
                }
                read++;
                EventInfo info = rec.eventInfo.get(e.getEventType());
                if (info == null) {
                    info = rec.describe(e, candidates);
                }
                rec.eventTypeCounts.merge(info.name, 1L, Long::sum);
                if (!info.wanted) {
                    continue;
                }
                if (info.preferred) {
                    rec.preferredSeen = true;
                } else if (auto && rec.preferredSeen) {
                    // Wall-clock samples already cover every thread; stop paying for the rest.
                    continue;
                }
                scratch = rec.accept(e, info, config, fromNanos, toNanos, scratch);
            }
        }

        rec.chooseEventTypes(auto, requestedTypes, log);
        for (ThreadSamples ts : rec.threadsByKey.values()) {
            ts.sortByTime();
        }
        rec.threadsByKey.values().removeIf(ts -> ts.size == 0);
        rec.recomputeBounds();
        return rec;
    }

    /** Per event-type facts, looked up by identity so it costs nothing per event. */
    private static final class EventInfo {
        final String name;
        final boolean wanted;
        final boolean preferred;
        final boolean sampledThreadField;
        final boolean durationField;
        final boolean stateField;
        final int typeId;

        EventInfo(String name, boolean wanted, boolean preferred, boolean sampledThreadField,
                  boolean durationField, boolean stateField, int typeId) {
            this.name = name;
            this.wanted = wanted;
            this.preferred = preferred;
            this.sampledThreadField = sampledThreadField;
            this.durationField = durationField;
            this.stateField = stateField;
            this.typeId = typeId;
        }
    }

    private final IdentityHashMap<jdk.jfr.EventType, EventInfo> eventInfo = new IdentityHashMap<>();
    /** Constant-pool objects are cached per chunk, so identity lookups avoid re-walking stacks. */
    private final IdentityHashMap<RecordedStackTrace, Integer> stackCache = new IdentityHashMap<>();
    private final IdentityHashMap<RecordedThread, ThreadSamples> threadCache = new IdentityHashMap<>();
    private static final ThreadSamples REJECTED = new ThreadSamples("<rejected>", 0, 0);
    private boolean preferredSeen;

    private EventInfo describe(RecordedEvent e, List<String> candidates) {
        jdk.jfr.EventType et = e.getEventType();
        String name = et.getName();
        boolean wanted = candidates.contains(name);
        int typeId = -1;
        if (wanted) {
            typeId = eventTypeIndex.computeIfAbsent(name, k -> {
                eventTypeNames.add(k);
                return eventTypeNames.size() - 1;
            });
        }
        EventInfo info = new EventInfo(name, wanted,
                wanted && name.equals("profiler.WallClockSample"),
                e.hasField("sampledThread"), e.hasField("duration"), e.hasField("state"), typeId);
        eventInfo.put(et, info);
        return info;
    }

    private int[] accept(RecordedEvent e, EventInfo info, Config config,
                         long fromNanos, long toNanos, int[] scratch) {
        RecordedThread thread = info.sampledThreadField ? e.getThread("sampledThread") : e.getThread();
        if (thread == null) {
            return scratch;
        }

        ThreadSamples ts = threadCache.get(thread);
        if (ts == null) {
            String name = threadName(thread);
            if (!config.acceptsThread(name)) {
                threadCache.put(thread, REJECTED);
                skippedByThreadFilter++;
                return scratch;
            }
            ts = thread(name, thread.getJavaThreadId(), thread.getOSThreadId());
            threadCache.put(thread, ts);
        } else if (ts == REJECTED) {
            skippedByThreadFilter++;
            return scratch;
        }

        Instant start = e.getStartTime();
        long t = start.getEpochSecond() * 1_000_000_000L + start.getNano();
        if (t < fromNanos || t > toNanos) {
            skippedOutOfWindow++;
            return scratch;
        }

        long dur = 0;
        if (info.durationField) {
            Duration d = e.getDuration();
            if (d != null && !d.isNegative()) {
                dur = d.toNanos();
            }
        }

        RecordedStackTrace st = e.getStackTrace();
        int stackId;
        if (st == null) {
            stackId = stacks.intern(EMPTY);
        } else {
            Integer cached = stackCache.get(st);
            if (cached != null) {
                stackId = cached;
            } else {
                int need = st.getFrames().size();
                if (need > scratch.length) {
                    scratch = new int[Math.max(scratch.length * 2, need + 16)];
                }
                stackId = internStack(st, scratch);
                stackCache.put(st, stackId);
            }
        }

        int stateId = internThreadState(info.stateField ? e.getString("state") : null);
        ts.add(t, dur, stackId, stateId, info.typeId);
        totalSamples++;
        return scratch;
    }

    private static final int[] EMPTY = new int[0];

    private int internStack(RecordedStackTrace st, int[] scratch) {
        List<RecordedFrame> fs = st.getFrames();
        int n = fs.size();
        for (int i = 0; i < n; i++) {
            scratch[i] = frames.intern(frameName(fs.get(i)));
        }
        return stacks.intern(Arrays.copyOf(scratch, n));
    }

    int internThreadState(String raw) {
        String key = raw == null || raw.equals("null") ? "" : raw;
        Integer id = threadStateIndex.get(key);
        if (id != null) {
            return id;
        }
        int next = threadStates.size();
        threadStates.add(key);
        threadStateIndex.put(key, next);
        return next;
    }

    /** Picks which of the collected event types actually feed the timeline. */
    private void chooseEventTypes(boolean auto, List<String> requested, Log log) {
        if (eventTypeNames.isEmpty()) {
            return;
        }
        List<String> chosen;
        if (!auto) {
            chosen = new ArrayList<>(eventTypeNames);
        } else if (eventTypeNames.contains("profiler.WallClockSample")) {
            // Wall-clock samples cover every thread including the blocked ones - always preferred.
            chosen = new ArrayList<>();
            chosen.add("profiler.WallClockSample");
        } else {
            chosen = new ArrayList<>(eventTypeNames);
        }
        if (chosen.size() != eventTypeNames.size()) {
            List<String> dropped = new ArrayList<>(eventTypeNames);
            dropped.removeAll(chosen);
            log.info("using event type(s) " + String.join(", ", chosen)
                    + "; ignoring " + String.join(", ", dropped)
                    + " (override with 'eventTypes' in the config)");
        } else {
            log.info("using event type(s) " + String.join(", ", chosen));
        }

        usedEventTypes.clear();
        usedEventTypes.addAll(chosen);
        boolean[] keep = new boolean[eventTypeNames.size()];
        for (String c : chosen) {
            int i = eventTypeNames.indexOf(c);
            if (i >= 0) {
                keep[i] = true;
            }
        }
        long kept = 0;
        for (ThreadSamples ts : threadsByKey.values()) {
            ts.retainEventTypes(keep);
            kept += ts.size;
        }
        totalSamples = kept;
        if (!requested.isEmpty()) {
            for (String r : requested) {
                if (!eventTypeNames.contains(r)) {
                    log.warn("event type '" + r + "' from the config was not found in the recording");
                }
            }
        }
    }

    /** Restricts the recording to {@code [start + relFrom, start + relTo]}. */
    public void applyWindow(long relFrom, long relTo) {
        long base = startNanos;
        long lo = relFrom == Long.MIN_VALUE ? Long.MIN_VALUE : base + relFrom;
        long hi = relTo == Long.MAX_VALUE ? Long.MAX_VALUE : base + relTo;
        long kept = 0;
        for (ThreadSamples ts : threadsByKey.values()) {
            int w = 0;
            for (int i = 0; i < ts.size; i++) {
                long t = ts.time[i];
                if (t < lo || t > hi) {
                    skippedOutOfWindow++;
                    continue;
                }
                ts.time[w] = t;
                ts.duration[w] = ts.duration[i];
                ts.stackId[w] = ts.stackId[i];
                ts.threadState[w] = ts.threadState[i];
                ts.eventType[w] = ts.eventType[i];
                w++;
            }
            ts.size = w;
            kept += w;
        }
        totalSamples = kept;
        threadsByKey.values().removeIf(ts -> ts.size == 0);
        recomputeBounds();
        if (lo != Long.MIN_VALUE && startNanos > lo) {
            startNanos = lo;   // keep the window's own origin so --from lines up with 0
        }
        if (hi != Long.MAX_VALUE) {
            endNanos = Math.min(endNanos, hi);
        }
        endNanos = Math.max(endNanos, startNanos);
    }

    /** Keeps only the {@code n} threads with the most samples. */
    public void keepBusiestThreads(int n) {
        if (n >= threadsByKey.size()) {
            return;
        }
        List<ThreadSamples> all = new ArrayList<>(threadsByKey.values());
        all.sort((a, b) -> Integer.compare(b.size, a.size));
        java.util.Set<ThreadSamples> keep = new java.util.HashSet<>(all.subList(0, Math.max(0, n)));
        threadsByKey.values().removeIf(ts -> !keep.contains(ts));
        long kept = 0;
        for (ThreadSamples ts : threadsByKey.values()) {
            kept += ts.size;
        }
        totalSamples = kept;
        recomputeBounds();
    }

    void recomputeBounds() {
        startNanos = Long.MAX_VALUE;
        endNanos = Long.MIN_VALUE;
        for (ThreadSamples ts : threadsByKey.values()) {
            if (ts.size == 0) {
                continue;
            }
            startNanos = Math.min(startNanos, ts.time[0]);
            long last = ts.time[ts.size - 1] + Math.max(0, ts.duration[ts.size - 1]);
            endNanos = Math.max(endNanos, last);
        }
        if (startNanos == Long.MAX_VALUE) {
            startNanos = 0;
            endNanos = 0;
        }
    }

    static String threadName(RecordedThread t) {
        String n = t.getJavaName();
        if (n == null || n.isEmpty()) {
            n = t.getOSName();
        }
        if (n == null || n.isEmpty()) {
            long os = t.getOSThreadId();
            n = os != 0 ? "tid-" + os : "thread-" + t.getJavaThreadId();
        }
        return n;
    }

    /** OS thread id identifies a thread best; fall back to the Java id when it is absent. */
    private static long threadKey(RecordedThread t) {
        long os = t.getOSThreadId();
        return os != 0 ? os : -(t.getJavaThreadId() + 1);
    }

    static String frameName(RecordedFrame f) {
        RecordedMethod m = f.getMethod();
        if (m == null) {
            return "<unknown>";
        }
        String method = m.getName();
        String type = m.getType() != null ? m.getType().getName() : null;
        if (type == null || type.isEmpty()) {
            return method == null || method.isEmpty() ? "<unknown>" : method;
        }
        if (method == null || method.isEmpty()) {
            return type;
        }
        return type + "." + method;
    }
}
