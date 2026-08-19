package com.github.netudima.jfr.thread.timeline;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches a single frame pattern from the configuration file against a frame string
 * of the form {@code fully.qualified.Class.method} (native frames look like
 * {@code libjvm.so.ObjectMonitor::enter}).
 *
 * <p>Supported pattern syntax:
 * <ul>
 *   <li>{@code re:<java regex>} &mdash; regex, matched with {@code find()} so it does not
 *       need to be anchored</li>
 *   <li>{@code =<text>} &mdash; the frame must be exactly {@code text}</li>
 *   <li>a pattern containing {@code *} or {@code ?} &mdash; glob over the whole frame string</li>
 *   <li>anything else &mdash; plain substring match (the forgiving default)</li>
 * </ul>
 */
public final class FrameMatcher {

    private enum Kind { SUBSTRING, EXACT, GLOB, REGEX }

    private final String spec;
    private final Kind kind;
    private final boolean ignoreCase;
    private final String needle;   // for SUBSTRING / EXACT, pre-lowercased when ignoreCase
    private final Pattern regex;   // for GLOB / REGEX

    private FrameMatcher(String spec, Kind kind, boolean ignoreCase, String needle, Pattern regex) {
        this.spec = spec;
        this.kind = kind;
        this.ignoreCase = ignoreCase;
        this.needle = needle;
        this.regex = regex;
    }

    public static FrameMatcher parse(String spec, boolean ignoreCase) {
        String s = spec.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty frame pattern");
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        if (s.startsWith("re:")) {
            String body = s.substring(3).trim();
            return new FrameMatcher(spec, Kind.REGEX, ignoreCase, null, Pattern.compile(body, flags));
        }
        if (s.startsWith("=")) {
            String body = s.substring(1).trim();
            return new FrameMatcher(spec, Kind.EXACT, ignoreCase, norm(body, ignoreCase), null);
        }
        if (s.startsWith("glob:")) {
            String body = s.substring(5).trim();
            return new FrameMatcher(spec, Kind.GLOB, ignoreCase, null, Pattern.compile(globToRegex(body), flags));
        }
        if (s.indexOf('*') >= 0 || s.indexOf('?') >= 0) {
            return new FrameMatcher(spec, Kind.GLOB, ignoreCase, null, Pattern.compile(globToRegex(s), flags));
        }
        return new FrameMatcher(spec, Kind.SUBSTRING, ignoreCase, norm(s, ignoreCase), null);
    }

    public boolean matches(String frame) {
        switch (kind) {
            case SUBSTRING: return norm(frame, ignoreCase).contains(needle);
            case EXACT:     return norm(frame, ignoreCase).equals(needle);
            case GLOB:
            case REGEX:     return regex.matcher(frame).find();
            default:        return false;
        }
    }

    public String spec() {
        return spec;
    }

    @Override
    public String toString() {
        return spec;
    }

    private static String norm(String s, boolean ignoreCase) {
        return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** Translates a glob ({@code *}, {@code ?}) into an anchored regex. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': case '(': case ')': case '[': case ']': case '{': case '}':
                case '+': case '^': case '$': case '|': case '\\':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        return sb.append('$').toString();
    }
}
