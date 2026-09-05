package com.pluskeymap.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

public class LauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Install splash screen first — must be before super.onCreate / setContentView.
        // applySavedTheme was already called in PlusKeyApp.onCreate, so the correct
        // night mode is active before the splash background color is resolved.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // Restore the dynamic Recents flag whenever the launcher task is rebuilt.
        RecentsVisibility.applySavedSetting(this);

        boolean setupDone = SetupActivity.isSetupDone(this);
        boolean skipped   = getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                .getBoolean(SetupActivity.KEY_SKIPPED, false);

        if (setupDone || skipped || DetectionBackend.usesShizuku(this)) {
            Intent main = new Intent(this, MainActivity.class);
            boolean wasRunning = getSharedPreferences(
                    SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                    .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
            // Opening the launcher is an explicit foreground user action. If an
            // expected detector session is gone, recreate it after MainActivity
            // gains focus so Android can show the full-log consent dialog.
            if (wasRunning && !DetectorService.isRunning()) {
                main.putExtra("reauth_logperm", true);
            }
            startActivity(main);
        } else {
            startActivity(new Intent(this, SetupActivity.class));
        }
        finish();
    }
}
