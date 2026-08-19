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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadGroupTest {

    private static final String GROUPS =
            "ungroupedName: Application\n" +
            "threadGroups:\n" +
            "  - {name: GC, threads: [\"re:^(GC Thread|G1 )\", \"VM Thread\"]}\n" +
            "  - {name: Netty, threads: [\"epollEventLoopGroup*\"]}\n" +
            "  - {name: Compaction, threads: [\"CompactionExecutor\"]}\n" +
            "states: [{name: Lock, frames: [LockSupport.park]}]\n";

    @Test
    void threadsAreBucketedByNameInFileOrder() {
        Config c = config(GROUPS);
        assertEquals(0, c.groupOf("GC Thread#3"));
        assertEquals(0, c.groupOf("G1 Conc#0"));
        assertEquals(0, c.groupOf("VM Thread"));
        assertEquals(1, c.groupOf("epollEventLoopGroup-5-12"));
        assertEquals(2, c.groupOf("CompactionExecutor:1"));
        assertEquals(3, c.groupOf("MutationStage-23"), "unclaimed threads land in the catch-all");
    }

    @Test
    void groupNamesEndWithTheCatchAll() {
        List<String> names = config(GROUPS).groupNames();
        assertEquals(List.of("GC", "Netty", "Compaction", "Application"), names);
    }

    @Test
    void theFirstMatchingGroupWins() {
        Config c = config(
                "threadGroups:\n"
                + "  - {name: First, threads: [\"re:^worker\"]}\n"
                + "  - {name: Second, threads: [\"re:worker-1\"]}\n"
                + "states: [{name: Lock, frames: [park]}]\n");
        assertEquals(0, c.groupOf("worker-1"));
    }

    @Test
    void noGroupsConfiguredMeansNoGrouping() {
        Config c = config("states: [{name: Lock, frames: [park]}]\n");
        assertEquals(-1, c.groupOf("anything"));
        assertTrue(c.groupNames().isEmpty());
    }

    @Test
    void groupsAreAssignedToTheTimeline() {
        Recording rec = empty();
        int park = stack(rec, "java.util.concurrent.locks.LockSupport.park");
        for (String name : new String[]{"GC Thread#0", "epollEventLoopGroup-5-1", "MutationStage-7"}) {
            Recording.ThreadSamples ts = thread(rec, name);
            sample(rec, ts, 0, park, "STATE_RUNNABLE");
            sample(rec, ts, 10, park, "STATE_RUNNABLE");
        }
        Timeline tl = build(rec, config("sampleIntervalMs: 10\ngapThresholdMs: 25\n" + GROUPS));

        assertEquals(List.of("GC", "Netty", "Compaction", "Application"), tl.groupNames);
        for (Timeline.ThreadTimeline t : tl.threads) {
            int expected = t.name.startsWith("GC") ? 0 : t.name.startsWith("epoll") ? 1 : 3;
            assertEquals(expected, t.group, t.name);
        }
        assertEquals(20 * MS, tl.threads.get(0).coveredTime);
    }

    @Test
    void malformedGroupsAreRejected() {
        assertThrows(Config.ConfigException.class, () ->
                config("threadGroups: notalist\nstates: [{name: A, frames: [a]}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("threadGroups: [{threads: [a]}]\nstates: [{name: A, frames: [a]}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("threadGroups: [{name: G}]\nstates: [{name: A, frames: [a]}]\n"));
        assertThrows(Config.ConfigException.class, () ->
                config("threadGroups: [{name: G, threads: []}]\nstates: [{name: A, frames: [a]}]\n"));
    }

    @Test
    void builtInConfigGroupsTheStandardJvmThreads() {
        Config c = Config.loadDefault();
        List<String> names = c.groupNames();
        assertTrue(names.contains("GC"), names.toString());
        assertTrue(names.contains("JIT compiler"), names.toString());

        assertEquals(names.indexOf("GC"), c.groupOf("GC Thread#0"));
        assertEquals(names.indexOf("GC"), c.groupOf("G1 Conc#4"));
        assertEquals(names.indexOf("GC"), c.groupOf("Shenandoah GC T"));
        assertEquals(names.indexOf("JIT compiler"), c.groupOf("C2 CompilerThre"));
        assertEquals(names.indexOf("Event loop / NIO"), c.groupOf("epollEventLoopGroup-5-3"));
        assertEquals(names.indexOf("JVM internal"), c.groupOf("Reference Handl"));
        assertEquals(names.indexOf("JVM internal"), c.groupOf("[tid=1676954]"));
        assertEquals(names.indexOf("JMX / management"), c.groupOf("JMX server connection timeout 323"));
        // application threads fall through to the catch-all, which is last
        assertEquals(names.size() - 1, c.groupOf("MutationStage-23"));
        assertEquals(names.size() - 1, c.groupOf("CompactionExecutor:1"));
    }
}
