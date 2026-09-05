package com.pluskeymap.app;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.TimeUnit;

public class PlusKeyApp extends Application {

    private static final String TAG              = "PKM_App";
    private static final String KEEPALIVE_WORK   = "pkm_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        SettingsActivity.applySavedTheme(this);

        // Periodic health checks notify when the session is lost. They must not
        // recreate logcat from the background because Android denies that request.
        scheduleKeepalive();
        // JobScheduler check: persisted in OS and survives SIGKILL.
        KeepaliveJobService.schedule(this);
        // Heartbeat: 3-minute repeating alarm living in AlarmManagerService.
        boolean wasRunning = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        if (wasRunning) {
            HeartbeatReceiver.schedule(this);
        }
    }

    private void scheduleKeepalive() {
        try {
            PeriodicWorkRequest keepalive = new PeriodicWorkRequest.Builder(
                    KeepaliveWorker.class, 15, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    KEEPALIVE_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    keepalive);
            Log.d(TAG, "Keepalive WorkManager job scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule keepalive: " + e.getMessage());
        }
    }
}
