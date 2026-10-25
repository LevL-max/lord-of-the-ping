package com.lordoftheping.home;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shield-style "recent apps": what was opened since the TV was switched on, whether it is still in memory,
 * and a way to close it. Needs usage access (history) and DUMP (exit records of other apps), granted over adb.
 */
final class RecentsPanel extends FrameLayout {
    interface Host {
        /** Launchable app by package, or null. */
        HomeActivity.App app(String pkg);

        void open(String pkg);

        void hidden();
    }

    static final class Recent {
        final String pkg, label;
        final Drawable icon;
        final long lastUsed;
        final boolean alive;
        /** Kept running by the system (the SHIELD's Settings, the Digma's «Входы»): force-stop does nothing. */
        final boolean persistent;

        Recent(String pkg, String label, Drawable icon, long lastUsed, boolean alive, boolean persistent) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.lastUsed = lastUsed;
            this.alive = alive;
            this.persistent = persistent;
        }

        boolean closable() {
            return alive && !persistent;
        }
    }

    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final int SHEET_DP = 480;

    private final Host host;
    private final ActivityManager am;
    private final PackageManager pm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout sheet, list;
    private final TextView memory, status, closeAll;
    private View returnFocus;
    /** Process names from `ps` over the local adb; null when unknown (then exit records are the guess). */
    private Set<String> running;

    RecentsPanel(Context c, Host host) {
        super(c);
        this.host = host;
        am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        pm = c.getPackageManager();
        setVisibility(GONE);
        setClickable(true);
        setBackgroundColor(0x99000000);

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(32), dp(30), dp(32), dp(20));
        sheet.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF141C28, 0xFF0B0F15}));
        addView(sheet, new LayoutParams(dp(SHEET_DP), LayoutParams.MATCH_PARENT, Gravity.END));
        View edge = new View(c);
        edge.setBackgroundColor(0x99E9B949);
        LayoutParams ep = new LayoutParams(dp(2), LayoutParams.MATCH_PARENT, Gravity.END);
        ep.rightMargin = dp(SHEET_DP);
        addView(edge, ep);

        TextView title = Ui.text(c, "Недавние", 28, HomeActivity.GOLD);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        sheet.addView(title);
        memory = Ui.text(c, "", 14, HomeActivity.MUTED);
        memory.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(memory);

        closeAll = Ui.pill(c, "✕   Закрыть все");
        closeAll.setOnClickListener(v -> closeAll());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(4);
        cp.bottomMargin = dp(10);
        sheet.addView(closeAll, cp);

        status = Ui.text(c, "", 13, HomeActivity.MUTED);
        status.setPadding(0, 0, 0, dp(6));
        sheet.addView(status);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(4), dp(4), dp(4), dp(4));
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        sheet.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        sheet.addView(Ui.text(c, "OK — открыть   ·   ▶ и OK — закрыть   ·   Назад — скрыть", 12, HomeActivity.DIM));
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show(View focusToReturn) {
        returnFocus = focusToReturn;
        setVisibility(VISIBLE);
        sheet.setTranslationX(dp(SHEET_DP));
        sheet.animate().translationX(0).setDuration(180).start();
        status.setText("");
        refresh();
        worker.execute(() -> {
            Set<String> names = scanRunning();
            ui.post(() -> {
                running = names;
                if (isShowing()) refresh();
            });
        });
    }

    /**
     * Running process names via `ps`. Exit records alone are not enough: Android 11 (the Digma) writes none for
     * force-stops, so closed apps kept looking alive. Never prompts for the key; null without the local adb.
     */
    private Set<String> scanRunning() {
        try {
            String out = LocalAdb.shell(getContext(), "ps -A -o NAME", null);
            return new HashSet<>(Arrays.asList(out.split("\\s+")));
        } catch (Exception e) {
            return null;
        }
    }

    void hide() {
        setVisibility(GONE);
        host.hidden();
        if (returnFocus != null && returnFocus.isAttachedToWindow()) returnFocus.requestFocus();
    }

    private void refresh() {
        String focused = findFocus() != null && findFocus().getTag() instanceof String ? (String) findFocus().getTag() : null;
        list.removeAllViews();
        List<Recent> recents = query();
        int alive = 0, closable = 0;
        if (recents == null) {
            status.setText("Нет доступа к истории приложений: разрешение выдаётся один раз через adb.");
        } else {
            for (Recent r : recents) {
                list.addView(row(r));
                if (r.alive) alive++;
                if (r.closable()) closable++;
            }
            if (recents.isEmpty()) status.setText("С момента включения телевизора ничего не открывали.");
        }
        closeAll.setVisibility(closable > 1 ? VISIBLE : GONE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        memory.setText("Свободно памяти: " + (mi.availMem >> 20) + " из " + (mi.totalMem >> 20) + " МБ"
                + (alive > 0 ? "   ·   в памяти: " + alive : ""));

        View target = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (target == null || row.getTag().equals(focused)) target = row;
            if (row.getTag().equals(focused)) break;
        }
        if (target == null && closeAll.getVisibility() == VISIBLE) target = closeAll;
        if (target != null) target.requestFocus();
    }

    private View row(Recent r) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setTag(r.pkg);
        row.setClipChildren(false); // the focused ✕ grows past the row
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setLongClickable(true);
        GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(dp(12));
        back.setColor(0x00000000);
        row.setBackground(back);

        ImageView icon = new ImageView(c);
        icon.setImageDrawable(r.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView label = Ui.text(c, r.label, 17, r.alive ? HomeActivity.TEXT : HomeActivity.MUTED);
        label.setSingleLine(true);
        texts.addView(label);
        TextView sub = Ui.text(c, (r.alive ? "● в памяти · " : "выгружено · ") + ago(r.lastUsed)
                        + (r.alive && r.persistent ? " · системное, не закрывается" : ""), 13,
                r.alive ? HomeActivity.GOLD : HomeActivity.DIM);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        if (r.closable()) {
            TextView kill = Ui.pill(c, "✕");
            kill.setOnClickListener(v -> close(r));
            // The button sits inside the focusable row, so directional focus search never reaches it on its own.
            row.setOnKeyListener((v, code, ev) -> {
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) kill.requestFocus();
                return true;
            });
            kill.setOnKeyListener((v, code, ev) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) row.requestFocus();
                return true;
            });
            row.addView(kill);
        }

        row.setOnFocusChangeListener((v, has) -> back.setColor(has ? 0x33E9B949 : 0x00000000));
        row.setOnClickListener(v -> host.open(r.pkg));
        row.setOnLongClickListener(v -> {
            if (r.closable()) close(r);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void close(Recent r) {
        long before = availMb();
        status.setText("Закрываю «" + r.label + "»…");
        forceStop(Collections.singletonList(r.pkg), () -> status.setText(stillAlive(r.pkg)
                ? "«" + r.label + "» не закрылось."
                : "«" + r.label + "» закрыто" + freed(before) + "."));
    }

    private void closeAll() {
        List<Recent> recents = query();
        if (recents == null) return;
        List<String> pkgs = new ArrayList<>();
        for (Recent r : recents) if (r.closable()) pkgs.add(r.pkg);
        if (pkgs.isEmpty()) return;
        long before = availMb();
        status.setText("Закрываю приложения: " + pkgs.size() + "…");
        forceStop(pkgs, () -> status.setText("Закрыто приложений: " + pkgs.size() + freed(before) + "."));
    }

    /**
     * This firmware ignores killBackgroundProcesses from apps ("Invalid packageName" in the log), so closing
     * goes through the TV's own adb with shell rights.
     */
    private void forceStop(List<String> pkgs, Runnable done) {
        StringBuilder cmd = new StringBuilder();
        for (String p : pkgs) cmd.append(cmd.length() == 0 ? "" : "; ").append("am force-stop ").append(p);
        worker.execute(() -> {
            try {
                LocalAdb.shell(getContext(), cmd.toString(), () -> ui.post(() -> status.setText(
                        "На экране запрос «Allow USB debugging?»: отметьте «Always allow from this computer» и нажмите OK.")));
                Set<String> names = scanRunning();
                ui.postDelayed(() -> {
                    running = names;
                    refresh();
                    done.run();
                }, 400);
            } catch (Exception e) {
                Log.w(HomeActivity.TAG, "local adb failed", e);
                ui.post(() -> status.setText("Не получилось закрыть: нужна включённая сетевая отладка ("
                        + e.getMessage() + ")."));
            }
        });
    }

    private String freed(long beforeMb) {
        long d = availMb() - beforeMb;
        return d > 0 ? ", освобождено ~" + d + " МБ" : "";
    }

    private long availMb() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    private boolean stillAlive(String pkg) {
        List<Recent> recents = query();
        if (recents == null) return false;
        for (Recent r : recents) if (r.pkg.equals(pkg)) return r.alive;
        return false;
    }

    /** Apps brought to the screen since boot, newest first; null without usage access. */
    private List<Recent> query() {
        if (!hasUsageAccess()) return null;
        UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - SystemClock.elapsedRealtime(), now);
        Map<String, Long> lastUsed = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) lastUsed.put(e.getPackageName(), e.getTimeStamp());
        }
        List<Recent> out = new ArrayList<>();
        for (Map.Entry<String, Long> en : lastUsed.entrySet()) {
            String pkg = en.getKey();
            if (pkg.equals(getContext().getPackageName())) continue;
            HomeActivity.App app = host.app(pkg);
            if (app == null && !pkg.equals(TV_SETTINGS)) continue; // system overlays, assistant, screensaver
            String label = app != null ? app.label : "Настройки";
            boolean alive = running != null ? running.contains(pkg) : isAlive(pkg, en.getValue());
            out.add(new Recent(pkg, label, icon(pkg), en.getValue(), alive, isPersistent(pkg)));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return out;
    }

    /** Alive unless its main process has an exit record newer than its last use. */
    private boolean isAlive(String pkg, long lastUsed) {
        try {
            for (ApplicationExitInfo x : am.getHistoricalProcessExitReasons(pkg, 0, 8)) {
                if (pkg.equals(x.getProcessName())) return x.getTimestamp() < lastUsed;
            }
            return true;
        } catch (SecurityException noDump) {
            return true; // without DUMP we cannot tell; offer to close anyway
        }
    }

    /** Asked of the system on each device, so a TV where Settings can be closed keeps its ✕. */
    private boolean isPersistent(String pkg) {
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getContext().getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return getContext().checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private Drawable icon(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    private static String ago(long t) {
        long min = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (min < 1) return "только что";
        if (min < 60) return min + " мин назад";
        return (min / 60) + " ч " + (min % 60) + " мин назад";
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
