package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameMatcherTest {

    private static boolean m(String pattern, String frame) {
        return FrameMatcher.parse(pattern, false).matches(frame);
    }

    @Test
    void plainTextIsASubstringMatch() {
        assertTrue(m("java.net.Socket", "java.net.SocketInputStream.read"));
        assertTrue(m("SocketInputStream.read", "java.net.SocketInputStream.read"));
        assertFalse(m("java.net.Server", "java.net.SocketInputStream.read"));
    }

    @Test
    void leadingEqualsRequiresAnExactMatch() {
        assertTrue(m("=java.lang.Thread.sleep", "java.lang.Thread.sleep"));
        assertFalse(m("=java.lang.Thread.sleep", "java.lang.Thread.sleepNanos"));
        assertFalse(m("=Thread.sleep", "java.lang.Thread.sleep"));
    }

    @Test
    void wildcardsTurnThePatternIntoAWholeStringGlob() {
        assertTrue(m("sun.nio.ch.*.read0", "sun.nio.ch.SocketDispatcher.read0"));
        assertFalse(m("sun.nio.ch.*.read0", "sun.nio.ch.SocketDispatcher.read0Extra"));
        assertTrue(m("libjvm*.ObjectMonitor::enter", "libjvm.so.ObjectMonitor::enter"));
        assertTrue(m("libc?.write", "libcX.write"));
    }

    @Test
    void regexPatternsAreUnanchored() {
        assertTrue(m("re:Shenandoah", "libjvm.so.ShenandoahHeap::marked_object_iterate"));
        assertTrue(m("re:^libjvm.*::enter$", "libjvm.so.ObjectMonitor::enter"));
        assertFalse(m("re:^libjvm.*::enter$", "libjvm.so.ObjectMonitor::exit"));
    }

    @Test
    void globMetacharactersInTheLiteralPartAreEscaped() {
        // '.' must not behave as a regex wildcard once the pattern becomes a glob
        assertFalse(m("java.net.Socket*", "javaXnet.SocketInputStream.read"));
        assertTrue(m("java.net.Socket*", "java.net.SocketInputStream.read"));
    }

    @Test
    void ignoreCaseAppliesToEverySyntax() {
        assertTrue(FrameMatcher.parse("socketread0", true).matches("java.net.SocketInputStream.socketRead0"));
        assertTrue(FrameMatcher.parse("=JAVA.LANG.THREAD.SLEEP", true).matches("java.lang.Thread.sleep"));
        assertTrue(FrameMatcher.parse("JAVA.NET.*", true).matches("java.net.Socket.read"));
        assertFalse(FrameMatcher.parse("socketread0", false).matches("java.net.SocketInputStream.socketRead0"));
    }

    /**
     * The default config leans on end-anchored regexes for libc symbols. "pthread" contains
     * "read", so a sloppy pattern would paint every parked thread as a file read.
     */
    @Test
    void nativeIoRegexesDoNotSwallowPthreadFrames() {
        Config c = Config.loadDefault();
        Config.StateRule fileRead = ruleNamed(c, "File read");
        Config.StateRule socketRead = ruleNamed(c, "Socket read");
        Config.StateRule socketWrite = ruleNamed(c, "Socket write");
        Config.StateRule fileWrite = ruleNamed(c, "File write");

        for (String parked : new String[]{
                "libpthread-2.28.so.__pthread_cond_wait",
                "libpthread-2.28.so.pthread_cond_timedwait@@GLIBC_2.3.2",
                "libpthread-2.28.so.do_futex_wait.constprop.1",
                "libpthread-2.28.so.pthread_cond_signal@@GLIBC_2.3.2"}) {
            assertFalse(fileRead.matchesFrame(parked), parked + " must not be a file read");
            assertFalse(fileWrite.matchesFrame(parked), parked + " must not be a file write");
            assertFalse(socketRead.matchesFrame(parked), parked + " must not be a socket read");
            assertFalse(socketWrite.matchesFrame(parked), parked + " must not be a socket write");
        }

        assertTrue(socketRead.matchesFrame("libpthread-2.28.so.__libc_recv"));
        assertTrue(socketRead.matchesFrame("libpthread-2.28.so.recvmsg"));
        assertTrue(socketWrite.matchesFrame("libpthread-2.28.so.send"));
        assertTrue(socketWrite.matchesFrame("libc-2.28.so.sendto"));
        assertTrue(fileRead.matchesFrame("libc-2.28.so.__pread64"));
        assertTrue(fileRead.matchesFrame("libpthread-2.28.so.__read"));
        assertTrue(fileWrite.matchesFrame("libc-2.28.so.write"));
        assertTrue(fileWrite.matchesFrame("libc-2.28.so.fdatasync"));
    }

    private static Config.StateRule ruleNamed(Config c, String name) {
        return c.states.stream().filter(s -> s.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("default config has no state '" + name + "'"));
    }
}
