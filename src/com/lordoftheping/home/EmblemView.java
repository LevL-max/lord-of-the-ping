package com.lordoftheping.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** The ring that pings: a gold ring with two signal arcs; {@link #ping()} sends one wave out of it. */
public class EmblemView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float wavePos = -1f;
    private ValueAnimator anim;

    public EmblemView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(px(3.5f));
        ring.setColor(HomeActivity.GOLD);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(px(3f));
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(HomeActivity.GOLD);
        dot.setColor(HomeActivity.GOLD);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(px(2f));
        wave.setColor(HomeActivity.GOLD);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(px(2f));
        halo.setColor(0x66E9B949);
        setFocusable(true);
        setClickable(true);
    }

    private float px(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    public void ping() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1200);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            wavePos = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                wavePos = -1f;
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float ringR = r * 0.42f, arcR = r * 0.68f;
        if (isFocused()) c.drawCircle(cx, cy, r * 0.94f, halo);
        if (wavePos >= 0f) {
            wave.setAlpha((int) (230 * (1f - wavePos)));
            c.drawCircle(cx, cy, ringR + (r * 0.96f - ringR) * wavePos, wave);
        }
        c.drawCircle(cx, cy, ringR, ring);
        c.drawCircle(cx, cy, r * 0.11f, dot);
        box.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        c.drawArc(box, -40f, 80f, false, arc);
        c.drawArc(box, 140f, 80f, false, arc);
    }
}
