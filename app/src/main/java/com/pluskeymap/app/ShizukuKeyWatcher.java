package com.pluskeymap.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import rikka.shizuku.Shizuku;

/** All connection state and callbacks are serialized on the application's main thread. */
final class ShizukuKeyWatcher {
    interface Listener {
        void state(boolean ready, String message);
        void key(boolean down, long eventTimeMs);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Shizuku.UserServiceArgs args;
    private ServiceConnection connection;
    private IKeyReader reader;
    private boolean closed, ready;
    private int generation, streamGeneration, failures;
    private final Runnable retry = this::connect;
    private final Runnable health = this::checkHealth;
    private final Runnable bindTimeout = () -> failed("Shizuku 连接超时，请检查权限监控或重启 Shizuku");
    private final Shizuku.OnBinderReceivedListener received = () -> handler.post(this::connect);
    private final Shizuku.OnBinderDeadListener dead = () -> handler.post(
            () -> failed("Shizuku 已停止，请启动 Shizuku；启动后会自动恢复"));

    ShizukuKeyWatcher(Context context, Listener listener) {
        this.listener = listener;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, KeyReaderService.class))
                .tag("plus-key-input")
                .processNameSuffix("plus_key_input")
                // Keep an idle shell Binder across app restarts/pauses. OxygenOS may reject
                // bootstrapping a new UserService after permission monitoring is restored.
                // The callback death recipient still closes getevent when the app dies.
                .daemon(true)
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);
    }

    void start() {
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        connect();
    }

    private void connect() {
        if (closed || ready) return;
        handler.removeCallbacks(retry);
        if (!DetectionBackend.isShizukuGranted()) {
            failed(DetectionBackend.isShizukuAvailable()
                    ? "尚未授权，请打开本应用授权 Shizuku"
                    : "Shizuku 未运行，请先启动 Shizuku");
            return;
        }
        if (reader != null && reader.asBinder().isBinderAlive()) {
            startReader(generation);
            return;
        }
        if (connection != null) return;
        final int token = ++generation;
        listener.state(false, "正在连接 Shizuku 并查找 Plus 键");
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                handler.post(() -> {
                    if (closed || token != generation) return;
                    reader = IKeyReader.Stub.asInterface(binder);
                    startReader(token);
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                handler.post(() -> {
                    if (!closed && token == generation) failed("按键服务已断开，正在重新连接");
                });
            }
        };
        handler.postDelayed(bindTimeout, 10000);
        try { Shizuku.bindUserService(args, connection); }
        catch (RuntimeException e) { failed("无法绑定 Shizuku 服务，正在重试"); }
    }

    private void startReader(int token) {
        final int stream = ++streamGeneration;
        handler.removeCallbacks(bindTimeout);
        handler.postDelayed(bindTimeout, 10000);
        try {
            reader.start(new IKeyEventListener.Stub() {
                private boolean current() { return !closed && token == generation && stream == streamGeneration; }
                @Override public void onReady(String device) {
                    handler.post(() -> {
                        if (!current()) return;
                        handler.removeCallbacks(bindTimeout);
                        ready = true;
                        failures = 0;
                        listener.state(true, "Shizuku 已连接，正在监听 Plus 键");
                        Log.i("PKM_Shizuku", "Input reader ready: " + device);
                        handler.postDelayed(health, 5000);
                    });
                }
                @Override public void onKey(boolean down, long eventTimeMs) {
                    handler.post(() -> { if (current() && ready) listener.key(down, eventTimeMs); });
                }
                @Override public void onError(String message) {
                    handler.post(() -> { if (current()) failed(message); });
                }
            });
        } catch (Exception e) { failed("无法启动底层按键监听，正在重试"); }
    }

    private void checkHealth() {
        if (closed) return;
        try {
            if (!DetectionBackend.isShizukuGranted() || reader == null || !reader.isReading()) {
                failed("按键监听已断开，正在重新连接");
                return;
            }
        } catch (Exception e) { failed("按键服务无响应，正在重新连接"); return; }
        handler.postDelayed(health, 5000);
    }

    private void failed(String message) {
        if (closed) return;
        if (reader != null && reader.asBinder().isBinderAlive() && DetectionBackend.isShizukuGranted()) {
            // A getevent failure doesn't require replacing the privileged process.
            ++streamGeneration;
            ready = false;
            handler.removeCallbacks(retry);
            handler.removeCallbacks(health);
            handler.removeCallbacks(bindTimeout);
            try { reader.stop(); } catch (Exception ignored) { }
        } else disconnect();
        listener.state(false, message);
        long delay = Math.min(30000, 2000L << Math.min(failures++, 4));
        Log.w("PKM_Shizuku", message + "; retry in " + delay + " ms");
        handler.postDelayed(retry, delay);
    }

    private void disconnect() {
        ++generation; // Ignore queued Binder callbacks from the previous reader.
        ++streamGeneration;
        ready = false;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(health);
        handler.removeCallbacks(bindTimeout);
        // A revoked Shizuku grant may reject unbindUserService; the existing narrow Binder
        // can still close our own reader, preventing an orphaned getevent process.
        if (reader != null) {
            try { reader.stop(); } catch (Exception ignored) { }
        }
        if (connection != null) {
            try { Shizuku.unbindUserService(args, connection, false); }
            catch (RuntimeException ignored) { }
        }
        connection = null;
        reader = null;
    }

    void stop() {
        closed = true;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        disconnect();
    }
}
