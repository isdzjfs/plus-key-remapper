package com.pluskeymap.app;

import android.animation.ValueAnimator;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 410;
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermission =
            (requestCode, result) -> runOnUiThread(() -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST || isFinishing()
                        || !DetectionBackend.usesShizuku(this)) return;
                applySkippedState(false);
                if (result == PackageManager.PERMISSION_GRANTED) startDetectorService();
                else Snackbar.make(findViewById(android.R.id.content),
                        "未获得 Shizuku 授权，可在 Shizuku 的已授权应用中重新允许。", Snackbar.LENGTH_LONG).show();
                refreshServiceStatus(true);
            });

    private SharedPreferences prefs;

    // Status card
    private MaterialCardView cardStatus;
    private android.widget.ImageView ivStatusIcon;
    private TextView         tvStatusTitle, tvStatusSub;
    private MaterialButton   btnEnableService;

    // Skipped-setup banner
    private MaterialCardView cardSetupBanner;
    private TextView         tvBannerTitle, tvBannerBody;
    private boolean serviceRunning = false;
    // Set when OEM logcat dialog is denied mid-session. Cleared when user retries.
    private boolean oemLogcatDenied = false;
    // Notification launches can reach onCreate before this Activity is actually
    // top-resumed. Defer logcat creation until the window has foreground focus.
    private boolean pendingForegroundReauth = false;

    // Polls permission state while activity is visible - catches OEM "allow read
    // logs" system overlays that do NOT trigger onPause/onResume, and detects
    // when the OEM dialog is accepted (logcatEverConfirmed flips to true).
    private final android.os.Handler permPoller =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable permCheckRunnable = new Runnable() {
        @Override public void run() {
            if (!DetectionBackend.usesShizuku(MainActivity.this) && serviceRunning && (!isPermissionGranted() || DetectorService.isLogcatDenied(MainActivity.this))) {
                stopDetectorService();
                applySkippedState(false);
            }
            // Refresh status on every tick - catches logcatConfirmed flipping to true
            // mid-session (dialog accepted while UI is open) without needing a broadcast.
            refreshServiceStatus(false);
            permPoller.postDelayed(this, 1_500);
        }
    };

    // Detector card
    private MaterialCardView cardDetector;
    private TextView         tvDetectedKeycode, tvDetectedAction;
    private MaterialButton   btnDetect, btnClearKey;
    private boolean          detectMode = false;

    // Binding rows
    private TextView tvSingleLabel;
    private TextView tvLongLabel;

    // Camera shutter card
    private MaterialCardView cardCameraShutter;
    private SwitchMaterial   switchCameraShutter;

    private final BroadcastReceiver keyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DetectorService.ACTION_LOGCAT_FAILED.equals(intent.getAction())) {
                serviceRunning = false;
                oemLogcatDenied = true;
                applySkippedState(false);
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_OEM_DENIED.equals(intent.getAction())) {
                serviceRunning = false;
                oemLogcatDenied = true;
                applySkippedState(true);
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_CONFIRMED.equals(intent.getAction())
                    || DetectorService.ACTION_INPUT_STATE_CHANGED.equals(intent.getAction())) {
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_VERIFYING.equals(intent.getAction())) {
                refreshServiceStatus(true);
                return;
            }
            int    code   = intent.getIntExtra(DetectorService.EXTRA_KEYCODE, -1);
            String act    = intent.getStringExtra(DetectorService.EXTRA_ACTION);
            String source = intent.getStringExtra("source");
            if (!detectMode) return;
            if (code == LogcatWatcher.PLUS_KEY_CODE && ("logcat".equals(source) || "shizuku".equals(source))) {
                prefs.edit().putInt(ActionExecutor.KEY_DETECTED_KEYCODE, code).apply();
                tvDetectedKeycode.setText("✓ 已检测到 Plus 键！");
                String actLabel = "down".equals(act) ? "按下"
                        : ("up".equals(act) ? "释放" : String.valueOf(act));
                tvDetectedAction.setText("事件：" + actLabel + ("shizuku".equals(source) ? "（来源：Shizuku 底层按键）" : "（来源：KEYLOG_OplusKeyEventUtil）"));
                detectMode = false;
                DetectorService.setDetectMode(false);
                btnDetect.setText("开始检测");
                // Restore service to its pre-detect state
                if (!serviceWasRunningBeforeDetect) {
                    stopDetectorService();
                }
                refreshServiceStatus(true);
                Snackbar.make(findViewById(android.R.id.content),
                        "Plus 键已确认！请在下方分配操作。",
                        Snackbar.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = ActionExecutor.prefs(this);
        // MainActivity can also be opened directly from service notifications.
        RecentsVisibility.applySavedSetting(this);

        bindViews();
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermission);
        applySkippedState(false);
        applySingleOnlyMode();
        refreshServiceStatus(false);
        refreshBindingLabels();

        if (savedInstanceState == null) runEntranceAnimation();

        // A notification retry is queued until this Activity has window focus;
        // Android rejects full-device log requests made while still backgrounded.
        if (getIntent() != null && getIntent().getBooleanExtra("reauth_logperm", false)) {
            handleReauthIntent();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("reauth_logperm", false)) {
            handleReauthIntent();
        }
    }

    /**
     * Called when the user taps the log-session notification. The actual start
     * waits for window focus so LogcatManager sees this package as foreground.
     */
    private void handleReauthIntent() {
        pendingForegroundReauth = true;
        if (getIntent() != null) getIntent().removeExtra("reauth_logperm");
        if (hasWindowFocus()) performPendingForegroundReauth();
    }

    private void performPendingForegroundReauth() {
        if (!pendingForegroundReauth) return;
        boolean wasRunning = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        pendingForegroundReauth = false;
        if (!wasRunning) return; // user had stopped it - don't auto-restart
        if (DetectionBackend.usesShizuku(this)) { authorizeShizuku(); return; }
        if (!isPermissionGranted()) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        KeepaliveJobService.dismissPermNotification(this);
        oemLogcatDenied = false;
        serviceRunning = true;
        launchDetectorService();
        refreshServiceStatus(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) performPendingForegroundReauth();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync the in-memory flag with the actual service state so that
        // returning from background never incorrectly shows "Paused".
        serviceRunning = DetectorService.isRunning();
        // If the service is running but permission was revoked/denied while we
        // were in the background (e.g. user dismissed the overlay dialog), stop
        // the service immediately so the UI never shows "Active" without perms.
        if (!DetectionBackend.usesShizuku(this) && serviceRunning && (!isPermissionGranted() || DetectorService.isLogcatDenied(this))) {
            stopDetectorService();
        }
        applySkippedState(oemLogcatDenied);
        applySingleOnlyMode();
        refreshServiceStatus(false);
        // Re-evaluate the shutter card state in case the user just granted/revoked
        // accessibility access from the system settings screen.
        if (switchCameraShutter != null) {
            refreshShutterCardState(switchCameraShutter.isChecked());
        }
        IntentFilter filter = new IntentFilter(DetectorService.ACTION_KEY_DETECTED);
        filter.addAction(DetectorService.ACTION_LOGCAT_FAILED);
        filter.addAction(DetectorService.ACTION_LOGCAT_OEM_DENIED);
        filter.addAction(DetectorService.ACTION_LOGCAT_CONFIRMED);
        filter.addAction(DetectorService.ACTION_INPUT_STATE_CHANGED);
        filter.addAction(DetectorService.ACTION_LOGCAT_VERIFYING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(keyReceiver, filter);
        }
        permPoller.post(permCheckRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        permPoller.removeCallbacks(permCheckRunnable);
        unregisterReceiver(keyReceiver);
    }

    @Override protected void onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        super.onDestroy();
    }

    private void chooseDetectionBackend() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("检测方式")
                .setSingleChoiceItems(new String[]{"Shizuku 底层按键（推荐）", "系统日志（兼容模式）"},
                        DetectionBackend.usesShizuku(this) ? 0 : 1, (dialog, which) -> {
                            boolean wasRunning = DetectorService.isRunning();
                            DetectorService.setDetectMode(false);
                            detectMode = false;
                            btnDetect.setText("开始检测");
                            // Stop the old backend synchronously before saving the new selection.
                            DetectorService.stopForBackendChange(this);
                            serviceRunning = false;
                            getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE).edit()
                                    .putString(DetectionBackend.KEY, which == 0
                                            ? DetectionBackend.SHIZUKU : DetectionBackend.LOGCAT)
                                    .putBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, wasRunning).apply();
                            oemLogcatDenied = false;
                            dialog.dismiss();
                            updateBackendLabel();
                            applySkippedState(false);
                            refreshServiceStatus(true);
                            if (which == 0) authorizeShizuku();
                            else if (wasRunning && isPermissionGranted())
                                permPoller.postDelayed(this::startDetectorService, 300);
                        })
                .setNegativeButton("取消", null).show();
    }

    private void updateBackendLabel() {
        ((MaterialButton) findViewById(R.id.btnDetectionBackend)).setText(
                DetectionBackend.usesShizuku(this)
                        ? "检测方式：Shizuku 底层按键（点击切换）" : "检测方式：系统日志（点击切换）");
    }

    private void authorizeShizuku() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!DetectionBackend.isShizukuAvailable()) {
            new MaterialAlertDialogBuilder(this).setTitle("请先启动 Shizuku")
                    .setMessage("在 Shizuku 中通过无线调试或电脑 ADB 启动服务，然后返回本应用点击启用。非 Root 模式下，手机重启后需要重新启动 Shizuku。")
                    .setPositiveButton("打开 Shizuku", (d, w) -> {
                        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (launch != null) startActivity(launch);
                        else startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://shizuku.rikka.app/download/")));
                    }).setNegativeButton("稍后", null).show();
            return;
        }
        if (DetectionBackend.isShizukuGranted()) {
            // Allow onDestroy of the previous detector to finish before starting its replacement.
            permPoller.postDelayed(this::startDetectorService, 300);
        } else {
            try { rikka.shizuku.Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST); }
            catch (RuntimeException e) {
                Snackbar.make(findViewById(android.R.id.content),
                        "Shizuku 授权请求失败，请检查 Shizuku 是否运行。", Snackbar.LENGTH_LONG).show();
            }
        }
    }

    // ── View wiring ─────────────────────────────────────────────────────────

    private void bindViews() {
        findViewById(R.id.btnDetectionBackend).setOnClickListener(v -> chooseDetectionBackend());
        updateBackendLabel();
        cardSetupBanner    = findViewById(R.id.cardSetupBanner);
        tvBannerTitle      = findViewById(R.id.tvBannerTitle);
        tvBannerBody       = findViewById(R.id.tvBannerBody);

        cardStatus       = findViewById(R.id.cardStatus);
        ivStatusIcon     = findViewById(R.id.ivStatusIcon);
        tvStatusTitle    = findViewById(R.id.tvStatusTitle);
        tvStatusSub      = findViewById(R.id.tvStatusSub);
        btnEnableService = findViewById(R.id.btnEnableService);

        cardDetector      = findViewById(R.id.cardDetector);
        tvDetectedKeycode = findViewById(R.id.tvDetectedKeycode);
        tvDetectedAction  = findViewById(R.id.tvDetectedAction);
        btnDetect         = findViewById(R.id.btnDetect);
        btnClearKey       = findViewById(R.id.btnClearKey);

        tvSingleLabel = findViewById(R.id.tvSingleLabel);
        tvLongLabel   = findViewById(R.id.tvLongLabel);

        cardCameraShutter  = findViewById(R.id.cardCameraShutter);
        switchCameraShutter = findViewById(R.id.switchCameraShutter);
        bindCameraShutterCard();

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Re-open setup from the banner
        MaterialButton btnReopenSetup = findViewById(R.id.btnReopenSetup);
        btnReopenSetup.setOnClickListener(v -> {
            if (DetectionBackend.usesShizuku(this)) { authorizeShizuku(); return; }
            boolean permGranted      = isPermissionGranted();
            boolean oemEverConfirmed = DetectorService.isLogcatEverConfirmed(this);
            boolean oemDeniedPersist = DetectorService.isLogcatDenied(this);

            if (permGranted && (oemLogcatDenied || oemDeniedPersist || !oemEverConfirmed)) {
                // ADB perm is fine - we just need the OEM system dialog to appear.
                // Starting DetectorService is what triggers the dialog. Clear all
                // denial state first so the service gets a clean attempt.
                oemLogcatDenied = false;
                getSharedPreferences(DetectorService.PREFS_LOGCAT, MODE_PRIVATE)
                        .edit()
                        .putBoolean(DetectorService.KEY_LOGCAT_DENIED, false)
                        .apply();
                applySkippedState(false);
                // If service is already running (shouldn't be, but be safe), stop it
                // first so it restarts fresh and the dialog re-appears.
                if (DetectorService.isRunning()) {
                    Intent stop = new Intent(this, DetectorService.class)
                            .setAction(DetectorService.ACTION_STOP);
                    startService(stop);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this::startDetectorService, 500);
                } else {
                    startDetectorService();
                }
            } else {
                startActivity(new Intent(this, SetupActivity.class));
            }
        });

        btnDetect.setOnClickListener(v -> toggleDetectMode());

        btnClearKey.setOnClickListener(v -> {
            prefs.edit().remove(ActionExecutor.KEY_DETECTED_KEYCODE).apply();
            tvDetectedKeycode.setText("请按下 Plus 键…");
            tvDetectedAction.setText("");
            refreshServiceStatus(true);
        });

        // Status card "Re-run Setup" button
        btnEnableService.setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));

        cardStatus.setOnClickListener(v -> {
            if (DetectionBackend.usesShizuku(this)) {
                if (DetectorService.isRunning() && DetectionBackend.isShizukuGranted()) stopDetectorService();
                else authorizeShizuku();
                return;
            }
            if (!isPermissionGranted()) {
                startActivity(new Intent(this, SetupActivity.class));
                return;
            }
            if (serviceRunning) {
                stopDetectorService();
            } else {
                startDetectorService();
            }
        });

        findViewById(R.id.cardSingle).setOnClickListener(v -> showActionPicker(
                ActionExecutor.KEY_ACTION_SINGLE,
                ActionExecutor.KEY_LAUNCH_PKG_SINGLE,
                ActionExecutor.KEY_CUSTOM_INTENT_SINGLE, tvSingleLabel));

        // cardLong click listener is set in applySingleOnlyMode() so it
        // respects the enabled/disabled state without setOnClickListener
        // overriding clickable=false.
    }

    // ── Camera shutter card ──────────────────────────────────────────────────

    private View rowA11yWarning;

    /**
     * Initialises the camera shutter toggle card.
     *
     * Visual states:
     *   • Toggle OFF → normal card, no warning row.
     *   • Toggle ON + a11y granted → normal card, no warning row.
     *   • Toggle ON + a11y NOT granted → card background tinted red,
     *       warning row visible; tapping the card body opens Accessibility Settings.
     *
     * The preference is saved immediately on toggle - no dialog. When the card
     * is in the warning state, tapping anywhere on it opens Accessibility Settings
     * rather than re-toggling. The switch still works normally for on/off.
     */
    private void bindCameraShutterCard() {
        rowA11yWarning = findViewById(R.id.rowA11yWarning);

        SharedPreferences settings = getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE);
        boolean enabled = settings.getBoolean(
                ActionExecutor.KEY_CAMERA_SHUTTER_ENABLED, false);
        switchCameraShutter.setChecked(enabled);
        refreshShutterCardState(enabled);

        // Card body tap: if warning state → open a11y settings; otherwise toggle.
        cardCameraShutter.setOnClickListener(v -> {
            if (switchCameraShutter.isChecked() && !isAccessibilityServiceEnabled()) {
                openAccessibilitySettings();
            } else {
                boolean nowEnabled = !switchCameraShutter.isChecked();
                applyShutterToggle(nowEnabled);
            }
        });

        // Switch tap: always toggles; card visual updates accordingly.
        switchCameraShutter.setOnClickListener(v ->
                applyShutterToggle(switchCameraShutter.isChecked()));
    }

    /** Saves the shutter preference and refreshes the card visual state. */
    private void applyShutterToggle(boolean nowEnabled) {
        switchCameraShutter.setChecked(nowEnabled);
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit()
                .putBoolean(ActionExecutor.KEY_CAMERA_SHUTTER_ENABLED, nowEnabled)
                .apply();
        refreshShutterCardState(nowEnabled);
    }

    /**
     * Updates the camera shutter card's visual state:
     *   - Toggle ON + a11y missing → red-tinted card + warning row visible.
     *   - Any other state → normal surface card + warning row gone.
     *
     * Called on toggle change and on every onResume so the card reflects the
     * current a11y state without the user needing to leave and return.
     */
    private void refreshShutterCardState(boolean shutterEnabled) {
        boolean needsA11y = shutterEnabled && !isAccessibilityServiceEnabled();

        if (needsA11y) {
            // Red-tinted background using Material3 errorContainer colour.
            int errorContainer = com.google.android.material.color.MaterialColors.getColor(
                    cardCameraShutter,
                    com.google.android.material.R.attr.colorErrorContainer,
                    getColor(android.R.color.holo_red_dark));
            int errorStroke = com.google.android.material.color.MaterialColors.getColor(
                    cardCameraShutter,
                    com.google.android.material.R.attr.colorError,
                    getColor(android.R.color.holo_red_dark));
            cardCameraShutter.setCardBackgroundColor(errorContainer);
            cardCameraShutter.setStrokeColor(errorStroke);
            cardCameraShutter.setStrokeWidth(
                    (int) (getResources().getDisplayMetrics().density * 1.5f));
            rowA11yWarning.setVisibility(View.VISIBLE);
        } else {
            // Restore normal surface appearance.
            int surface = com.google.android.material.color.MaterialColors.getColor(
                    cardCameraShutter,
                    com.google.android.material.R.attr.colorSurface,
                    getColor(android.R.color.white));
            int outline = com.google.android.material.color.MaterialColors.getColor(
                    cardCameraShutter,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    getColor(android.R.color.darker_gray));
            cardCameraShutter.setCardBackgroundColor(surface);
            cardCameraShutter.setStrokeColor(outline);
            cardCameraShutter.setStrokeWidth(
                    (int) (getResources().getDisplayMetrics().density * 1f));
            rowA11yWarning.setVisibility(View.GONE);
        }
    }

    /** Opens the system Accessibility Settings screen. */
    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Returns true if PlusKeyService is currently listed in
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
     */
    private boolean isAccessibilityServiceEnabled() {
        String expected = getPackageName() + "/" + PlusKeyService.class.getName();
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            for (String component : enabled.split(":")) {
                if (component.trim().equalsIgnoreCase(expected)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Single-only mode ─────────────────────────────────────────────────────

    private void applySingleOnlyMode() {
        boolean singleOnly = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);
        View cardLong = findViewById(R.id.cardLong);
        if (cardLong == null) return;
        float alpha = singleOnly ? 0.35f : 1.0f;
        cardLong.setAlpha(alpha);
        setViewTreeEnabled(cardLong, !singleOnly);
        if (singleOnly) {
            cardLong.setClickable(false);
            cardLong.setOnClickListener(null);
        } else {
            cardLong.setOnClickListener(v -> showActionPicker(
                    ActionExecutor.KEY_ACTION_LONG,
                    ActionExecutor.KEY_LAUNCH_PKG_LONG,
                    ActionExecutor.KEY_CUSTOM_INTENT_LONG, tvLongLabel));
        }
    }

    // ── Skipped-setup state ─────────────────────────────────────────────────

    /**
     * Shows the setup banner only when ADB permission is missing or setup was
     * explicitly skipped. All other states (OEM dialog, verifying, denied) are
     * handled inline by the status card - no need to block the whole UI.
     */
    private void applySkippedState(boolean oemDenied) {
        if (DetectionBackend.usesShizuku(this)) {
            cardSetupBanner.setVisibility(View.GONE);
            cardStatus.setVisibility(View.VISIBLE);
            for (int id : new int[]{R.id.cardDetector, R.id.cardBindings, R.id.cardCameraShutter}) {
                View view = findViewById(id);
                view.setAlpha(1f);
                setViewTreeEnabled(view, true);
            }
            return;
        }
        boolean skipped     = wasSetupSkipped();
        boolean permGranted = isPermissionGranted();
        boolean oemEverConfirmed = DetectorService.isLogcatEverConfirmed(this);

        // If ADB permission is now granted and OEM dialog confirmed, clear skipped flag.
        if (permGranted && oemEverConfirmed && skipped) {
            getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                    .edit().putBoolean(SetupActivity.KEY_SKIPPED, false).apply();
            skipped = false;
        }

        // Only block the UI with the banner if ADB permission is missing.
        boolean showBanner = skipped || !permGranted;
        cardSetupBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        cardStatus.setVisibility(showBanner ? View.GONE : View.VISIBLE);

        if (showBanner && tvBannerTitle != null && tvBannerBody != null) {
            MaterialButton btnReopen = findViewById(R.id.btnReopenSetup);
            tvBannerTitle.setText("尚未完成设置");
            tvBannerBody.setText("尚未授予 ADB 权限，按键检测和操作绑定已停用。请完成设置以启用这些功能。");
            if (btnReopen != null) btnReopen.setText("完成设置");
        }

        int[] grayIds = { R.id.cardDetector, R.id.cardBindings, R.id.cardCameraShutter };
        float alpha  = showBanner ? 0.3f : 1.0f;
        boolean enable = !showBanner;
        for (int id : grayIds) {
            View v = findViewById(id);
            if (v == null) continue;
            v.animate().alpha(alpha).setDuration(220).start();
            setViewTreeEnabled(v, enable);
        }
    }

    /**
     * Recursively enable/disable all views in a hierarchy.
     *
     * When DISABLING we also clear the clickable flag so disabled children
     * cannot swallow touch events.  When RE-ENABLING we only restore
     * setEnabled - we deliberately do NOT call setClickable(true) on every
     * descendant, because views that were never meant to be clickable (e.g.
     * TextViews and icons inside a card) would start intercepting touches and
     * prevent the parent MaterialCardView's OnClickListener from firing.
     */
    private void setViewTreeEnabled(View root, boolean enabled) {
        root.setEnabled(enabled);
        // When disabling: clear clickable so disabled children don't swallow touches.
        // When enabling: restore clickable on views that have a click listener
        // (identified by having a tag set before we disabled them).
        if (!enabled) {
            if (root.isClickable()) root.setTag(R.id.tag_was_clickable, Boolean.TRUE);
            root.setClickable(false);
        } else {
            if (Boolean.TRUE.equals(root.getTag(R.id.tag_was_clickable))) {
                root.setClickable(true);
                root.setTag(R.id.tag_was_clickable, null);
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), enabled);
            }
        }
    }

    private boolean wasSetupSkipped() {
        return getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                .getBoolean(SetupActivity.KEY_SKIPPED, false);
    }

    // ── Entrance animation ──────────────────────────────────────────────────

    private void runEntranceAnimation() {
        int[] ids = { R.id.cardSetupBanner, R.id.cardStatus,
                R.id.cardDetector, R.id.cardBindings, R.id.cardCameraShutter };
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v == null || v.getVisibility() == View.GONE) continue;
            v.setAlpha(0f);
            v.setTranslationY(80f);
            v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(80L + i * 65L)
                    .setDuration(360)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    // ── Status card ─────────────────────────────────────────────────────────

    private void refreshServiceStatus(boolean animate) {
        if (DetectionBackend.usesShizuku(this)) {
            serviceRunning = DetectorService.isRunning();
            boolean ready = DetectorService.isInputReady();
            if (!Settings.canDrawOverlays(this)) {
                tvStatusTitle.setText("需要允许悬浮窗权限");
                tvStatusSub.setText("点击授权，以便在后台启动映射的应用。");
            } else if (!DetectionBackend.isShizukuAvailable()) {
                tvStatusTitle.setText("Shizuku 未运行");
                tvStatusSub.setText("点击查看启动方式；此模式无需系统日志授权。");
            } else if (!DetectionBackend.isShizukuGranted()) {
                tvStatusTitle.setText("需要 Shizuku 授权");
                tvStatusSub.setText("点击授权并启用底层按键监听。");
            } else {
                tvStatusTitle.setText(ready ? "已启用，正在监听 Plus 键"
                        : (serviceRunning ? "正在恢复底层按键监听" : "已暂停，点击启用"));
                tvStatusSub.setText(serviceRunning ? DetectorService.getInputStatus()
                        : "直接检测按下和松开，无需读取系统日志。");
            }
            ivStatusIcon.setImageResource(ready ? R.drawable.ic_status_active : R.drawable.ic_status_warning);
            cardStatus.setCardBackgroundColor(resolveColor(ready
                    ? com.google.android.material.R.attr.colorPrimaryContainer
                    : com.google.android.material.R.attr.colorSurfaceVariant));
            btnEnableService.setVisibility(View.GONE);
            return;
        }
        int     savedCode = prefs.getInt(ActionExecutor.KEY_DETECTED_KEYCODE,
                ActionExecutor.KEYCODE_UNSET);
        boolean keySet  = savedCode != ActionExecutor.KEYCODE_UNSET;
        boolean logPerm = isPermissionGranted();

        int targetColor;

        if (!logPerm) {
            // No ADB permission - setup not done.
            tvStatusTitle.setText("需要完成设置");
            tvStatusSub.setText("点击打开设置并授予所需权限。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_warning);
            targetColor = resolveColor(com.google.android.material.R.attr.colorErrorContainer);
        } else if (DetectorService.isLogcatConfirmed()) {
            // Fully working.
            tvStatusTitle.setText("已启用，正在监听 Plus 键");
            tvStatusSub.setText("点击暂停监听。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_active);
            targetColor = resolveColor(com.google.android.material.R.attr.colorPrimaryContainer);
        } else if (serviceRunning) {
            // The reader can always see this app's own lines. It becomes active
            // only after a line from another process proves full-device access.
            tvStatusTitle.setText("正在确认系统日志访问");
            tvStatusSub.setText("请在系统弹窗中选择允许；无需先按 Plus 键。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_warning);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSecondaryContainer);
        } else if (oemLogcatDenied || DetectorService.isLogcatDenied(this)) {
            tvStatusTitle.setText("系统日志访问已停止");
            tvStatusSub.setText("点击后在前台重新确认并恢复 Plus 键监听。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_warning);
            targetColor = resolveColor(com.google.android.material.R.attr.colorErrorContainer);
        } else if (!keySet) {
            tvStatusTitle.setText("准备就绪，点击启动");
            tvStatusSub.setText("请先检测 Plus 键，然后点击启用。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_paused);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant);
        } else {
            tvStatusTitle.setText("已暂停");
            tvStatusSub.setText("点击恢复监听。");
            ivStatusIcon.setImageResource(R.drawable.ic_status_paused);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant);
        }

        btnEnableService.setVisibility(View.GONE);

        if (animate) {
            animateCardColor(cardStatus, targetColor);
        } else {
            cardStatus.setCardBackgroundColor(targetColor);
        }
    }

    private void animateCardColor(MaterialCardView card, int toColor) {
        int fromColor = card.getCardBackgroundColor() != null
                ? card.getCardBackgroundColor().getDefaultColor()
                : toColor;
        ValueAnimator anim = ValueAnimator.ofArgb(fromColor, toColor);
        anim.setDuration(400);
        anim.addUpdateListener(a -> card.setCardBackgroundColor((int) a.getAnimatedValue()));
        anim.start();
    }

    private int resolveColor(int attrRes) {
        int[] attrs = { attrRes };
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        // Fall back to colorSurface so cards never turn invisible on either theme.
        int fallback = resolveAttrColor(com.google.android.material.R.attr.colorSurface);
        int color = ta.getColor(0, fallback);
        ta.recycle();
        return color;
    }

    private int resolveAttrColor(int attrRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    // ── Detect mode ─────────────────────────────────────────────────────────

    private boolean serviceWasRunningBeforeDetect = false;

    private void toggleDetectMode() {
        if (DetectionBackend.usesShizuku(this) && !isPermissionGranted()) { authorizeShizuku(); return; }
        detectMode = !detectMode;
        if (detectMode) {
            serviceWasRunningBeforeDetect = serviceRunning;
            btnDetect.setText("停止检测");
            tvDetectedKeycode.setText("现在请按下 Plus 键…");
            tvDetectedAction.setText(DetectionBackend.usesShizuku(this)
                    ? "正在通过 Shizuku 监听底层按键" : "正在通过系统日志监听");
            DetectorService.setDetectMode(true);
            // Do not replace an already-approved reader session just to enter
            // detect mode; a replacement would require another system approval.
            if (!DetectorService.isRunning()) launchDetectorService();
        } else {
            btnDetect.setText("开始检测");
            DetectorService.setDetectMode(false);
            // If the service wasn't running before detect mode, stop it again
            if (!serviceWasRunningBeforeDetect) {
                stopDetectorService();
            }
        }
    }

    private void startDetectorService() {
        if (!isPermissionGranted()) {
            refreshServiceStatus(true);
            return;
        }
        // Clear any previously persisted denial so the service gets a fresh attempt.
        // The new foreground session will confirm after any external-process
        // log line arrives, without requiring a physical Plus-key press.
        getSharedPreferences(DetectorService.PREFS_LOGCAT, MODE_PRIVATE)
                .edit()
                .putBoolean(DetectorService.KEY_LOGCAT_DENIED, false)
                .apply();
        oemLogcatDenied = false;
        serviceRunning = true;
        detectMode = false;
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, true).apply();
        launchDetectorService();
        refreshServiceStatus(true);
    }

    private void launchDetectorService() {
        Intent i = new Intent(this, DetectorService.class)
                .setAction(DetectorService.ACTION_START)
                .putExtra(DetectorService.EXTRA_DETECT_MODE, detectMode)
                .putExtra(DetectorService.EXTRA_FOREGROUND_AUTH_ATTEMPT, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopDetectorService() {
        serviceRunning = false;
        // Persist so BootReceiver does NOT auto-start after reboot
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false).apply();
        Intent i = new Intent(this, DetectorService.class)
                .setAction(DetectorService.ACTION_STOP);
        startService(i);
        refreshServiceStatus(true);
    }

    // ── Permission ───────────────────────────────────────────────────────────

    private boolean isPermissionGranted() {
        if (DetectionBackend.usesShizuku(this))
            return DetectionBackend.isShizukuGranted() && Settings.canDrawOverlays(this);
        boolean logGranted = checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
        boolean overlayGranted = (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.M)
                ? android.provider.Settings.canDrawOverlays(this)
                : true;
        return logGranted && overlayGranted;
    }

    // ── Binding labels ───────────────────────────────────────────────────────

    private void refreshBindingLabels() {
        tvSingleLabel.setText(getActionLabel(ActionExecutor.KEY_ACTION_SINGLE));
        tvLongLabel.setText(getActionLabel(ActionExecutor.KEY_ACTION_LONG));
    }

    private String getActionLabel(String prefKey) {
        int action = prefs.getInt(prefKey, ActionConfig.ACTION_NONE);
        if (action == ActionConfig.ACTION_CUSTOM_INTENT) {
            // Derive a friendly label from the stored intent string
            String intentKey = ActionExecutor.KEY_ACTION_SINGLE.equals(prefKey)
                    ? ActionExecutor.KEY_CUSTOM_INTENT_SINGLE
                    : ActionExecutor.KEY_CUSTOM_INTENT_LONG;
            String stored = prefs.getString(intentKey, "");
            if (!stored.isEmpty()) {
                String[] parts = stored.split("\\|", -1);
                String pkg = parts.length > 1 ? parts[1].trim() : "";
                String act = parts.length > 0 ? parts[0].trim() : "";
                if (!pkg.isEmpty()) {
                    // Try to resolve package name to app label
                    try {
                        PackageManager pm = getPackageManager();
                        return pm.getApplicationLabel(
                                pm.getApplicationInfo(pkg, 0)).toString();
                    } catch (PackageManager.NameNotFoundException ignored) {
                        return pkg;
                    }
                }
                if (!act.isEmpty()) return act;
            }
            return "自定义 Intent";
        }
        if (action < 0 || action >= ActionConfig.ACTION_LABELS.length)
            return ActionConfig.ACTION_LABELS[0];
        String label = ActionConfig.ACTION_LABELS[action];
        return label != null ? label : ActionConfig.ACTION_LABELS[0];
    }

    // ── Pickers ─────────────────────────────────────────────────────────────

    private void showActionPicker(String actionKey, String pkgKey,
                                  String intentKey, TextView label) {
        // Build the standard system-action choices first
        List<String>  displayLabels = new ArrayList<>();
        List<Integer> actionIndices = new ArrayList<>();
        for (int i = 0; i < ActionConfig.ACTION_LABELS.length; i++) {
            // Skip Custom Intent - handled by the new tabbed picker below
            if (ActionConfig.ACTION_LABELS[i] != null
                    && i != ActionConfig.ACTION_CUSTOM_INTENT) {
                displayLabels.add(ActionConfig.ACTION_LABELS[i]);
                actionIndices.add(i);
            }
        }
        // Add "Open App / Custom Intent…" as the last entry, opening the full picker
        displayLabels.add("打开应用/自定义 Intent…");
        actionIndices.add(ActionConfig.ACTION_CUSTOM_INTENT);

        String[] items = displayLabels.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择操作")
                .setItems(items, (dialog, which) -> {
                    int realAction = actionIndices.get(which);
                    if (realAction == ActionConfig.ACTION_CUSTOM_INTENT) {
                        // Open the new tabbed bottom-sheet picker
                        openAppIntentPicker(actionKey, intentKey, label);
                        return;
                    }
                    prefs.edit().putInt(actionKey, realAction).apply();
                    DetectorService.refreshGestureMode();
                    animateLabel(label, items[which]);
                    if (realAction == ActionConfig.ACTION_RINGER_TOGGLE) {
                        requestNotificationPolicyIfNeeded();
                    }
                })
                .show();
    }

    private void openAppIntentPicker(String actionKey, String intentKey, TextView label) {
        ActionPickerDialog picker = new ActionPickerDialog(this,
                (actionType, stored, displayLabel) -> {
                    prefs.edit()
                            .putInt(actionKey, ActionConfig.ACTION_CUSTOM_INTENT)
                            .putString(intentKey, stored)
                            .apply();
                    DetectorService.refreshGestureMode();
                    animateLabel(label, displayLabel);
                });
        // Pre-fill custom tab with whatever was saved
        String existing = prefs.getString(intentKey, "");
        picker.show();
        picker.prefillCustom(existing);
    }

    private void animateLabel(TextView label, String text) {
        label.setText(text);
        label.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                .withEndAction(() ->
                        label.animate().scaleX(1f).scaleY(1f).setDuration(120)
                                .setInterpolator(new OvershootInterpolator()).start())
                .start();
    }

    private void requestNotificationPolicyIfNeeded() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("需要权限")
                    .setMessage("更改响铃模式需要勿扰模式访问权限。点击“打开设置”并授予该权限。")
                    .setPositiveButton("打开设置", (d, w) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }
}
