package com.lordoftheping.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

/** Night gradient with faint gold ping rings spreading from the lower right corner. Static: drawn once per layout. */
public class PingBackdrop extends View {
    private final GradientDrawable night = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF152030, 0xFF0B0F15, 0xFF06080B});
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PingBackdrop(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f,
                getResources().getDisplayMetrics()));
        ring.setColor(HomeActivity.GOLD);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        night.setBounds(0, 0, w, h);
        night.draw(c);
        float cx = w * 0.97f, cy = h * 1.04f;
        for (int i = 1; i <= 5; i++) {
            ring.setAlpha(26 - i * 4);
            c.drawCircle(cx, cy, w * 0.13f * i, ring);
        }
    }
}
