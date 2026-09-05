package com.pluskeymap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Only auto-start if service was running before the reboot.
        // We infer this from whether the user completed setup (READ_LOGS granted)
        // and had not explicitly stopped the service (no stored "stopped" pref).
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        if (!wasRunning) {
            Log.d("PKM_Boot", "Service was not running before reboot — skipping auto-start");
            return;
        }

        // Full-device log access is session-scoped and Android rejects new
        // requests from a background boot receiver. Ask the user to reopen the
        // app instead of starting a detector that cannot receive Plus-key logs.
        Log.d("PKM_Boot", "Boot completed — foreground log re-auth required");
        KeepaliveJobService.postPermissionLostNotificationStatic(context);
    }
}
