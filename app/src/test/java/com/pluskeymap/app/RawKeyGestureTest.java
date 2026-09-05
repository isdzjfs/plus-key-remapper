package com.pluskeymap.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RawKeyGestureTest {
    private static final class Fixture implements RawKeyGesture.Scheduler, RawKeyGesture.Actions {
        int singles, longs;
        Runnable pending;
        final RawKeyGesture gesture = new RawKeyGesture(this, this, 850);
        public void postDelayed(Runnable r, long ms) { assertEquals(850, ms); pending = r; }
        public void cancel(Runnable r) { if (pending == r) pending = null; }
        public void single() { singles++; }
        public void longPress() { longs++; }
        void timeout() { Runnable task = pending; pending = null; if (task != null) task.run(); }
    }

    @Test public void shortTapFiresOnceOnRealUp() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, false);
        assertEquals(0, f.singles);
        f.gesture.onKey(false, 1164, false);
        f.gesture.onKey(false, 1164, false);
        f.timeout();
        assertEquals(1, f.singles);
        assertEquals(0, f.longs);
    }

    @Test public void longHoldAndRepeatedDownNeverAlsoFireSingle() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, false);
        f.timeout();
        f.gesture.onKey(true, 2000, false);
        f.gesture.onKey(false, 3560, false);
        assertEquals(1, f.longs);
        assertEquals(0, f.singles);
    }

    @Test public void timestampsResolveLongPressWhenMainThreadTimerWasDelayed() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, false);
        f.gesture.onKey(false, 3160, false);
        f.timeout();
        assertEquals(1, f.longs);
        assertEquals(0, f.singles);
    }

    @Test public void singleOnlyFiresImmediatelyAndNeverRepeatsDuringHold() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, true);
        assertEquals(1, f.singles);
        f.gesture.onKey(true, 2000, true);
        f.gesture.onKey(false, 3500, true);
        f.timeout();
        assertEquals(1, f.singles);
        assertEquals(0, f.longs);
    }

    @Test public void disconnectOrDetectionModeChangeCancelsPendingGesture() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, false);
        f.gesture.reset();
        f.timeout();
        f.gesture.onKey(false, 4000, false);
        assertEquals(0, f.singles + f.longs);
        f.gesture.onKey(true, 5000, false);
        f.gesture.onKey(false, 5100, false);
        assertEquals(1, f.singles);
    }

    @Test public void rapidIndependentTapsAreNotDroppedByOldLogcatDebounce() {
        Fixture f = new Fixture();
        f.gesture.onKey(true, 1000, false);
        f.gesture.onKey(false, 1040, false);
        f.gesture.onKey(true, 1120, false);
        f.gesture.onKey(false, 1160, false);
        assertEquals(2, f.singles);
    }
}
