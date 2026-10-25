package com.lordoftheping.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A tiny gold line of the last CPU readings, 0–100 %. */
final class Sparkline extends View {
    private final float[] values = new float[30];
    private int count;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Sparkline(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(context, 1.5f));
        line.setColor(HomeActivity.GOLD);
        base.setColor(0x33E9B949);
        base.setStrokeWidth(Ui.dp(context, 1));
    }

    void push(float percent) {
        System.arraycopy(values, 1, values, 0, values.length - 1);
        values[values.length - 1] = Math.max(0, Math.min(100, percent));
        count = Math.min(count + 1, values.length);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawLine(0, h - 1, w, h - 1, base);
        if (count < 2) return;
        path.reset();
        float step = w / (values.length - 1);
        for (int i = values.length - count; i < values.length; i++) {
            float x = i * step, y = h - 1 - (h - 2) * values[i] / 100f;
            if (i == values.length - count) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
