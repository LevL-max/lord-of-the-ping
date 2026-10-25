package com.lordoftheping.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Small view helpers shared by the home screen and the recents panel. */
final class Ui {
    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Rounded button that gets a gold outline and grows a little when focused. */
    static TextView pill(Context c, String s) {
        TextView t = text(c, s, 16, HomeActivity.TEXT);
        t.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 22));
        g.setColor(HomeActivity.CARD);
        g.setStroke(dp(c, 2), 0x00000000);
        t.setBackground(g);
        t.setOnFocusChangeListener((v, has) -> {
            g.setStroke(dp(c, 2), has ? HomeActivity.GOLD : 0x00000000);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
        });
        return t;
    }
}
