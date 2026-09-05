package com.pluskeymap.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** A static, non-interactive window owned by the active Shizuku listener. Main thread only. */
final class InputKeepaliveWindow {
    private final Context context;
    private final WindowManager windowManager;
    private View window;

    InputKeepaliveWindow(Context context) {
        this.context = context;
        windowManager = context.getSystemService(WindowManager.class);
    }

    boolean show() {
        if (window != null) return true;
        if (windowManager == null || !Settings.canDrawOverlays(context)) return false;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("Plus key input keepalive");
        // OxygenOS Hans freezes even a foreground service with unrestricted battery
        // access. Its floatWindow exemption keeps Binder input callbacks deliverable.
        // The surface remains visible to WindowManager, but draws just one faint pixel.
        params.alpha = 0.8f;
        View candidate = new View(context);
        candidate.setBackgroundColor(0x01000000);
        try {
            windowManager.addView(candidate, params);
            window = candidate;
            Log.i("PKM_Shizuku", "Input keepalive window attached");
            return true;
        } catch (RuntimeException e) {
            Log.w("PKM_Shizuku", "Unable to attach input keepalive window", e);
            return false;
        }
    }

    void hide() {
        if (window == null) return;
        try { windowManager.removeViewImmediate(window); }
        catch (RuntimeException e) { Log.w("PKM_Shizuku", "Input keepalive window removal failed", e); }
        window = null;
        Log.i("PKM_Shizuku", "Input keepalive window removed");
    }
}
