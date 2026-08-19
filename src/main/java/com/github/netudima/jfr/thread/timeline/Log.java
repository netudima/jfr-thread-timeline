package com.github.netudima.jfr.thread.timeline;

/** Minimal stderr logger; {@code --quiet} silences info, {@code --verbose} adds detail. */
public final class Log {

    public enum Level { QUIET, NORMAL, VERBOSE }

    private final Level level;

    public Log(Level level) {
        this.level = level;
    }

    public void info(String msg) {
        if (level != Level.QUIET) {
            System.err.println("[jfr-thread-timeline] " + msg);
        }
    }

    public void debug(String msg) {
        if (level == Level.VERBOSE) {
            System.err.println("[jfr-thread-timeline] " + msg);
        }
    }

    public void warn(String msg) {
        System.err.println("[jfr-thread-timeline] WARNING: " + msg);
    }

    public boolean verbose() {
        return level == Level.VERBOSE;
    }
}
