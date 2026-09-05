package com.pluskeymap.app;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shizuku UserService. No arbitrary commands/paths are accepted over Binder. */
public class KeyReaderService extends IKeyReader.Stub {
    private volatile Session session;

    public KeyReaderService() {}

    @Override public synchronized void start(IKeyEventListener listener) throws RemoteException {
        stop();
        if (listener == null) return;
        Session next = new Session(listener);
        session = next;
        listener.asBinder().linkToDeath(next, 0);
        new Thread(next, "pkm-input-reader").start();
    }

    @Override public synchronized void stop() {
        Session old = session;
        session = null;
        if (old != null) old.close();
    }

    @Override public boolean isReading() {
        Session current = session;
        return current != null && current.ready && current.alive();
    }

    @Override public void destroy() { stop(); System.exit(0); }

    private static final class Session implements Runnable, IBinder.DeathRecipient {
        final IKeyEventListener listener;
        volatile Process process;
        volatile boolean closed, ready;
        Session(IKeyEventListener listener) { this.listener = listener; }

        synchronized Process launch(String... args) throws Exception {
            if (closed) throw new InterruptedException();
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            return process;
        }

        boolean alive() { Process p = process; return !closed && p != null && p.isAlive(); }

        @Override public void run() {
            try {
                Process scan = launch("/system/bin/getevent", "-lp");
                StringBuilder caps = new StringBuilder();
                // Drain concurrently so a large capability list cannot fill the pipe.
                Thread drain = new Thread(() -> {
                    try (BufferedReader reader = reader(scan)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (caps.length() < 262144) caps.append(line).append('\n');
                        }
                    } catch (Exception ignored) { }
                }, "pkm-input-scan");
                drain.start();
                if (!scan.waitFor(5, TimeUnit.SECONDS)) {
                    scan.destroyForcibly();
                    throw new IllegalStateException("输入设备扫描超时");
                }
                drain.join(1000);
                if (drain.isAlive() || scan.exitValue() != 0)
                    throw new IllegalStateException("无法读取输入设备，请检查 Shizuku 权限");
                List<String> paths = InputEventParser.findDevices(caps.toString());
                if (paths.size() != 1)
                    throw new IllegalStateException(paths.isEmpty()
                            ? "未找到 Plus 键输入设备" : "找到多个 Plus 键候选设备，暂不监听");
                Process input = launch("/system/bin/getevent", "-lt", paths.get(0));
                if (input.waitFor(250, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("无法打开 Plus 键设备，请检查 shell 输入权限");
                if (closed) return;
                ready = true;
                listener.onReady(paths.get(0));
                try (BufferedReader reader = reader(input)) {
                    String line;
                    while (!closed && (line = reader.readLine()) != null) {
                        InputEventParser.Event event = InputEventParser.parse(line);
                        if (event != null) listener.onKey(event.down, event.timeMs);
                        else if (line.contains("SYN_DROPPED"))
                            throw new IllegalStateException("输入事件丢失，重新连接以重置按键状态");
                    }
                }
                if (!closed) listener.onError("底层按键监听已退出，正在重新连接");
            } catch (Exception e) {
                if (!closed) {
                    try { listener.onError(e.getMessage() == null ? "底层按键监听失败" : e.getMessage()); }
                    catch (RemoteException ignored) { }
                }
            } finally { close(); }
        }

        private static BufferedReader reader(Process p) {
            return new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }

        @Override public void binderDied() { close(); }

        synchronized void close() {
            if (closed) return;
            closed = true;
            ready = false;
            if (process != null) process.destroyForcibly();
            try { listener.asBinder().unlinkToDeath(this, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
    }
}
