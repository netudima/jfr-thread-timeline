package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import static com.github.netudima.jfr.thread.timeline.TestRecordings.MS;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.build;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.config;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.empty;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.sample;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.sampleWithDuration;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stack;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineTest {

    /** Fixed sampling parameters keep the expectations independent of the estimator. */
    private static final String CONF =
            "sampleIntervalMs: 10\n" +
            "gapThresholdMs: 25\n" +
            "states:\n" +
            "  - {name: Lock wait, color: \"#e15759\", frames: [LockSupport.park]}\n" +
            "  - {name: Socket read, color: \"#4e79a7\", frames: [SocketDispatcher.read0]}\n";

    @Test
    void consecutiveSamplesWithTheSameStateCollapseIntoOneSegment() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        for (int i = 0; i < 10; i++) {
            sample(rec, t, i * 10, park, "STATE_RUNNABLE");
        }

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(1, out.count);
        assertEquals(0, out.start[0]);
        // nine 10 ms steps plus one nominal interval for the trailing sample
        assertEquals(100 * MS, out.end[0]);
        assertEquals(10, out.samples[0]);
    }

    @Test
    void aStateChangeStartsANewSegment() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        int read = stack(rec, "sun.nio.ch.SocketDispatcher.read0");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 10, park, "STATE_RUNNABLE");
        sample(rec, t, 20, read, "STATE_RUNNABLE");
        sample(rec, t, 30, read, "STATE_RUNNABLE");

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(2, out.count);
        assertEquals(0, out.start[0]);
        assertEquals(20 * MS, out.end[0]);
        assertEquals(20 * MS, out.start[1]);
        assertEquals(40 * MS, out.end[1]);
        assertTrue(out.state[0] != out.state[1]);
    }

    @Test
    void gapsLongerThanTheThresholdLeaveAHole() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 10, park, "STATE_RUNNABLE");
        sample(rec, t, 500, park, "STATE_RUNNABLE");   // 490 ms later: not observed in between

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(2, out.count, "the hole must split the run even though the state is unchanged");
        assertEquals(0, out.start[0]);
        assertEquals(20 * MS, out.end[0], "closed one nominal interval after the last sample");
        assertEquals(500 * MS, out.start[1]);
        assertEquals(510 * MS, out.end[1]);
    }

    @Test
    void samplesSharingATimestampCollapseIntoOnePoint() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 10, park, "STATE_RUNNABLE");

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(1, out.count);
        assertEquals(0, out.start[0]);
        assertEquals(20 * MS, out.end[0]);
        assertEquals(3, out.samples[0], "every sample is still counted");
    }

    @Test
    void durationEventsDefineTheirOwnExtentButNeverOverlap() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        int read = stack(rec, "sun.nio.ch.SocketDispatcher.read0");
        Recording.ThreadSamples t = thread(rec, "main");
        sampleWithDuration(rec, t, 0, 200, park, "STATE_RUNNABLE");   // claims 0..200 ms
        sampleWithDuration(rec, t, 50, 10, read, "STATE_RUNNABLE");   // but the next event starts at 50

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(2, out.count);
        assertEquals(0, out.start[0]);
        assertEquals(50 * MS, out.end[0], "clipped so segments never overlap");
        assertEquals(50 * MS, out.start[1]);
        assertEquals(60 * MS, out.end[1]);
    }

    @Test
    void unsortedSamplesAreOrderedBeforeSegmentsAreBuilt() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        sample(rec, t, 20, park, "STATE_RUNNABLE");
        sample(rec, t, 0, park, "STATE_RUNNABLE");
        sample(rec, t, 10, park, "STATE_RUNNABLE");

        Timeline tl = build(rec, config(CONF));
        Timeline.ThreadTimeline out = tl.threads.get(0);
        assertEquals(1, out.count);
        assertEquals(0, out.start[0]);
        assertEquals(30 * MS, out.end[0]);
    }

    @Test
    void perThreadAndGlobalStateTotalsAddUp() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        int read = stack(rec, "sun.nio.ch.SocketDispatcher.read0");
        Recording.ThreadSamples a = thread(rec, "a");
        Recording.ThreadSamples b = thread(rec, "b");
        for (int i = 0; i < 5; i++) {
            sample(rec, a, i * 10, park, "STATE_RUNNABLE");
            sample(rec, b, i * 10, read, "STATE_RUNNABLE");
        }

        Config c = config(CONF);
        Timeline tl = build(rec, c);
        Classifier cl = new Classifier(c, rec);
        int lock = TestRecordings.stateIndex(cl, "Lock wait");
        int socket = TestRecordings.stateIndex(cl, "Socket read");

        assertEquals(2, tl.threads.size());
        assertEquals(50 * MS, tl.totalStateTime[lock]);
        assertEquals(50 * MS, tl.totalStateTime[socket]);
        for (Timeline.ThreadTimeline t : tl.threads) {
            assertEquals(50 * MS, t.coveredTime);
        }
    }

    @Test
    void theSamplingIntervalIsEstimatedFromTheData() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        Recording.ThreadSamples t = thread(rec, "main");
        for (int i = 0; i < 50; i++) {
            sample(rec, t, i * 7, park, "STATE_RUNNABLE");
        }
        // no sampleIntervalMs / gapThresholdMs in this config: both must be inferred
        Timeline tl = build(rec, config("states: [{name: Lock wait, frames: [LockSupport.park]}]\n"));
        assertEquals(7 * MS, tl.sampleIntervalNanos);
        assertTrue(tl.gapNanos > tl.sampleIntervalNanos);
        assertEquals(1, tl.threads.get(0).count);
    }
}
