package com.pluskeymap.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * JobScheduler-based health check that survives SIGKILL.
 *
 * Android denies a new full-device log request from the background, even while
 * the static READ_LOGS grant remains present. Therefore a dead detector must
 * never be restarted here; notify the user so MainActivity can recreate the
 * temporary reader session while it is visibly in the foreground.
 */
public class KeepaliveJobService extends JobService {

    private static final String TAG             = "PKM_JobKeepalive";
    static final int            JOB_ID          = 9903;
    private static final String CHANNEL_ALERT   = "pkm_perm_alert";
    private static final int    NOTIF_PERM_LOST = 2003;

    @Override
    public boolean onStartJob(JobParameters params) {
        Context ctx = getApplicationContext();

        boolean wasRunning = ctx.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);

        if (!wasRunning) {
            Log.d(TAG, "onStartJob: service was not running — skip");
            jobFinished(params, false);
            return false;
        }

        boolean hasLogPerm = ctx.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (!hasLogPerm) {
            Log.w(TAG, "onStartJob: static READ_LOGS grant missing — notifying user");
            postPermissionLostNotificationStatic(ctx);
            jobFinished(params, false);
            return false;
        }

        if (!DetectorService.isRunning()) {
            Log.w(TAG, "onStartJob: DetectorService dead — foreground re-auth required");
            postPermissionLostNotificationStatic(ctx);
        } else {
            Log.d(TAG, "onStartJob: DetectorService alive — ok");
        }

        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if OS stopped us early
    }

    // ── Notification ─────────────────────────────────────────────────────────

    static void postPermissionLostNotificationStatic(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ALERT,
                    "Plus 键日志访问提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("系统日志读取会话结束、需要前台重新确认时发出提醒");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        // Open MainActivity — it will detect !hasLogPerm and trigger the OEM dialog flow.
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("reauth_logperm", true);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setContentTitle("Plus 键监听已停止，点击重新启用")
                .setContentText("系统日志读取会话已结束，点击后在前台重新确认。")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Android 只允许前台应用确认完整设备日志访问。"
                                + "点击打开应用并接受系统对话框，即可重新启用 Plus 键检测。"))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify(NOTIF_PERM_LOST, notif);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        if (js.getPendingJob(JOB_ID) != null) {
            Log.d(TAG, "Job already scheduled — skip");
            return;
        }

        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, KeepaliveJobService.class))
                .setPeriodic(60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build();

        int result = js.schedule(job);
        Log.d(TAG, "Job scheduled: " + (result == JobScheduler.RESULT_SUCCESS ? "ok" : "FAILED"));
    }

    /** Dismiss the permission-lost notification (call after perm re-granted). */
    static void dismissPermNotification(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_PERM_LOST);
    }

    static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }
}
