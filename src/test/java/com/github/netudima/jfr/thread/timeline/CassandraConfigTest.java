package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the shipped Cassandra configuration.
 *
 * <p>Several of its groups cannot be exercised by the reference recordings — a node that is not
 * repairing has no repair threads, and Accord may not be enabled at all — so the patterns are
 * asserted against representative thread names instead. Without this, a typo in one of those
 * regexes would only surface on someone else's cluster.
 */
class CassandraConfigTest {

    private static Config config;
    private static List<String> groups;

    @BeforeAll
    static void load() throws IOException {
        Path path = Paths.get("config/cassandra.yaml");
        assertTrue(Files.isReadable(path), "expected to run from the project root, looked for " + path);
        config = Config.load(path);
        groups = config.groupNames();
    }

    private static void assertGrouped(String group, String... threadNames) {
        int expected = groups.indexOf(group);
        assertTrue(expected >= 0, "config has no group '" + group + "', only " + groups);
        for (String name : threadNames) {
            assertEquals(expected, config.groupOf(name),
                    "'" + name + "' landed in '" + groups.get(config.groupOf(name)) + "'");
        }
    }

    /**
     * The group list is also the row order in the viewer, so the groups worth looking at first
     * lead. Safe only because no two groups can claim the same thread — see
     * {@link #theSharedPoolPatternIsNotTooGreedy()} and the membership tests below.
     */
    @Test
    void theMostInterestingGroupsComeFirst() {
        assertEquals(List.of("Netty event loops", "SEP shared pool", "GC", "Memtable flush", "Compaction"),
                groups.subList(0, 5));
        assertEquals("Other", groups.get(groups.size() - 1), "the catch-all bucket stays last");
    }

    @Test
    void compactionAndFlushAreSeparateGroups() {
        assertGrouped("Compaction", "CompactionExecutor:1", "CompactionExecutor:12", "CompactionLogger:1");
        assertGrouped("Memtable flush",
                "MemtableFlushWriter:3", "MemtablePostFlush:1", "MemtableReclaimMemory:2",
                "PerDiskMemtableFlushWriter_0:1", "SlabPoolCleaner");
        assertNotEquals(groups.indexOf("Compaction"), groups.indexOf("Memtable flush"));
    }

    @Test
    void hintsHaveTheirOwnGroup() {
        assertGrouped("Hints", "HintsWriteExecutor:1", "HintsDispatcher:1", "HintedHandoff:1",
                "BatchlogTasks:1");
    }

    @Test
    void repairHasItsOwnGroup() {
        assertGrouped("Repair & validation",
                "Repair#1:1", "RepairJobTask:1", "Repair-Task-3",
                "AntiEntropyStage:1", "ValidationExecutor:2",
                "StreamReceiveTask:1", "NettyStreaming-Outbound-10.0.0.1");
    }

    @Test
    void accordHasItsOwnGroup() {
        assertGrouped("Accord", "AccordScheduler", "Accord-CommandStore-1", "accord-executor-2", "CommandStore[0]");
    }

    /** The SEP naming moved around between 3.x, 4.x and 5.x; all three shapes must land together. */
    @Test
    void everySharedPoolShapeLandsInOneGroup() {
        assertGrouped("SEP shared pool",
                "SharedPool-Worker-3",                                   // 3.x
                "MutationStage-23", "ReadStage-7", "RequestResponseStage-2",
                "ViewMutationStage-1", "CounterMutationStage-1",
                "Native-Transport-Requests-5",                           // 4.x
                "RequestPool-Worker-9", "ReadPool-Worker-1",
                "MutatePool-Worker-2", "ResponsePool-Worker-1");         // 5.x
    }

    /** The SEP pattern keys on "…Pool-Worker-N" and must not swallow unrelated pools. */
    @Test
    void theSharedPoolPatternIsNotTooGreedy() {
        int sep = groups.indexOf("SEP shared pool");
        for (String other : new String[]{"ForkJoinPool.commonPool-worker-3", "LocalPool-Cleaner-chunk-cache",
                                         "prometheus-netty-pool-1"}) {
            assertNotEquals(sep, config.groupOf(other), other + " must not be treated as a SEP worker");
        }
    }

    @Test
    void commitlogAndInternodeMessagingStillGroup() {
        assertGrouped("Commitlog", "COMMIT-LOG-ALLOCATOR", "COMMIT-LOG-WRITER", "PERIODIC-COMMIT-LOG-SYNCER");
        assertGrouped("Netty event loops",
                "epollEventLoopGroup-5-12", "nioEventLoopGroup-1-1",
                "Messaging-EventLoop-3-8", "Messaging-AcceptLoop");
        assertGrouped("Gossip & cluster", "GossipStage:1", "GossipTasks:1", "MigrationStage:1");
    }

    /**
     * Compaction, flush and GC are thread groups, not states. A CompactionExecutor thread is
     * always compacting; the state should say whether it is reading, writing or blocked.
     */
    @Test
    void workKindsThatAreGroupsAreNotAlsoStates() {
        for (String name : new String[]{"Compaction", "Memtable flush", "GC"}) {
            assertTrue(groups.contains(name), "'" + name + "' should be a thread group");
            assertTrue(config.states.stream().noneMatch(s -> s.name.equals(name)),
                    "'" + name + "' should not also be a state");
        }
    }

    private static Config.StateRule state(String name) {
        return config.states.stream().filter(s -> s.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no state '" + name + "'"));
    }

    @Test
    void lz4MatchesBothTheJniLibraryAndTheJavaSide() {
        Config.StateRule lz4 = state("LZ4");
        // the JNI library is unpacked to a temp file, so the name carries a random suffix
        assertTrue(lz4.matchesFrame("liblz4-java-1267762641758156326.so.LZ4_decompress_safe"));
        assertTrue(lz4.matchesFrame("liblz4-java-99.so.Java_net_jpountz_lz4_LZ4JNI_LZ4_1compress_1limitedOutput"));
        assertTrue(lz4.matchesFrame("net.jpountz.lz4.LZ4JNISafeDecompressor.decompress"));
        assertTrue(lz4.matchesFrame("io.netty.handler.codec.compression.Lz4FrameDecoder.decode"));
        assertFalse(lz4.matchesFrame("org.apache.cassandra.io.compress.SnappyCompressor.uncompress"));
    }

    @Test
    void caffeineMatchesTheLibraryAndCassandrasWrapper() {
        Config.StateRule cache = state("Caffeine cache");
        assertTrue(cache.matchesFrame("org.apache.cassandra.cache.CaffeineCache.get"));
        assertTrue(cache.matchesFrame("org.apache.cassandra.cache.CaffeineCache.put"));
        assertTrue(cache.matchesFrame("com.github.benmanes.caffeine.cache.BoundedLocalCache.getIfPresent"));
        assertTrue(cache.matchesFrame("com.github.benmanes.caffeine.cache.BoundedLocalCache$PerformCleanupTask.run"));
        assertTrue(cache.matchesFrame("com.github.benmanes.caffeine.cache.FrequencySketch.increment"));
        assertFalse(cache.matchesFrame("org.apache.cassandra.cache.ChunkCache.get"),
                "only the Caffeine-backed caches belong here");
    }

    /** A loose "wakeup" pattern would also claim libpthread's condvar internals. */
    @Test
    void epollWakeupDoesNotClaimUnrelatedWakeups() {
        Config.StateRule wake = state("Epoll wakeup");
        assertTrue(wake.matchesFrame("io.netty.channel.epoll.EpollEventLoop.wakeup"));
        assertTrue(wake.matchesFrame("io.netty.channel.epoll.Native.eventFdWrite"));
        assertTrue(wake.matchesFrame("libc-2.28.so.eventfd_write"));
        assertFalse(wake.matchesFrame("libpthread-2.28.so.__condvar_confirm_wakeup"));
        assertFalse(wake.matchesFrame("io.netty.channel.epoll.EpollEventLoop.epollWait"));
    }

    /** eventfd_write ends in "write"; it must not be swallowed by the file-write regex. */
    @Test
    void theFileWriteRuleDoesNotClaimEventfdWrite() {
        assertFalse(state("File write").matchesFrame("libc-2.28.so.eventfd_write"));
        assertTrue(state("File write").matchesFrame("libc-2.28.so.pwrite64"));
    }

    /** Every state rule must still resolve, and the park rules must stay ahead of the catch-all. */
    @Test
    void theStateRulesAreIntact() {
        assertTrue(config.states.size() >= 12, "expected the full state list, got " + config.states.size());
        int catchAll = -1;
        int lastSpecificPark = -1;
        for (int i = 0; i < config.states.size(); i++) {
            Config.StateRule r = config.states.get(i);
            if (r.name.equals("Lock wait (park)")) {
                catchAll = i;
            } else if (r.hasSequences()) {
                lastSpecificPark = Math.max(lastSpecificPark, i);
            }
        }
        assertTrue(catchAll >= 0, "the park catch-all disappeared");
        assertTrue(lastSpecificPark < catchAll,
                "specific park sequences must be listed before the 'Lock wait (park)' catch-all");
    }
}
