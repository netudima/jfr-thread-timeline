package com.github.netudima.jfr.thread.timeline;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private static Config load(String yaml) {
        return Config.load(new StringReader(yaml), "test");
    }

    @Test
    void minimalConfigLoads() {
        Config c = load(
                "states:\n" +
                "  - name: Lock\n" +
                "    color: \"#ff0000\"\n" +
                "    frames: [LockSupport.park]\n");
        assertEquals(1, c.states.size());
        assertEquals("Lock", c.states.get(0).name);
        assertEquals("#ff0000", c.states.get(0).color);
        assertEquals(Config.MatchStrategy.INNERMOST, c.matchStrategy);
        assertTrue(c.useThreadState);
    }

    @Test
    void colorsAreAutoAssignedWhenOmitted() {
        Config c = load(
                "states:\n" +
                "  - {name: A, frames: [a]}\n" +
                "  - {name: B, frames: [b]}\n");
        assertFalse(c.states.get(0).color.isEmpty());
        assertFalse(c.states.get(0).color.equals(c.states.get(1).color));
    }

    @Test
    void matchStrategyIsParsed() {
        assertEquals(Config.MatchStrategy.CONFIG_ORDER,
                load("matchStrategy: config-order\nstates: [{name: A, frames: [a]}]\n").matchStrategy);
        assertEquals(Config.MatchStrategy.INNERMOST,
                load("matchStrategy: innermost\nstates: [{name: A, frames: [a]}]\n").matchStrategy);
        assertThrows(Config.ConfigException.class,
                () -> load("matchStrategy: nonsense\nstates: [{name: A, frames: [a]}]\n"));
    }

    @Test
    void threadIncludeAndExcludeAreApplied() {
        Config c = load(
                "threads:\n" +
                "  include: [\"worker-*\", \"=main\"]\n" +
                "  exclude: [\"worker-9*\"]\n" +
                "states: [{name: A, frames: [a]}]\n");
        assertTrue(c.acceptsThread("worker-1"));
        assertTrue(c.acceptsThread("main"));
        assertFalse(c.acceptsThread("worker-9"));
        assertFalse(c.acceptsThread("GC Thread#0"));
    }

    @Test
    void emptyIncludeMeansEveryThread() {
        Config c = load("states: [{name: A, frames: [a]}]\n");
        assertTrue(c.acceptsThread("anything"));
    }

    @Test
    void badConfigsAreRejectedWithAMessage() {
        assertThrows(Config.ConfigException.class, () -> load("title: x\n"));
        assertThrows(Config.ConfigException.class, () -> load("states: []\n"));
        assertThrows(Config.ConfigException.class, () -> load("states: [{color: red, frames: [a]}]\n"));
        assertThrows(Config.ConfigException.class, () -> load("states: [{name: A}]\n"));
        assertThrows(Config.ConfigException.class, () -> load("states: [{name: A, frames: []}]\n"));
        assertThrows(Config.ConfigException.class, () -> load("maxStackDepth: 0\nstates: [{name: A, frames: [a]}]\n"));
    }

    @Test
    void threadStateTokensGetFriendlyNames() {
        assertEquals("Running", Config.prettyThreadState("STATE_DEFAULT"));
        assertEquals("Sleeping", Config.prettyThreadState("STATE_SLEEPING"));
        assertEquals("Runnable", Config.prettyThreadState("STATE_RUNNABLE"));
        assertEquals("Blocked", Config.prettyThreadState("STATE_BLOCKED_ON_MONITOR_ENTER"));
        assertEquals("Native", Config.prettyThreadState("STATE_IN_NATIVE"));
        assertEquals("Unknown", Config.prettyThreadState(null));
        assertEquals("Something else", Config.prettyThreadState("STATE_SOMETHING_ELSE"));
    }

    @Test
    void builtInConfigIsValid() {
        Config c = Config.loadDefault();
        assertTrue(c.states.size() >= 10);
        for (Config.StateRule r : c.states) {
            assertFalse(r.name.isEmpty());
            assertTrue(r.color.startsWith("#"), r.name + " has colour " + r.color);
            assertFalse(r.sequences.isEmpty());
        }
    }
}
