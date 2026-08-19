package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import static com.github.netudima.jfr.thread.timeline.TestRecordings.config;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.empty;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stack;
import static com.github.netudima.jfr.thread.timeline.TestRecordings.stateIndex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nested match sequences: telling apart two states that share their innermost frame by what
 * called into it. Modelled on the real case of a Cassandra write path where both a memtable
 * shard lock and a replica-response wait bottom out in {@code LockSupport.park}.
 */
class SequenceTest {

    private static final String PARK = "java.util.concurrent.locks.LockSupport.park";
    private static final String UNSAFE_PARK = "jdk.internal.misc.Unsafe.park";
    private static final String SHARD_PUT = "org.apache.cassandra.db.memtable.TrieMemtable$MemtableShard.put";
    private static final String RESPONSE_GET = "org.apache.cassandra.service.AbstractWriteResponseHandler.get";

    /** Specific sequences first, the catch-all park rule last. */
    private static final String CONF =
            "states:\n" +
            "  - name: Memtable shard lock\n" +
            "    color: \"#8c564b\"\n" +
            "    sequences:\n" +
            "      - [\"LockSupport.park\", \"MemtableShard.put\"]\n" +
            "  - name: Replica response wait\n" +
            "    color: \"#17becf\"\n" +
            "    sequences:\n" +
            "      - [\"LockSupport.park\", \"AbstractWriteResponseHandler.get\"]\n" +
            "  - name: Lock wait (park)\n" +
            "    color: \"#e15759\"\n" +
            "    frames: [\"LockSupport.park\"]\n";

    private static int memtableStack(Recording rec) {
        return stack(rec, UNSAFE_PARK, PARK,
                "org.apache.cassandra.utils.concurrent.WaitQueue$Standard$AbstractSignal.await",
                SHARD_PUT,
                "org.apache.cassandra.db.memtable.TrieMemtable.put",
                "org.apache.cassandra.db.Keyspace.applyInternal");
    }

    private static int responseStack(Recording rec) {
        return stack(rec, UNSAFE_PARK, PARK,
                "org.apache.cassandra.utils.concurrent.Awaitable$AbstractAwaitable.await",
                RESPONSE_GET,
                "org.apache.cassandra.service.StorageProxy.mutate");
    }

    private static int plainParkStack(Recording rec) {
        return stack(rec, UNSAFE_PARK, PARK,
                "java.util.concurrent.LinkedBlockingQueue.take",
                "java.util.concurrent.ThreadPoolExecutor.getTask");
    }

    @Test
    void sequencesSeparateStatesThatShareTheirInnermostFrame() {
        Recording rec = empty();
        int memtable = memtableStack(rec);
        int response = responseStack(rec);
        int plain = plainParkStack(rec);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Classifier cl = new Classifier(config(CONF), rec);

        assertEquals(stateIndex(cl, "Memtable shard lock"), cl.stateOf(memtable, state));
        assertEquals(stateIndex(cl, "Replica response wait"), cl.stateOf(response, state));
        assertEquals(stateIndex(cl, "Lock wait (park)"), cl.stateOf(plain, state));
    }

    @Test
    void everyFrameOfTheMatchedSequenceIsReported() {
        Recording rec = empty();
        int memtable = memtableStack(rec);
        Classifier cl = new Classifier(config(CONF), rec);
        // LockSupport.park is at index 1, MemtableShard.put at index 3
        assertArrayEquals(new int[]{1, 3}, cl.matchPositions(memtable));
        assertEquals(1, cl.matchPos(memtable), "the anchor is the innermost step");
    }

    /** Three rules anchor on the same park frame, so the config order decides the tie. */
    @Test
    void ordersMattersOnlyForRulesAnchoredAtTheSameDepth() {
        Recording rec = empty();
        int memtable = memtableStack(rec);
        int state = rec.internThreadState("STATE_RUNNABLE");

        String catchAllFirst =
                "states:\n" +
                "  - {name: Lock wait (park), frames: [\"LockSupport.park\"]}\n" +
                "  - name: Memtable shard lock\n" +
                "    sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]\n";
        Classifier greedy = new Classifier(config(catchAllFirst), rec);
        assertEquals(stateIndex(greedy, "Lock wait (park)"), greedy.stateOf(memtable, state),
                "a catch-all listed first shadows the specific sequence");

        Classifier ordered = new Classifier(config(CONF), rec);
        assertEquals(stateIndex(ordered, "Memtable shard lock"), ordered.stateOf(memtable, state));
    }

    @Test
    void aDeeperAnchorLosesToAShallowerOneUnderInnermost() {
        Recording rec = empty();
        int s = stack(rec, "sun.nio.ch.SocketDispatcher.read0", PARK, SHARD_PUT);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Config c = config(
                "states:\n" +
                "  - name: Memtable shard lock\n" +
                "    sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]\n" +
                "  - {name: Socket read, frames: [\"SocketDispatcher.read0\"]}\n");
        Classifier cl = new Classifier(c, rec);
        // the socket read is at index 0, the sequence anchors at index 1 - the leaf wins
        assertEquals(stateIndex(cl, "Socket read"), cl.stateOf(s, state));
    }

    @Test
    void configOrderStrategyIgnoresDepthEntirely() {
        Recording rec = empty();
        int s = stack(rec, "sun.nio.ch.SocketDispatcher.read0", PARK, SHARD_PUT);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Config c = config(
                "matchStrategy: config-order\n" +
                "states:\n" +
                "  - name: Memtable shard lock\n" +
                "    sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]\n" +
                "  - {name: Socket read, frames: [\"SocketDispatcher.read0\"]}\n");
        Classifier cl = new Classifier(c, rec);
        assertEquals(stateIndex(cl, "Memtable shard lock"), cl.stateOf(s, state));
    }

    @Test
    void orderWithinASequenceIsSignificant() {
        Recording rec = empty();
        int s = memtableStack(rec);
        int state = rec.internThreadState("STATE_RUNNABLE");
        // reversed: MemtableShard.put would have to be *above* park, which it is not
        Config c = config(
                "states:\n" +
                "  - name: Reversed\n" +
                "    sequences: [[\"MemtableShard.put\", \"LockSupport.park\"]]\n");
        Classifier cl = new Classifier(c, rec);
        assertFalse(cl.matched(s));
        assertEquals(stateIndex(cl, "Runnable"), cl.stateOf(s, state), "falls through to the thread state");
    }

    @Test
    void gapsBetweenStepsAreAllowed() {
        Recording rec = empty();
        int s = stack(rec, "a.A.one", "b.B.two", "c.C.three", "d.D.four", "e.E.five");
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - {name: Spread, sequences: [[\"A.one\", \"C.three\", \"E.five\"]]}\n"), rec);
        assertArrayEquals(new int[]{0, 2, 4}, cl.matchPositions(s));
    }

    @Test
    void aStepMayListAlternatives() {
        Recording rec = empty();
        int viaUnsafe = stack(rec, UNSAFE_PARK, SHARD_PUT);
        int viaSupport = stack(rec, PARK, SHARD_PUT);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - name: Memtable shard lock\n" +
                "    sequences:\n" +
                "      - [[\"Unsafe.park\", \"LockSupport.park\"], \"MemtableShard.put\"]\n"), rec);
        assertEquals(stateIndex(cl, "Memtable shard lock"), cl.stateOf(viaUnsafe, state));
        assertEquals(stateIndex(cl, "Memtable shard lock"), cl.stateOf(viaSupport, state));
    }

    @Test
    void aRuleMayMixPlainFramesAndSequences() {
        Recording rec = empty();
        int viaSequence = memtableStack(rec);
        int viaFrame = stack(rec, "org.apache.cassandra.db.memtable.TrieMemtable.flush");
        int state = rec.internThreadState("STATE_RUNNABLE");
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - name: Memtable\n" +
                "    frames: [\"TrieMemtable.flush\"]\n" +
                "    sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]\n"), rec);
        assertEquals(stateIndex(cl, "Memtable"), cl.stateOf(viaSequence, state));
        assertEquals(stateIndex(cl, "Memtable"), cl.stateOf(viaFrame, state));
    }

    @Test
    void theLowestAnchoredOccurrenceIsChosen() {
        Recording rec = empty();
        // park appears twice; the sequence must anchor on the innermost one that still completes
        int s = stack(rec, PARK, "x.X.mid", PARK, SHARD_PUT);
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - {name: Memtable, sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]}\n"), rec);
        assertArrayEquals(new int[]{0, 3}, cl.matchPositions(s));
    }

    @Test
    void sequencesLongerThanTheStackNeverMatch() {
        Recording rec = empty();
        int s = stack(rec, PARK);
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - {name: Memtable, sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]}\n"), rec);
        assertFalse(cl.matched(s));
    }

    @Test
    void threeStepSequencesWork() {
        Recording rec = empty();
        int s = memtableStack(rec);
        Classifier cl = new Classifier(config(
                "states:\n" +
                "  - name: Memtable shard lock via keyspace apply\n" +
                "    sequences:\n" +
                "      - [\"Unsafe.park\", \"MemtableShard.put\", \"Keyspace.applyInternal\"]\n"), rec);
        assertArrayEquals(new int[]{0, 3, 5}, cl.matchPositions(s));
    }

    @Test
    void malformedSequencesAreRejectedWithAMessage() {
        assertThrows(Config.ConfigException.class, () ->
                config("states: [{name: A, sequences: 5}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("states: [{name: A, sequences: [[]]}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("states: [{name: A, sequences: [[[]]]}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("states: [{name: A, sequences: [[{bad: 1}]]}]\n"));
    }

    @Test
    void aBareStringInSequencesIsAOneFrameSequence() {
        Recording rec = empty();
        int s = stack(rec, PARK, SHARD_PUT);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Classifier cl = new Classifier(config(
                "states: [{name: Parked, sequences: [\"LockSupport.park\"]}]\n"), rec);
        assertEquals(stateIndex(cl, "Parked"), cl.stateOf(s, state));
    }

    /**
     * The classic authoring trap: a real park stack has several park frames on top of each
     * other, so a catch-all naming the innermost one beats a sequence anchored on an outer one.
     * The loser must be recorded so the CLI can warn about it instead of silently reporting 0%.
     */
    @Test
    void aCatchAllAnchoredNearerTheLeafShadowsASequence() {
        Recording rec = empty();
        int s = stack(rec, UNSAFE_PARK, PARK, SHARD_PUT);
        Config c = config(
                "states:\n"
                + "  - {name: Memtable shard lock, sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]}\n"
                + "  - {name: Lock wait (park), frames: [\"Unsafe.park\", \"LockSupport.park\"]}\n");
        Classifier cl = new Classifier(c, rec);

        assertEquals(1, cl.winningRule(s), "the catch-all anchored at frame 0 wins");
        assertEquals(0, cl.runnerUpRule(s), "the shadowed sequence is reported as the runner-up");
    }

    /** Giving both rules the same innermost frames makes config order decide, as intended. */
    @Test
    void sharingTheAnchorFramesLetsConfigOrderDecide() {
        Recording rec = empty();
        int s = stack(rec, UNSAFE_PARK, PARK, SHARD_PUT);
        int state = rec.internThreadState("STATE_RUNNABLE");
        Config c = config(
                "states:\n"
                + "  - name: Memtable shard lock\n"
                + "    sequences: [[[\"Unsafe.park\", \"LockSupport.park\"], \"MemtableShard.put\"]]\n"
                + "  - {name: Lock wait (park), frames: [\"Unsafe.park\", \"LockSupport.park\"]}\n");
        Classifier cl = new Classifier(c, rec);

        assertEquals(0, cl.winningRule(s));
        assertEquals(stateIndex(cl, "Memtable shard lock"), cl.stateOf(s, state));
        assertEquals(1, cl.runnerUpRule(s));
    }

    @Test
    void theRunnerUpIsTheBestOfTheLosers() {
        Recording rec = empty();
        int s = stack(rec, "a.A.leaf", PARK, SHARD_PUT, "z.Z.root");
        Config c = config(
                "states:\n"
                + "  - {name: Deepest, frames: [\"Z.root\"]}\n"
                + "  - {name: Middle, sequences: [[\"LockSupport.park\", \"MemtableShard.put\"]]}\n"
                + "  - {name: Leaf, frames: [\"A.leaf\"]}\n");
        Classifier cl = new Classifier(c, rec);

        assertEquals(2, cl.winningRule(s), "the leaf rule anchors at 0");
        assertEquals(1, cl.runnerUpRule(s), "the sequence at 1 beats the root rule at 3");
    }

    @Test
    void hasSequencesReportsMultiFrameRules() {
        Config c = config(CONF);
        assertTrue(c.states.get(0).hasSequences());
        assertFalse(c.states.get(2).hasSequences());
    }
}
