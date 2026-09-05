package com.pluskeymap.app;

/** Real input edges do not need the logcat path's synthetic-release/bounce delays. */
final class RawKeyGesture {
    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);
        void cancel(Runnable runnable);
    }
    interface Actions { void single(); void longPress(); }
    private final Scheduler scheduler;
    private final Actions actions;
    private final long thresholdMs;
    private boolean pressed, fired, singleOnly;
    private long downTime;
    private final Runnable timeout = this::onLongTimeout;

    RawKeyGesture(Scheduler scheduler, Actions actions, long thresholdMs) {
        this.scheduler = scheduler;
        this.actions = actions;
        this.thresholdMs = thresholdMs;
    }

    void onKey(boolean down, long timeMs, boolean onlySingle) {
        if (down) {
            if (pressed) return; // A repeated DOWN must not restart the timer.
            pressed = true;
            fired = false;
            singleOnly = onlySingle;
            downTime = timeMs;
            if (singleOnly) {
                fired = true;
                actions.single();
            } else scheduler.postDelayed(timeout, thresholdMs);
        } else if (pressed) {
            scheduler.cancel(timeout);
            pressed = false;
            if (!fired && timeMs >= downTime) {
                if (timeMs - downTime >= thresholdMs) actions.longPress();
                else actions.single();
            }
            fired = false;
        }
    }

    private void onLongTimeout() {
        if (!pressed || fired || singleOnly) return;
        fired = true;
        actions.longPress();
    }

    void reset() {
        scheduler.cancel(timeout);
        pressed = false;
        fired = false;
    }
}
