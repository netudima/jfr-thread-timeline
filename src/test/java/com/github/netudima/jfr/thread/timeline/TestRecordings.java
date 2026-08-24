package com.github.netudima.jfr.thread.timeline;

import java.io.StringReader;

/** Builds synthetic {@link Recording}s so the classifier and timeline can be tested without a JFR file. */
final class TestRecordings {

    static final long MS = 1_000_000L;

    static Config config(String yaml) {
        return Config.load(new StringReader(yaml), "test");
    }

    static Recording empty() {
        Recording rec = new Recording();
        rec.eventTypeNames.add("jdk.ExecutionSample");
        rec.usedEventTypes.add("jdk.ExecutionSample");
        return rec;
    }

    /** Interns a stack given leaf-first frame names. */
    static int stack(Recording rec, String... framesLeafFirst) {
        int[] ids = new int[framesLeafFirst.length];
        for (int i = 0; i < framesLeafFirst.length; i++) {
            ids[i] = rec.frames.intern(framesLeafFirst[i]);
        }
        return rec.stacks.intern(ids);
    }

    static Recording.ThreadSamples thread(Recording rec, String name) {
        return rec.thread(name, 1, name.hashCode() & 0xffff);
    }

    /** Adds one instantaneous sample at {@code atMillis} from the (arbitrary) epoch base. */
    static void sample(Recording rec, Recording.ThreadSamples t, long atMillis, int stackId, String state) {
        t.add(atMillis * MS, 0, stackId, rec.internThreadState(state), 0);
        rec.totalSamples++;
    }

    static void sampleWithDuration(Recording rec, Recording.ThreadSamples t, long atMillis,
                                   long durationMillis, int stackId, String state) {
        t.add(atMillis * MS, durationMillis * MS, stackId, rec.internThreadState(state), 0);
        rec.totalSamples++;
    }

    static void finish(Recording rec) {
        for (Recording.ThreadSamples t : rec.threads()) {
            t.sortByTime();
        }
        rec.recomputeBounds();
    }

    static Timeline build(Recording rec, Config config) {
        finish(rec);
        return Timeline.build(rec, new Classifier(config, rec), config, new Log(Log.Level.QUIET));
    }

    static int stateIndex(Classifier c, String name) {
        for (int i = 0; i < c.states().size(); i++) {
            if (c.states().get(i).name.equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no state named '" + name + "' in " + c.states().size() + " states");
    }

    private TestRecordings() {
    }
}
