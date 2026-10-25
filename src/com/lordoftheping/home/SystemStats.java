package com.lordoftheping.home;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * CPU, memory and network for the home screen, only while it is visible.
 * Memory and traffic come from public APIs. CPU needs /proc/stat, which apps cannot read, so one shell loop
 * over the local adb prints its first line every 2 s.
 */
final class SystemStats {
    static final class Sample {
        int cpuPercent = -1; // -1: unknown (no local adb)
        long usedMb, totalMb;
        double rxMbit, txMbit;
    }

    /** Turns the streamed /proc/stat lines into a CPU percentage; one per stream, used on its thread only. */
    private final class CpuParser {
        private final StringBuilder pending = new StringBuilder();
        private long prevTotal = -1, prevIdle;

        void accept(byte[] data) {
            pending.append(new String(data, StandardCharsets.US_ASCII));
            for (int nl = pending.indexOf("\n"); nl >= 0; nl = pending.indexOf("\n")) {
                String line = pending.substring(0, nl).trim();
                pending.delete(0, nl + 1);
                if (line.startsWith("cpu ")) onCpuLine(line);
            }
        }

        /** "cpu  user nice system idle iowait irq softirq steal ..." in jiffies since boot. */
        private void onCpuLine(String line) {
            String[] f = line.split("\\s+");
            if (f.length < 6) return;
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
            if (prevTotal >= 0 && total > prevTotal) {
                cpu = (int) Math.round(100.0 * (1 - (double) (idle - prevIdle) / (total - prevTotal)));
            }
            prevTotal = total;
            prevIdle = idle;
        }
    }

    private final Context context;
    private final ActivityManager am;
    private volatile int cpu = -1;
    private volatile Socket socket;
    private long lastRx = -1, lastTx, lastAt;

    SystemStats(Context context) {
        this.context = context;
        am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    void start() {
        stop();
        Socket s = new Socket();
        socket = s;
        CpuParser parser = new CpuParser();
        Thread t = new Thread(() -> {
            try {
                LocalAdb.stream(context, s, "while :; do head -n 1 /proc/stat; sleep 2; done", parser::accept);
            } catch (IOException | GeneralSecurityException e) {
                // Closed by stop(), or no local adb: CPU stays unknown.
            }
            if (socket == s) cpu = -1;
        }, "lotp-stats");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Called on the UI thread every couple of seconds. */
    Sample sample() {
        Sample out = new Sample();
        out.cpuPercent = cpu;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        out.totalMb = mi.totalMem >> 20;
        out.usedMb = (mi.totalMem - mi.availMem) >> 20;

        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (lastRx >= 0 && rx >= lastRx && now > lastAt) {
            double seconds = (now - lastAt) / 1000.0;
            out.rxMbit = (rx - lastRx) * 8 / seconds / 1e6;
            out.txMbit = (tx - lastTx) * 8 / seconds / 1e6;
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
        return out;
    }
}
