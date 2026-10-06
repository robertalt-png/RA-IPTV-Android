package com.nenotv.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Loading sun: yellow rays turn slowly around the static SunnyIPTV mark, which breathes gently. No library. */
public final class SunLoader extends View {
    private static final int RAYS = 12;
    private final Paint ray = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mark;
    private ValueAnimator animator;
    private float phase;

    public SunLoader(Context context) {
        super(context);
        ray.setColor(0xFFFFD600);
        ray.setStrokeCap(Paint.Cap.ROUND);
        mark = context.getDrawable(R.drawable.ic_nenotv_mark);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Starts the turn; stays a still sun when the system has animations switched off. */
    public void start() {
        if (animator != null) return;
        if (!ValueAnimator.areAnimatorsEnabled()) { phase = 0; invalidate(); return; }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(12000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    public void stop() {
        if (animator != null) { animator.cancel(); animator = null; }
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        float inner = r * 0.62f, outer = r * 0.94f;
        ray.setStrokeWidth(Math.max(2f, r * 0.07f));
        canvas.save();
        canvas.rotate(phase * 360f, cx, cy);
        for (int i = 0; i < RAYS; i++) {
            double a = Math.PI * 2 * i / RAYS;
            // Alternate long and short rays; each one pulses a little with the turn.
            float len = (i % 2 == 0 ? 1f : 0.8f) * (0.9f + 0.1f * (float) Math.sin((phase * 4 + i / (float) RAYS) * Math.PI * 2));
            float end = inner + (outer - inner) * len;
            ray.setAlpha(i % 2 == 0 ? 255 : 170);
            canvas.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                    cx + (float) Math.cos(a) * end, cy + (float) Math.sin(a) * end, ray);
        }
        canvas.restore();
        if (mark != null) {
            float breathe = 1f + 0.04f * (float) Math.sin(phase * 6 * Math.PI * 2);
            int half = Math.round(r * 0.5f * breathe);
            mark.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
            mark.draw(canvas);
        }
    }
}
