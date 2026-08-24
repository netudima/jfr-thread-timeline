package com.github.netudima.jfr.thread.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run-length encodes each thread's samples into coloured segments.
 *
 * <p>A sample is assumed to describe the thread until the next sample arrives. If the next sample
 * is further away than the gap threshold the thread was not observed in between, so the segment is
 * closed after one nominal sampling interval and the timeline shows a blank gap.
 */
public final class Timeline {

    /** One contiguous run of samples that share a state, for a single thread. */
    public static final class ThreadTimeline {
        public final String name;
        public final long javaId;
        public final long osId;
        /** Index into {@link Timeline#groupNames}, or -1 when grouping is not configured. */
        public int group = -1;
        /** The samples this timeline was built from; lets other views line up thread indices. */
        Recording.ThreadSamples source;

        public long[] start;      // nanos relative to the recording start
        public long[] end;
        public int[] state;
        public int[] stackId;
        public int[] samples;
        public int count;

        public long[] stateTime;  // total nanos per state index
        public long coveredTime;  // sum of all segment durations
        public int sampleCount;
        public long firstSample;
        public long lastSample;

        ThreadTimeline(String name, long javaId, long osId, int stateCount) {
            this.name = name;
            this.javaId = javaId;
            this.osId = osId;
            this.start = new long[16];
            this.end = new long[16];
            this.state = new int[16];
            this.stackId = new int[16];
            this.samples = new int[16];
            this.stateTime = new long[stateCount];
        }

        private void add(long s, long e, int st, int stack, int n) {
            if (count == start.length) {
                int cap = count + (count >> 1) + 16;
                start = Arrays.copyOf(start, cap);
                end = Arrays.copyOf(end, cap);
                state = Arrays.copyOf(state, cap);
                stackId = Arrays.copyOf(stackId, cap);
                samples = Arrays.copyOf(samples, cap);
            }
            start[count] = s;
            end[count] = e;
            state[count] = st;
            stackId[count] = stack;
            samples[count] = n;
            count++;
            stateTime[st] += e - s;
            coveredTime += e - s;
        }

        public double dominantShare(int stateIndex) {
            return coveredTime == 0 ? 0 : (double) stateTime[stateIndex] / coveredTime;
        }
    }

    public final List<ThreadTimeline> threads = new ArrayList<>();
    /** Group display names with the catch-all last; empty when no groups are configured. */
    public List<String> groupNames = new ArrayList<>();
    public final long[] totalStateTime;
    public long durationNanos;
    public long sampleIntervalNanos;
    public long gapNanos;
    public int segmentCount;

    private Timeline(int stateCount) {
        this.totalStateTime = new long[stateCount];
    }

    public static Timeline build(Recording rec, Classifier classifier, Config config, Log log) {
        int stateCount = classifier.states().size();
        Timeline tl = new Timeline(stateCount);

        long nominal = config.sampleIntervalMs != null
                ? (long) (config.sampleIntervalMs * 1_000_000d)
                : estimateInterval(rec);
        if (nominal <= 0) {
            nominal = 10_000_000L;   // 10 ms
        }
        long gap = config.gapThresholdMs != null
                ? (long) (config.gapThresholdMs * 1_000_000d)
                : Math.max(nominal * 5 / 2, nominal + 1_000_000L);

        tl.sampleIntervalNanos = nominal;
        tl.gapNanos = gap;
        log.info(String.format("sample interval ~%.2f ms, gap threshold %.2f ms",
                nominal / 1e6, gap / 1e6));

        long base = rec.startNanos;
        Map<Integer, Integer> stackVotes = new HashMap<>();

        for (Recording.ThreadSamples ts : rec.threads()) {
            if (ts.size() == 0) {
                continue;
            }
            ThreadTimeline t = new ThreadTimeline(ts.name, ts.javaId, ts.osId, stateCount);
            t.group = config.groupOf(ts.name);
            t.source = ts;
            t.sampleCount = ts.size();
            t.firstSample = ts.time(0) - base;
            t.lastSample = ts.time(ts.size() - 1) - base;

            // Open segment being accumulated.
            boolean open = false;
            long segStart = 0;
            long segEnd = 0;
            int segState = -1;
            int segSamples = 0;
            stackVotes.clear();

            int i = 0;
            int n = ts.size();
            while (i < n) {
                long pointTime = ts.time(i);
                // Collapse samples that share a timestamp into one point.
                int j = i;
                long maxDuration = 0;
                while (j < n && ts.time(j) == pointTime) {
                    maxDuration = Math.max(maxDuration, ts.duration(j));
                    j++;
                }
                int pointSamples = j - i;
                int stackId = ts.stackId(i);
                int state = classifier.stateOf(stackId, ts.threadState(i));

                long nextTime = j < n ? ts.time(j) : Long.MIN_VALUE;
                long pointEnd;
                if (maxDuration > 0) {
                    pointEnd = pointTime + maxDuration;
                    if (nextTime != Long.MIN_VALUE) {
                        pointEnd = Math.min(pointEnd, nextTime);
                    }
                    pointEnd = Math.max(pointEnd, pointTime + 1);
                } else if (nextTime != Long.MIN_VALUE && nextTime - pointTime <= gap) {
                    pointEnd = nextTime;
                } else {
                    pointEnd = pointTime + nominal;
                    if (nextTime != Long.MIN_VALUE) {
                        pointEnd = Math.min(pointEnd, nextTime);
                    }
                }

                long relStart = pointTime - base;
                long relEnd = pointEnd - base;

                boolean contiguous = open && segState == state && relStart <= segEnd;
                if (contiguous) {
                    segEnd = Math.max(segEnd, relEnd);
                    segSamples += pointSamples;
                    stackVotes.merge(stackId, pointSamples, Integer::sum);
                } else {
                    if (open) {
                        t.add(segStart, segEnd, segState, pickStack(stackVotes), segSamples);
                    }
                    open = true;
                    segStart = relStart;
                    segEnd = relEnd;
                    segState = state;
                    segSamples = pointSamples;
                    stackVotes.clear();
                    stackVotes.put(stackId, pointSamples);
                }
                i = j;
            }
            if (open) {
                t.add(segStart, segEnd, segState, pickStack(stackVotes), segSamples);
            }

            for (int s = 0; s < stateCount; s++) {
                tl.totalStateTime[s] += t.stateTime[s];
            }
            tl.segmentCount += t.count;
            tl.threads.add(t);
        }

        tl.groupNames = config.groupNames();
        tl.durationNanos = Math.max(0, rec.endNanos - rec.startNanos);
        for (ThreadTimeline t : tl.threads) {
            if (t.count > 0) {
                tl.durationNanos = Math.max(tl.durationNanos, t.end[t.count - 1]);
            }
        }
        return tl;
    }

    private static int pickStack(Map<Integer, Integer> votes) {
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * Estimates the sampling period as the 25th percentile of the gaps between consecutive
     * samples. A low percentile is deliberate: it reflects the profiler's period during the
     * stretches where a thread was sampled continuously, and ignores the long idle gaps.
     */
    private static long estimateInterval(Recording rec) {
        int cap = 1 << 20;
        long[] deltas = new long[Math.min(cap, (int) Math.min(cap, Math.max(16, rec.totalSamples)))];
        int n = 0;
        outer:
        for (Recording.ThreadSamples ts : rec.threads()) {
            for (int i = 1; i < ts.size(); i++) {
                long d = ts.time(i) - ts.time(i - 1);
                if (d <= 0) {
                    continue;
                }
                if (n == deltas.length) {
                    break outer;
                }
                deltas[n++] = d;
            }
        }
        if (n == 0) {
            return 0;
        }
        long[] used = Arrays.copyOf(deltas, n);
        Arrays.sort(used);
        return used[n / 4];
    }
}
