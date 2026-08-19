package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import static com.github.netudima.jfr.thread.timeline.TestRecordings.config;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.empty;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stack;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stateIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassifierTest {

    private static final String TWO_RULES =
            "states:\n" +
            "  - {name: Lock wait, color: \"#e15759\", frames: [LockSupport.park]}\n" +
            "  - {name: Socket read, color: \"#4e79a7\", frames: [SocketDispatcher.read0]}\n";

    /** Leaf-first: socket read nested inside a lock section. */
    private static int nestedStack(Recording rec) {
        return stack(rec,
                "sun.nio.ch.SocketDispatcher.read0",
                "sun.nio.ch.SocketChannelImpl.read",
                "java.util.concurrent.locks.LockSupport.park",
                "com.example.Handler.run");
    }

    @Test
    void innermostStrategyPicksTheLeafMostMatch() {
        Recording rec = empty();
        int s = nestedStack(rec);
        Config c = config("matchStrategy: innermost\n" + TWO_RULES);
        Classifier cl = new Classifier(c, rec);

        assertEquals(stateIndex(cl, "Socket read"), cl.stateOf(s, rec.internThreadState("STATE_RUNNABLE")));
        assertEquals(0, cl.matchPos(s), "the leaf frame decided");
    }

    @Test
    void configOrderStrategyPicksTheFirstRuleInTheFile() {
        Recording rec = empty();
        int s = nestedStack(rec);
        Config c = config("matchStrategy: config-order\n" + TWO_RULES);
        Classifier cl = new Classifier(c, rec);

        assertEquals(stateIndex(cl, "Lock wait"), cl.stateOf(s, rec.internThreadState("STATE_RUNNABLE")));
        assertEquals(2, cl.matchPos(s), "the LockSupport.park frame decided");
    }

    @Test
    void unmatchedStacksFallBackToTheJfrThreadState() {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop", "com.example.Main.main");
        int sleeping = rec.internThreadState("STATE_SLEEPING");
        int running = rec.internThreadState("STATE_DEFAULT");
        Classifier cl = new Classifier(config(TWO_RULES), rec);

        assertEquals(stateIndex(cl, "Sleeping"), cl.stateOf(s, sleeping));
        assertEquals(stateIndex(cl, "Running"), cl.stateOf(s, running));
        assertEquals(-1, cl.matchPos(s));
    }

    @Test
    void fallbackCanBeCollapsedIntoASingleOtherBucket() {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop");
        int sleeping = rec.internThreadState("STATE_SLEEPING");
        int runnable = rec.internThreadState("STATE_RUNNABLE");
        Config c = config("fallback: {useThreadState: false}\n" + TWO_RULES);
        Classifier cl = new Classifier(c, rec);

        assertEquals(stateIndex(cl, "Other"), cl.stateOf(s, sleeping));
        assertEquals(stateIndex(cl, "Other"), cl.stateOf(s, runnable));
    }

    @Test
    void samplesWithoutAStateFallBackToOther() {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop");
        int none = rec.internThreadState(null);
        Classifier cl = new Classifier(config(TWO_RULES), rec);
        assertEquals(stateIndex(cl, "Other"), cl.stateOf(s, none));
    }

    /** Nothing may blow up if a state id turns up that the classifier never saw. */
    @Test
    void unknownThreadStateIdsDegradeToOther() {
        Recording rec = empty();
        int s = stack(rec, "com.example.Busy.loop");
        Classifier cl = new Classifier(config(TWO_RULES), rec);
        assertEquals(stateIndex(cl, "Other"), cl.stateOf(s, 42));
        assertEquals(stateIndex(cl, "Other"), cl.stateOf(s, -1));
    }

    @Test
    void emptyStacksAreClassifiedByThreadStateOnly() {
        Recording rec = empty();
        int s = stack(rec);
        int sleeping = rec.internThreadState("STATE_SLEEPING");
        Classifier cl = new Classifier(config(TWO_RULES), rec);
        assertEquals(stateIndex(cl, "Sleeping"), cl.stateOf(s, sleeping));
    }

    @Test
    void ruleStatesExistEvenWhenNothingMatchesThem() {
        Recording rec = empty();
        stack(rec, "com.example.Busy.loop");
        Classifier cl = new Classifier(config(TWO_RULES), rec);
        // both rules keep a legend slot so the colours stay stable across recordings
        assertEquals(0, stateIndex(cl, "Lock wait"));
        assertEquals(1, stateIndex(cl, "Socket read"));
    }

    @Test
    void frameAndStackMatchCountsAreReported() {
        Recording rec = empty();
        nestedStack(rec);
        stack(rec, "com.example.Busy.loop");
        Classifier cl = new Classifier(config(TWO_RULES), rec);
        assertEquals(2, cl.matchedFrameCount());
        assertEquals(1, cl.matchedStackCount());
    }
}
