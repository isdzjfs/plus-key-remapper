package com.pluskeymap.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.core.content.ContextCompat;
import rikka.shizuku.Shizuku;

final class DetectionBackend {
    static final String KEY = "detection_backend";
    static final String SHIZUKU = "shizuku";
    static final String LOGCAT = "logcat";

    static boolean usesShizuku(Context context) {
        return SHIZUKU.equals(context.getSharedPreferences(SettingsActivity.PREFS_SETTINGS, 0)
                .getString(KEY, LOGCAT));
    }

    static boolean isShizukuAvailable() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11(); }
        catch (RuntimeException e) { return false; }
    }

    static boolean isShizukuGranted() {
        try { return isShizukuAvailable()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }

    /** Existing background entry points use this only for the selected Shizuku backend. */
    static void recover(Context context) {
        if (!usesShizuku(context) || !context.getSharedPreferences(SettingsActivity.PREFS_SETTINGS, 0)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false)) return;
        if (DetectorService.isRunning()) return; // Its connection monitor handles reader death.
        if (isShizukuGranted()) {
            try {
                ContextCompat.startForegroundService(context,
                        new Intent(context, DetectorService.class).setAction(DetectorService.ACTION_START));
                return;
            } catch (RuntimeException e) {
                // Android/OEM may disallow this FGS start; the notification provides a foreground path.
                Log.w("PKM_Shizuku", "Background detector recovery rejected", e);
            }
        }
        KeepaliveJobService.postPermissionLostNotificationStatic(context);
    }
}
