package com.pluskeymap.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager periodic health check for DetectorService.
 *
 * A new full-device log reader cannot be authorized from a background worker.
 * If the detector is gone, this worker posts the foreground re-auth notification
 * instead of starting a service that could only see the app's own logs.
 */
public class KeepaliveWorker extends Worker {

    private static final String TAG = "PKM_Keepalive";

    public KeepaliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Only monitor if the user had the service running (not explicitly stopped).
        boolean wasRunning = ctx.getSharedPreferences(SettingsActivity.PREFS_SETTINGS,
                Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);

        if (!wasRunning) {
            Log.d(TAG, "doWork: service was not running — skip");
            return Result.success();
        }

        boolean hasLogPerm = ctx.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!hasLogPerm) {
            Log.w(TAG, "doWork: static READ_LOGS grant missing — notifying user");
            KeepaliveJobService.postPermissionLostNotificationStatic(ctx);
            return Result.success();
        }

        if (!DetectorService.isRunning()) {
            Log.w(TAG, "doWork: DetectorService not running — foreground re-auth required");
            KeepaliveJobService.postPermissionLostNotificationStatic(ctx);
        } else {
            Log.d(TAG, "doWork: DetectorService alive — ok");
        }

        return Result.success();
    }
}
