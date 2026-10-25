package com.lordoftheping.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lord of the Ping: a deliberately small TV home screen.
 * Two rows (favourites in the user's order, then all apps), a clock, nothing in the background.
 */
public class HomeActivity extends Activity {
    static final String TAG = "LotP";
    static final int GOLD = 0xFFE9B949;
    static final int TEXT = 0xFFE8EAED;
    static final int MUTED = 0xFF8B93A1;
    static final int DIM = 0xFF5F6775;
    static final int CARD = 0xFF18202B;
    static final Locale RU = new Locale("ru");
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Two Home presses closer than this open the recents panel, as on a Shield. */
    static final long DOUBLE_HOME_MS = 600;

    /**
     * Comma-separated packages that replace the favourites, for setting up a new TV over adb:
     * {@code am start -n com.lordoftheping.home/.HomeActivity --es lotp.set_favorites pkg1,pkg2}
     */
    static final String EXTRA_SET_FAVORITES = "lotp.set_favorites";

    /**
     * TV input the TV follows, for TVs that ignore CEC One Touch Play (the Raz TV wakes up for a Shield but stays on
     * its own home): {@code am start -n com.lordoftheping.home/.HomeActivity --es lotp.follow_input <input id>}.
     * An empty id turns it off.
     */
    static final String EXTRA_FOLLOW_INPUT = "lotp.follow_input";
    /**
     * Cast device to wake when the TV is switched on with its own remote, since this TV can't wake it over CEC:
     * {@code --es lotp.wake_cast 192.168.1.103}. Empty turns it off. See {@link CastWake}.
     */
    static final String EXTRA_WAKE_CAST = "lotp.wake_cast";
    /** Sent by {@link RecentsShortcut}: open the recents panel. */
    static final String EXTRA_OPEN_RECENTS = "lotp.open_recents";
    /**
     * Shows the «⇄» inputs button: {@code --ez lotp.inputs_button true}. Off by default: TVs switch inputs from
     * their remote; the projector has no input key, so it gets the button.
     */
    static final String EXTRA_INPUTS_BUTTON = "lotp.inputs_button";
    /** After a wake-up the source needs a few seconds to show a picture: look this often, this many times. */
    static final long FOLLOW_STEP_MS = 2000;
    static final int FOLLOW_TRIES = 15;
    /** Wi-Fi comes back a few seconds after the TV wakes up: keep trying to reach the Cast device this long. */
    static final long CAST_WAKE_WINDOW_MS = 30000;
    /** A home screen created this soon after boot means the TV was just switched on. */
    static final long BOOT_WINDOW_MS = 180000;
    /** While the home screen is on view, ask the Cast source this often whether it woke up. */
    static final long SOURCE_WATCH_MS = 4000;

    /** The Google TV "Your apps" row as it was on 2026-10-25, used until the user edits favourites. */
    static final String[] DEFAULT_FAVS = {
            "com.google.android.youtube.tv", "net.filmix.filmix", "org.xbmc.kodi", "ru.kinopoisk.tv",
            "ru.rt.video.app.tv", "tv.okko.androidtv", "one.premier.rustoretv", "ru.start.androidtv",
            "ru.rutube.app.tv", "com.yandex.browser.tv",
    };

    static final class App {
        final String pkg, label;
        final Drawable art;
        final boolean banner;

        App(String pkg, String label, Drawable art, boolean banner) {
            this.pkg = pkg;
            this.label = label;
            this.art = art;
            this.banner = banner;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final List<String> favs = new ArrayList<>();
    private Map<String, App> apps = new HashMap<>();
    private SharedPreferences prefs;
    private LinearLayout content, favRow, allRow;
    private HorizontalScrollView favScroll, allScroll;
    private TextView clock, date, greeting, favHint, inputsButton;
    private EmblemView emblem;
    private RecentsPanel recents;
    private SystemStats stats;
    private Sparkline spark;
    private TextView statsText;
    private final Runnable statsTick = this::showStats;
    private boolean resumed, openRecentsWhenLoaded;
    private long lastHomeAt;
    /** Only the instance the system opened as home follows inputs; a settings-only start just saves and closes. */
    private boolean homeInstance, settingsOnly;
    private boolean followConnected;
    private int followTries;
    private long followedAt;
    /** Cast source state while the home screen is on view: null until the first answer; misses in a row. */
    private Boolean sourceAwake;
    private int sourceMisses;
    private boolean watching;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            View f = getCurrentFocus();
            reload(f != null && f.getTag() instanceof String ? (String) f.getTag() : null, isInRow(f, favRow));
        }
    };

    /** HDMI inputs register after boot, often later than the home screen starts. */
    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String id) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String id) {
            updateInputsButton();
        }

        @Override
        public void onInputUpdated(String id) {
            updateInputsButton();
        }

        /** The followed source got a picture while the TV was on: One Touch Play, done here. */
        @Override
        public void onInputStateChanged(String id, int state) {
            if (!isFollowed(id)) return;
            boolean was = followConnected;
            followConnected = isFollowConnected();
            if (followConnected && !was) openFollowed();
        }
    };

    /**
     * The TV woke up (the followed source asked it to over CEC, or its own remote did), or someone dismissed its
     * screensaver: either way the TV is wanted, and it is a display for the source.
     */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onWake();
        }
    };

    /**
     * While the home screen is on view, asks the Cast source whether it sleeps. When it wakes up (from its own
     * remote, or after a reboot) the TV switches to it, as CEC One Touch Play should have. The first answer only
     * sets the baseline, so pressing Home while the source is on does not bounce back to it.
     */
    private final Runnable sourceWatch = new Runnable() {
        @Override
        public void run() {
            String host = wakeCast();
            if (!watching || host.isEmpty()) return;
            net.execute(() -> {
                Boolean awake;
                try {
                    awake = !CastWake.isStandBy(host);
                } catch (IOException | GeneralSecurityException e) {
                    awake = null;
                }
                Boolean answer = awake;
                ui.post(() -> onSourceState(answer));
            });
        }
    };

    private final Runnable followCheck = new Runnable() {
        @Override
        public void run() {
            if (!isInteractive()) return;
            if (isFollowConnected()) openFollowed();
            else if (++followTries < FOLLOW_TRIES) ui.postDelayed(this, FOLLOW_STEP_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("lotp", MODE_PRIVATE);
        Intent start = getIntent();
        homeInstance = start != null && start.hasCategory(Intent.CATEGORY_HOME);
        boolean configured = importFavorites(start) | importFollow(start) | importWake(start) | importInputsButton(start);
        if (configured && !homeInstance) {
            // "am start -n ... --es lotp.*" from adb: the running home instance picks the settings up itself.
            settingsOnly = true;
            finish();
            return;
        }
        loadFavs();
        openRecentsWhenLoaded = start != null && start.getBooleanExtra(EXTRA_OPEN_RECENTS, false);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        reload(null, true);

        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputWatch, ui);
        followConnected = isFollowConnected();
        IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(screenOn, wake);
        updateInputsButton();
        if (homeInstance && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) onWake();
    }

    @Override
    protected void onDestroy() {
        if (settingsOnly) {
            super.onDestroy();
            return;
        }
        unregisterReceiver(packages);
        unregisterReceiver(screenOn);
        ui.removeCallbacks(followCheck);
        stopSourceWatch();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputWatch);
        bg.shutdown();
        net.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!TextUtils.join(",", favs).equals(prefs.getString("favorites", TextUtils.join(",", favs)))) {
            loadFavs(); // changed by a settings-only start from adb
            reload(null, true);
        }
        updateInputsButton();
        updateClock();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        emblem.ping();
        stats.start();
        ui.post(statsTick);
        startSourceWatch();
    }

    @Override
    protected void onPause() {
        resumed = false;
        unregisterReceiver(tick);
        ui.removeCallbacks(statsTick);
        stats.stop();
        stopSourceWatch();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean favsSet = importFavorites(intent);
        boolean followSet = importFollow(intent) | importWake(intent) | importInputsButton(intent);
        if (favsSet || followSet) {
            if (favsSet) reload(null, true);
            updateInputsButton();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_RECENTS, false)) {
            if (!recents.isShowing()) showRecents();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = now - lastHomeAt < DOUBLE_HOME_MS;
        lastHomeAt = now;
        if (twice) {
            if (!recents.isShowing()) showRecents();
            return;
        }
        if (recents.isShowing()) recents.hide();
        if (!resumed) return; // coming back from an app: keep the focus where it was
        // Home pressed while already home: back to the first favourite.
        favScroll.smoothScrollTo(0, 0);
        allScroll.scrollTo(0, 0);
        View first = find(favRow, null);
        if (first == null) first = find(allRow, null);
        if (first != null) first.requestFocus();
        emblem.ping();
    }

    @Override
    public void onBackPressed() {
        // The home screen has nowhere to go back to; Back only closes the recents panel.
        if (recents.isShowing()) recents.hide();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // The remote's gear key sends NOTIFICATION: the system wants the Google TV panel, which is gone,
        // so on the home screen it opens Settings (KeyService does the same in other apps).
        if (code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                    && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                openSettings();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new PingBackdrop(this), new FrameLayout.LayoutParams(MATCH, MATCH));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(48), dp(24), dp(48), dp(12));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false); // focused pills grow past their bounds
        emblem = new EmblemView(this);
        emblem.setOnClickListener(v -> {
            emblem.ping();
            showAbout();
        });
        head.addView(emblem, new LinearLayout.LayoutParams(dp(68), dp(68)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Lord of the Ping", 30, GOLD);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        title.setLetterSpacing(0.03f);
        brand.addView(title);
        TextView sub = text("наш домашний экран · сделан вместе с Claude", 13, MUTED);
        sub.setPadding(dp(2), dp(4), 0, 0);
        brand.addView(sub);
        head.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clock = text("", 42, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text("", 15, MUTED);
        greeting = text("", 15, GOLD);
        right.addView(clock);
        right.addView(date);
        right.addView(greeting);
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        spark = new Sparkline(this);
        statsRow.addView(spark, new LinearLayout.LayoutParams(dp(56), dp(14)));
        statsText = text("", 12, MUTED);
        statsText.setPadding(dp(8), 0, 0, 0);
        statsRow.addView(statsText);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(WRAP, WRAP);
        srp.topMargin = dp(4);
        right.addView(statsRow, srp);
        head.addView(right);
        stats = new SystemStats(this);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setClipChildren(false);
        TextView recentsButton = pill("☰  Недавние");
        recentsButton.setOnClickListener(v -> showRecents());
        TextView settings = pill("⚙  Настройки");
        settings.setOnClickListener(v -> openSettings());
        buttons.addView(recentsButton, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(MATCH, WRAP);
        gap.topMargin = dp(8);
        buttons.addView(settings, gap);
        // The Android TV home had an "Inputs" button; on the projector it is the only way to HDMI.
        inputsButton = pill("⇄  Входы");
        inputsButton.setOnClickListener(v -> showInputs());
        inputsButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(MATCH, WRAP);
        gap2.topMargin = dp(8);
        buttons.addView(inputsButton, gap2);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, WRAP);
        bp.leftMargin = dp(24);
        head.addView(buttons, bp);
        content.addView(head);

        content.addView(sectionTitle("Избранное"), topMargin(dp(16)));
        favHint = text("Пока пусто. Долгое нажатие OK на приложении ниже — «Добавить в избранное».", 15, MUTED);
        favHint.setPadding(0, dp(12), 0, dp(12));
        favHint.setVisibility(View.GONE);
        content.addView(favHint);
        favScroll = rowScroll();
        favRow = row();
        favScroll.addView(favRow);
        content.addView(favScroll);

        content.addView(sectionTitle("Все приложения"), topMargin(dp(8)));
        allScroll = rowScroll();
        allRow = row();
        allScroll.addView(allRow);
        content.addView(allScroll);

        content.addView(text("Долгое нажатие OK — переставить, убрать или добавить   ·   дважды Home — недавние", 12, DIM),
                topMargin(dp(4)));

        recents = new RecentsPanel(this, new RecentsPanel.Host() {
            @Override
            public App app(String pkg) {
                return apps.get(pkg);
            }

            @Override
            public void open(String pkg) {
                recents.hide();
                if ("com.android.tv.settings".equals(pkg)) openSettings();
                else openApp(pkg);
            }

            @Override
            public void hidden() {
                content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        });
        root.addView(recents, new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    private void showRecents() {
        View back = getCurrentFocus();
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        recents.show(back);
        emblem.ping();
    }

    private View tile(App a, boolean inFavs, boolean starred) {
        int w = inFavs ? dp(192) : dp(150);
        int h = inFavs ? dp(108) : dp(84);

        FrameLayout card = new FrameLayout(this);
        card.setTag(a.pkg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        GradientDrawable back = new GradientDrawable();
        back.setColor(CARD);
        back.setCornerRadius(dp(10));
        card.setBackground(back);
        card.setClipToOutline(true);

        ImageView img = new ImageView(this);
        Drawable.ConstantState cs = a.art.getConstantState();
        img.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : a.art);
        if (a.banner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(h * 0.56f);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        if (starred) {
            TextView star = text("★", 15, GOLD);
            star.setShadowLayer(dp(3), 0, 0, 0xCC000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(4), dp(7), 0);
            card.addView(star, lp);
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(3), GOLD);

        TextView label = text(a.label, inFavs ? 15 : 13, MUTED);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(4), dp(8), dp(4), 0);

        card.setOnFocusChangeListener((v, has) -> {
            card.setForeground(has ? ring : null);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .translationZ(has ? dp(10) : 0).setDuration(140).start();
            label.setTextColor(has ? TEXT : MUTED);
        });
        card.setOnClickListener(v -> openApp(a.pkg));
        card.setOnLongClickListener(v -> {
            showMenu(a, inFavs);
            return true;
        });
        card.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(a, inFavs);
                return true;
            }
            return false;
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.addView(card, new LinearLayout.LayoutParams(w, h));
        box.addView(label, new LinearLayout.LayoutParams(w, WRAP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(inFavs ? 22 : 18);
        box.setLayoutParams(lp);
        return box;
    }

    private void rebuild(String focusPkg, boolean inFavs) {
        favRow.removeAllViews();
        allRow.removeAllViews();
        int shown = 0;
        for (String p : favs) {
            App a = apps.get(p);
            if (a == null) continue; // not installed or disabled right now; keep its place
            favRow.addView(tile(a, true, false));
            shown++;
        }
        favHint.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        favScroll.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);

        List<App> all = new ArrayList<>(apps.values());
        Collator coll = Collator.getInstance(RU);
        Collections.sort(all, (x, y) -> coll.compare(x.label, y.label));
        for (App a : all) allRow.addView(tile(a, false, favs.contains(a.pkg)));

        if (recents.isShowing()) return; // the panel keeps the focus
        View target = focusPkg == null ? null : find(inFavs ? favRow : allRow, focusPkg);
        if (target == null) target = find(favRow, null);
        if (target == null) target = find(allRow, null);
        if (target != null) target.requestFocus();
    }

    private void showMenu(App a, boolean inFavs) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        List<String> visible = visibleFavs();
        int i = visible.indexOf(a.pkg);
        if (inFavs) {
            if (i > 0) {
                items.add("◀   Сдвинуть влево");
                acts.add(() -> move(a.pkg, -1));
            }
            if (i >= 0 && i < visible.size() - 1) {
                items.add("▶   Сдвинуть вправо");
                acts.add(() -> move(a.pkg, 1));
            }
            items.add("✕   Убрать из избранного");
            acts.add(() -> {
                String next = i + 1 < visible.size() ? visible.get(i + 1) : i > 0 ? visible.get(i - 1) : null;
                favs.remove(a.pkg);
                saveFavs();
                rebuild(next, true);
            });
        } else if (!favs.contains(a.pkg)) {
            items.add("★   Добавить в избранное");
            acts.add(() -> {
                favs.add(a.pkg);
                saveFavs();
                rebuild(a.pkg, false);
                toast("«" + a.label + "» теперь в избранном");
            });
        } else {
            items.add("☆   Убрать из избранного");
            acts.add(() -> {
                favs.remove(a.pkg);
                saveFavs();
                rebuild(a.pkg, false);
            });
        }
        items.add("ⓘ   О приложении");
        acts.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg))));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** Swaps the app with its visible neighbour; hidden (uninstalled) favourites keep their slots. */
    private void move(String pkg, int dir) {
        List<String> visible = visibleFavs();
        int i = visible.indexOf(pkg), j = i + dir;
        if (i < 0 || j < 0 || j >= visible.size()) return;
        int a = favs.indexOf(pkg), b = favs.indexOf(visible.get(j));
        Collections.swap(favs, a, b);
        saveFavs();
        rebuild(pkg, true);
    }

    private void showAbout() {
        long mb = Math.round(Debug.getPss() / 1024.0);
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Lord of the Ping")
                .setMessage("Один лаунчер, чтобы править всеми.\n\n"
                        + "Наш собственный домашний экран для этого телевизора. Сделан вместе с Claude, октябрь 2026.\n\n"
                        + "Версия " + ver + " · сейчас занимает " + mb + " МБ памяти.")
                .setPositiveButton("Ping!", (d, w) -> emblem.ping())
                .show();
    }

    private void showStats() {
        SystemStats.Sample s = stats.sample();
        if (s.cpuPercent >= 0) spark.push(s.cpuPercent);
        String cpu = s.cpuPercent < 0 ? "ЦП —" : "ЦП " + s.cpuPercent + "%";
        statsText.setText(String.format(RU, "%s · ОЗУ %.1f/%.1f ГБ · ↓%s ↑%s Мбит/с", cpu,
                s.usedMb / 1024.0, s.totalMb / 1024.0, mbit(s.rxMbit), mbit(s.txMbit)));
        statsText.setTextColor(s.cpuPercent >= 85 ? GOLD : MUTED);
        ui.postDelayed(statsTick, 2000);
    }

    private static String mbit(double v) {
        return v < 0.05 ? "0" : String.format(RU, v < 10 ? "%.1f" : "%.0f", v);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", RU).format(now));
        String d = new SimpleDateFormat("EEEE, d MMMM", RU).format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Доброй ночи" : h < 12 ? "Доброе утро" : h < 18 ? "Добрый день"
                : h < 23 ? "Добрый вечер" : "Доброй ночи");
    }

    // ---------------------------------------------------------------- apps

    private void reload(String focusPkg, boolean inFavs) {
        bg.execute(() -> {
            Map<String, App> found = scan();
            ui.post(() -> {
                apps = found;
                rebuild(focusPkg, inFavs);
                if (openRecentsWhenLoaded) {
                    openRecentsWhenLoaded = false;
                    showRecents();
                }
            });
        });
    }

    private Map<String, App> scan() {
        PackageManager pm = getPackageManager();
        Map<String, App> m = new HashMap<>();
        collect(pm, m, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, m, Intent.CATEGORY_LAUNCHER, false);
        m.remove(getPackageName());
        return m;
    }

    private void collect(PackageManager pm, Map<String, App> m, String category, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (m.containsKey(ai.packageName)) continue;
            Drawable art = leanback ? ai.loadBanner(pm) : null;
            boolean banner = art != null;
            if (art == null) art = ri.loadIcon(pm);
            m.put(ai.packageName, new App(ai.packageName, String.valueOf(ri.loadLabel(pm)), shrink(art), banner));
        }
    }

    /** Banners come at up to 2x card size; keep them at card size to save memory. */
    private Drawable shrink(Drawable d) {
        if (!(d instanceof BitmapDrawable)) return d;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        int maxW = dp(192);
        if (b == null || b.getWidth() <= maxW) return d;
        int h = Math.round(b.getHeight() * (maxW / (float) b.getWidth()));
        return new BitmapDrawable(getResources(), Bitmap.createScaledBitmap(b, maxW, h, true));
    }

    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("Не получается открыть приложение");
            return;
        }
        launch(i);
    }

    private void openSettings() {
        launch(new Intent(Settings.ACTION_SETTINGS));
    }

    // ---------------------------------------------------------------- inputs (HDMI)

    private List<TvInputInfo> inputs() {
        List<TvInputInfo> list = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return list;
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.isPassthroughInput() && !i.isHidden(this)) list.add(i);
            }
        } catch (RuntimeException ignored) {
        }
        return list;
    }

    private String inputLabel(TvInputInfo i) {
        CharSequence l = i.loadCustomLabel(this);
        if (TextUtils.isEmpty(l)) l = i.loadLabel(this);
        return TextUtils.isEmpty(l) ? "Вход" : l.toString();
    }

    /**
     * A followed input or a single input: the button names it and opens it directly. Several: "Входы" and a list.
     * None: hidden.
     */
    private void updateInputsButton() {
        List<TvInputInfo> list = inputs();
        boolean on = prefs.getBoolean("inputs_button", false);
        inputsButton.setVisibility(!on || list.isEmpty() ? View.GONE : View.VISIBLE);
        String followed = followLabel();
        if (followed != null) inputsButton.setText("⇄  " + followed);
        else inputsButton.setText(list.size() == 1 ? "⇄  " + inputLabel(list.get(0)) : "⇄  Входы");
    }

    private void showInputs() {
        if (followLabel() != null) {
            openFollowed();
            return;
        }
        List<TvInputInfo> list = inputs();
        if (list.isEmpty()) {
            toast("Входов нет");
            return;
        }
        if (list.size() == 1) {
            openInput(list.get(0));
            return;
        }
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        String[] items = new String[list.size()];
        for (int k = 0; k < items.length; k++) {
            int state = tim.getInputState(list.get(k).getId());
            items[k] = inputLabel(list.get(k)) + (state == TvInputManager.INPUT_STATE_CONNECTED ? "  ·  подключено"
                    : state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "  ·  в ожидании" : "  ·  не подключено");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Входы")
                .setItems(items, (d, which) -> openInput(list.get(which)))
                .show();
    }

    /** The intent the Android TV home sends for an input; the TV's input player shows the picture. */
    private void openInput(TvInputInfo i) {
        openInput(i.getId());
    }

    private void openInput(String id) {
        launch(new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(id), TvContract.Channels.CONTENT_ITEM_TYPE));
    }

    // ---------------------------------------------------------------- followed input (CEC One Touch Play)

    private String followInput() {
        return prefs.getString("follow_input", "");
    }

    private String wakeCast() {
        return prefs.getString("wake_cast", "");
    }

    /** The TV just woke up or booted: wake the followed source over Cast if it sleeps, then switch to it. */
    private void onWake() {
        // A dream also stops when the TV goes to sleep; waking the source then would wake the TV straight back.
        if (!homeInstance || followInput().isEmpty() || !isInteractive()) return;
        followTries = 0;
        ui.removeCallbacks(followCheck);
        ui.postDelayed(followCheck, FOLLOW_STEP_MS);
        String host = wakeCast();
        if (!host.isEmpty()) new Thread(() -> wakeSource(host), "cast-wake").start();
    }

    /** Wi-Fi needs a few seconds after the TV wakes up, so the first attempts may fail to connect. */
    private void wakeSource(String host) {
        long end = SystemClock.uptimeMillis() + CAST_WAKE_WINDOW_MS;
        while (SystemClock.uptimeMillis() < end && isInteractive()) {
            try {
                CastWake.wakeIfAsleep(host);
                return;
            } catch (IOException | GeneralSecurityException e) {
                SystemClock.sleep(2000);
            }
        }
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void startSourceWatch() {
        if (!homeInstance || wakeCast().isEmpty() || followInput().isEmpty()) return;
        watching = true;
        sourceAwake = null;
        sourceMisses = 0;
        ui.removeCallbacks(sourceWatch);
        ui.post(sourceWatch);
    }

    private void stopSourceWatch() {
        watching = false;
        ui.removeCallbacks(sourceWatch);
    }

    /**
     * {@code awake} is null when the source didn't answer. Switch when it goes from sleeping, or from silent for
     * a while (rebooting), to awake; a single miss is a network hiccup, not a reboot.
     */
    private void onSourceState(Boolean awake) {
        if (!watching) return;
        if (awake == null) {
            sourceMisses++;
        } else {
            if (awake && (Boolean.FALSE.equals(sourceAwake) || sourceMisses >= 2) && isInteractive()) openFollowed();
            sourceAwake = awake;
            sourceMisses = 0;
        }
        if (watching) ui.postDelayed(sourceWatch, SOURCE_WATCH_MS);
    }

    /** The followed input itself, or the CEC device the TV lists under it (a Shield shows up as one). */
    private boolean isFollowed(String id) {
        String follow = followInput();
        if (follow.isEmpty() || id == null) return false;
        if (follow.equals(id)) return true;
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        TvInputInfo i = tim == null ? null : tim.getTvInputInfo(id);
        return i != null && follow.equals(i.getParentId());
    }

    /** Whether the followed input, or the CEC device on it, reports a picture right now. */
    private boolean isFollowConnected() {
        String follow = followInput();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null || follow.isEmpty()) return false;
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if ((follow.equals(i.getId()) || follow.equals(i.getParentId()))
                        && tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** The CEC device's name ("SHIELD") when the TV knows it, else the input's own; null when nothing is followed. */
    private String followLabel() {
        String follow = followInput();
        if (follow.isEmpty()) return null;
        String own = null;
        for (TvInputInfo i : inputs()) {
            if (follow.equals(i.getParentId())) return inputLabel(i);
            if (follow.equals(i.getId())) own = inputLabel(i);
        }
        return own;
    }

    /** The home app may start activities from the background, so this works over any app, as CEC would. */
    private void openFollowed() {
        long now = SystemClock.uptimeMillis();
        String follow = followInput();
        if (follow.isEmpty() || now - followedAt < 5000) return;
        followedAt = now;
        ui.removeCallbacks(followCheck);
        openInput(follow);
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Не получается открыть");
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> visibleFavs() {
        List<String> v = new ArrayList<>();
        for (String p : favs) if (apps.containsKey(p)) v.add(p);
        return v;
    }

    private void saveFavs() {
        prefs.edit().putString("favorites", TextUtils.join(",", favs)).apply();
    }

    private void loadFavs() {
        favs.clear();
        String saved = prefs.getString("favorites", null);
        if (saved == null) favs.addAll(Arrays.asList(DEFAULT_FAVS));
        else if (!saved.isEmpty()) favs.addAll(Arrays.asList(saved.split(",")));
    }

    /** Applies {@link #EXTRA_SET_FAVORITES} if the intent carries it; returns whether it did. */
    private boolean importFavorites(Intent intent) {
        String list = intent == null ? null : intent.getStringExtra(EXTRA_SET_FAVORITES);
        if (list == null) return false;
        favs.clear();
        for (String p : list.split(",")) if (!p.trim().isEmpty()) favs.add(p.trim());
        saveFavs();
        return true;
    }

    /** Applies {@link #EXTRA_FOLLOW_INPUT} if the intent carries it; returns whether it did. */
    private boolean importFollow(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra(EXTRA_FOLLOW_INPUT);
        if (id == null) return false;
        prefs.edit().putString("follow_input", id.trim()).apply();
        followConnected = isFollowConnected();
        return true;
    }

    /** Applies {@link #EXTRA_INPUTS_BUTTON} if the intent carries it; returns whether it did. */
    private boolean importInputsButton(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_INPUTS_BUTTON)) return false;
        prefs.edit().putBoolean("inputs_button", intent.getBooleanExtra(EXTRA_INPUTS_BUTTON, false)).apply();
        return true;
    }

    /** Applies {@link #EXTRA_WAKE_CAST} if the intent carries it; returns whether it did. */
    private boolean importWake(Intent intent) {
        String host = intent == null ? null : intent.getStringExtra(EXTRA_WAKE_CAST);
        if (host == null) return false;
        prefs.edit().putString("wake_cast", host.trim()).apply();
        return true;
    }

    private View find(LinearLayout row, String pkg) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View card = ((ViewGroup) row.getChildAt(i)).getChildAt(0);
            if (pkg == null || pkg.equals(card.getTag())) return card;
        }
        return null;
    }

    private static boolean isInRow(View v, LinearLayout row) {
        for (ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) {
            if (p == row) return true;
        }
        return false;
    }

    private HorizontalScrollView rowScroll() {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(0, dp(10), 0, dp(4));
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(dp(6), dp(6), dp(48), dp(6));
        return r;
    }

    private TextView text(String s, float sp, int color) {
        return Ui.text(this, s, sp, color);
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(RU), 13, MUTED);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView pill(String s) {
        return Ui.pill(this, s);
    }

    private LinearLayout.LayoutParams topMargin(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = m;
        return lp;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
