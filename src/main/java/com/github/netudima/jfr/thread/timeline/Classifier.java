package com.github.netudima.jfr.thread.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a stack into a coloured state.
 *
 * <p>A rule matches when any of its {@linkplain Config.Sequence sequences} is found on the stack:
 * the sequence's frames must appear innermost first, in order, with gaps allowed. The position of
 * a match is the index of its innermost frame — that is what {@code matchStrategy: innermost}
 * compares, so two rules anchored on the same {@code park} frame are separated by the deeper
 * frames they require, and ties fall back to the order of the config file.
 *
 * <p>Every pattern in the configuration is tested against every <em>unique frame</em> once, and
 * the result is stored as a bitset per frame. Sequence matching then only reads bits, and the
 * whole thing is done once per <em>unique stack</em>, so the per-sample cost is an array lookup.
 */
public final class Classifier {

    /** One entry of the timeline's colour legend. */
    public static final class State {
        public final String name;
        public final String color;
        /** {@code rule}, {@code thread-state} or {@code unmatched} &mdash; used by the UI only. */
        public final String kind;
        public final String description;

        State(String name, String color, String kind, String description) {
            this.name = name;
            this.color = color;
            this.kind = kind;
            this.description = description;
        }
    }

    private static final int NO_RULE = -1;
    private static final int[] NO_POSITIONS = new int[0];

    /** A sequence compiled to one bitset per step. */
    private static final class CompiledSequence {
        final long[][] stepBits;

        CompiledSequence(long[][] stepBits) {
            this.stepBits = stepBits;
        }
    }

    private final Config config;
    private final List<State> states = new ArrayList<>();
    private final Map<String, Integer> stateIndexByName = new HashMap<>();

    /** Bitset words per frame; one bit per distinct pattern in the config. */
    private final int words;
    private final long[] frameBits;

    private final CompiledSequence[][] ruleSequences;
    private final int[] ruleToState;

    /** For each stack id: the deciding rule, or {@link #NO_RULE}. */
    private final int[] stackRule;
    /** For each stack id: the frame indices (leaf = 0) that the matched sequence landed on. */
    private final int[][] stackPositions;
    /**
     * For each stack id: the best rule that matched but lost, or {@link #NO_RULE}. Used to warn
     * about states that are silently shadowed by a broader rule.
     */
    private final int[] stackRunnerUp;

    private final int[] threadStateToState;
    private final int unmatchedIndex;

    private int matchedFrames;
    private int matchedStacks;

    public Classifier(Config config, Recording rec) {
        this.config = config;

        for (Config.StateRule r : config.states) {
            // registered up-front so every configured state keeps a stable legend slot
            addState(new State(r.name, r.color, "rule", r.description));
        }
        this.ruleToState = new int[config.states.size()];
        for (int i = 0; i < config.states.size(); i++) {
            ruleToState[i] = stateIndexByName.get(config.states.get(i).name);
        }

        // ---- pattern table: one bit per distinct pattern spec ----
        Map<String, Integer> patternIds = new HashMap<>();
        List<FrameMatcher> patterns = new ArrayList<>();
        for (Config.StateRule r : config.states) {
            for (FrameMatcher m : r.allPatterns()) {
                patternIds.computeIfAbsent(m.spec(), k -> {
                    patterns.add(m);
                    return patterns.size() - 1;
                });
            }
        }
        this.words = Math.max(1, (patterns.size() + 63) >>> 6);

        int nFrames = rec.frames.size();
        this.frameBits = new long[nFrames * words];
        for (int f = 0; f < nFrames; f++) {
            String name = rec.frames.name(f);
            int base = f * words;
            boolean any = false;
            for (int p = 0; p < patterns.size(); p++) {
                if (patterns.get(p).matches(name)) {
                    frameBits[base + (p >>> 6)] |= 1L << (p & 63);
                    any = true;
                }
            }
            if (any) {
                matchedFrames++;
            }
        }

        // ---- rules compiled to step bitsets ----
        this.ruleSequences = new CompiledSequence[config.states.size()][];
        for (int r = 0; r < config.states.size(); r++) {
            List<Config.Sequence> seqs = config.states.get(r).sequences;
            CompiledSequence[] compiled = new CompiledSequence[seqs.size()];
            for (int s = 0; s < seqs.size(); s++) {
                List<List<FrameMatcher>> steps = seqs.get(s).steps;
                long[][] stepBits = new long[steps.size()][words];
                for (int i = 0; i < steps.size(); i++) {
                    for (FrameMatcher m : steps.get(i)) {
                        int p = patternIds.get(m.spec());
                        stepBits[i][p >>> 6] |= 1L << (p & 63);
                    }
                }
                compiled[s] = new CompiledSequence(stepBits);
            }
            ruleSequences[r] = compiled;
        }

        // ---- classify every unique stack once ----
        int nStacks = rec.stacks.size();
        this.stackRule = new int[nStacks];
        this.stackPositions = new int[nStacks][];
        this.stackRunnerUp = new int[nStacks];
        boolean innermost = config.matchStrategy == Config.MatchStrategy.INNERMOST;
        long[] stackBits = new long[words];
        int[] scratch = new int[8];

        for (int s = 0; s < nStacks; s++) {
            int[] frames = rec.stacks.frames(s);
            Arrays.fill(stackBits, 0L);
            for (int frame : frames) {
                int base = frame * words;
                for (int w = 0; w < words; w++) {
                    stackBits[w] |= frameBits[base + w];
                }
            }

            int bestRule = NO_RULE;
            int[] bestPositions = NO_POSITIONS;
            int secondRule = NO_RULE;
            int secondAnchor = Integer.MAX_VALUE;

            for (int r = 0; r < ruleSequences.length; r++) {
                CompiledSequence[] seqs = ruleSequences[r];
                int[] rulePositions = null;
                for (CompiledSequence seq : seqs) {
                    if (seq.stepBits.length > scratch.length) {
                        scratch = new int[seq.stepBits.length];
                    }
                    if (!reachable(seq, stackBits)) {
                        continue;
                    }
                    int[] hit = matchSequence(frames, seq.stepBits, scratch);
                    if (hit != null && (rulePositions == null || hit[0] < rulePositions[0])) {
                        rulePositions = hit;
                    }
                }
                if (rulePositions == null) {
                    continue;
                }
                if (!innermost) {
                    // config order: the first matching rule wins, the next one is the runner-up
                    if (bestRule == NO_RULE) {
                        bestRule = r;
                        bestPositions = rulePositions;
                    } else {
                        secondRule = r;
                        break;
                    }
                    continue;
                }
                // innermost: lowest anchor wins, ties keep the earlier rule
                if (bestRule == NO_RULE || rulePositions[0] < bestPositions[0]) {
                    if (bestRule != NO_RULE) {
                        // the outgoing best beat everything seen so far, so it is the runner-up
                        secondRule = bestRule;
                        secondAnchor = bestPositions[0];
                    }
                    bestRule = r;
                    bestPositions = rulePositions;
                } else if (rulePositions[0] < secondAnchor) {
                    secondRule = r;
                    secondAnchor = rulePositions[0];
                }
            }

            stackRule[s] = bestRule;
            stackPositions[s] = bestPositions;
            stackRunnerUp[s] = secondRule;
            if (bestRule != NO_RULE) {
                matchedStacks++;
            }
        }

        // ---- fallback states, in the order they appear in the recording ----
        this.unmatchedIndex = addState(new State(config.unmatchedName, config.unmatchedColor, "unmatched", null));
        this.threadStateToState = new int[rec.threadStates.size()];
        for (int i = 0; i < rec.threadStates.size(); i++) {
            String raw = rec.threadStateName(i);
            if (!config.useThreadState || raw == null || raw.isEmpty()) {
                threadStateToState[i] = unmatchedIndex;
                continue;
            }
            String pretty = Config.prettyThreadState(raw);
            threadStateToState[i] = addState(new State(pretty, config.threadStateColor(pretty), "thread-state", null));
        }
    }

    /** Cheap rejection: every step needs at least one candidate frame somewhere in this stack. */
    private boolean reachable(CompiledSequence seq, long[] stackBits) {
        for (long[] step : seq.stepBits) {
            boolean any = false;
            for (int w = 0; w < words; w++) {
                if ((step[w] & stackBits[w]) != 0) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the lowest-anchored occurrence of the sequence in the stack. Steps must appear in
     * order from the leaf outwards; the frames in between are ignored.
     *
     * @return the matched frame indices, or {@code null}
     */
    private int[] matchSequence(int[] frames, long[][] stepBits, int[] scratch) {
        int n = frames.length;
        int steps = stepBits.length;
        for (int i = 0; i + steps <= n; i++) {
            if (!satisfies(frames[i], stepBits[0])) {
                continue;
            }
            scratch[0] = i;
            int step = 1;
            for (int j = i + 1; step < steps && j < n; j++) {
                if (satisfies(frames[j], stepBits[step])) {
                    scratch[step++] = j;
                }
            }
            if (step == steps) {
                return Arrays.copyOf(scratch, steps);
            }
        }
        return null;
    }

    private boolean satisfies(int frameId, long[] stepBits) {
        int base = frameId * words;
        for (int w = 0; w < words; w++) {
            if ((frameBits[base + w] & stepBits[w]) != 0) {
                return true;
            }
        }
        return false;
    }

    private int addState(State s) {
        Integer existing = stateIndexByName.get(s.name);
        if (existing != null) {
            return existing;
        }
        states.add(s);
        int idx = states.size() - 1;
        stateIndexByName.put(s.name, idx);
        return idx;
    }

    /** The state index for a sample with the given stack and raw thread state. */
    public int stateOf(int stackId, int threadStateId) {
        int rule = stackRule[stackId];
        if (rule != NO_RULE) {
            return ruleToState[rule];
        }
        // The tables are snapshots of the fully-read recording; anything unknown is "Other".
        return threadStateId >= 0 && threadStateId < threadStateToState.length
                ? threadStateToState[threadStateId]
                : unmatchedIndex;
    }

    /** Frame indices (leaf = 0) that the matched sequence landed on; empty when nothing matched. */
    public int[] matchPositions(int stackId) {
        return stackPositions[stackId];
    }

    /** Position of the innermost frame that decided this stack's state, or -1. */
    public int matchPos(int stackId) {
        int[] p = stackPositions[stackId];
        return p.length == 0 ? -1 : p[0];
    }

    public boolean matched(int stackId) {
        return stackRule[stackId] != NO_RULE;
    }

    /** Index of the config rule that decided this stack, or -1. */
    public int winningRule(int stackId) {
        return stackRule[stackId];
    }

    /** Index of the best rule that matched this stack but was beaten, or -1. */
    public int runnerUpRule(int stackId) {
        return stackRunnerUp[stackId];
    }

    public int ruleCount() {
        return ruleSequences.length;
    }

    public String ruleName(int rule) {
        return config.states.get(rule).name;
    }

    public List<State> states() {
        return states;
    }

    /** Number of distinct frames that matched at least one pattern &mdash; a sanity check. */
    public int matchedFrameCount() {
        return matchedFrames;
    }

    public int matchedStackCount() {
        return matchedStacks;
    }

    public Config config() {
        return config;
    }
}
