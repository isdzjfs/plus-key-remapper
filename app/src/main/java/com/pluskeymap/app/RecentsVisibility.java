package com.pluskeymap.app;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

/** Applies the user-controlled visibility of this app's tasks in Recents. */
final class RecentsVisibility {

    private static final String TAG = "RecentsVisibility";

    private RecentsVisibility() {}

    static void applySavedSetting(Context context) {
        boolean excluded = context.getSharedPreferences(
                        SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_EXCLUDE_FROM_RECENTS, false);
        setExcluded(context, excluded);
    }

    static void setExcluded(Context context, boolean excluded) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return;

        for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
            try {
                task.setExcludeFromRecents(excluded);
            } catch (RuntimeException e) {
                // A task may disappear while the list is being traversed.
                Log.w(TAG, "Unable to update task visibility in Recents", e);
            }
        }
    }
}
