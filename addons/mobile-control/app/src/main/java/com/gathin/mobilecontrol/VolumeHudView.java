package com.gathin.mobilecontrol;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class VolumeHudView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF bgRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF progressRect = new RectF();

    private int targetPercent = 50;
    private float animatedPercent = 50f;
    private boolean isMuted = false;
    private String deviceTitle = "JBL CINEMA SB560 (ARC)";
    private ValueAnimator anim;

    public VolumeHudView(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Dark glass background
        bgPaint.setColor(Color.parseColor("#E60D111A"));
        bgPaint.setStyle(Paint.Style.FILL);

        // Subtle crisp border
        borderPaint.setColor(Color.parseColor("#33FFFFFF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));

        // Audio cyan icon
        iconPaint.setColor(Color.parseColor("#00E5FF"));
        iconPaint.setTextSize(sp(20f));
        iconPaint.setTextAlign(Paint.Align.CENTER);

        // Title device label
        titlePaint.setColor(Color.parseColor("#94A3B8"));
        titlePaint.setTextSize(sp(11.5f));
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        // Percentage text
        percentPaint.setColor(Color.WHITE);
        percentPaint.setTextSize(sp(15.5f));
        percentPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        percentPaint.setTextAlign(Paint.Align.RIGHT);

        // Track background
        trackPaint.setColor(Color.parseColor("#1E2533"));
        trackPaint.setStyle(Paint.Style.FILL);

        // Progress bar fill
        progressPaint.setStyle(Paint.Style.FILL);
    }

    public void updateState(int volume, int maxVolume, boolean muted, String deviceName) {
        int max = maxVolume > 0 ? maxVolume : 100;
        int clamped = Math.max(0, Math.min(max, volume));
        this.targetPercent = (clamped * 100) / max;
        this.isMuted = muted || (targetPercent <= 0);
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            this.deviceTitle = deviceName.trim().toUpperCase();
        }

        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(animatedPercent, (float) targetPercent);
        anim.setDuration(130);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            animatedPercent = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float p = dp(1f);
        bgRect.set(p, p, w - p, h - p);

        // Progress bar bounds
        float trackLeft = dp(52f);
        float trackRight = w - dp(18f);
        float trackTop = h - dp(15f);
        float trackBottom = h - dp(8f);
        trackRect.set(trackLeft, trackTop, trackRight, trackBottom);

        // Gradient shader for progress bar
        progressPaint.setShader(new LinearGradient(
                trackLeft, trackTop, trackRight, trackTop,
                new int[]{Color.parseColor("#00E5FF"), Color.parseColor("#3B82F6")},
                null, Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cornerRadius = dp(14f);

        // Draw card background & border
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint);

        float w = getWidth();

        // 1. Icon (Speaker or Mute)
        String icon = isMuted ? "🔇" : (targetPercent < 33 ? "🔈" : (targetPercent < 66 ? "🔉" : "🔊"));
        iconPaint.setColor(isMuted ? Color.parseColor("#EF4444") : Color.parseColor("#00E5FF"));
        canvas.drawText(icon, dp(26f), dp(34f), iconPaint);

        // 2. Title / Device Label
        canvas.drawText(deviceTitle, dp(52f), dp(25f), titlePaint);

        // 3. Percentage or "MUTED"
        if (isMuted) {
            percentPaint.setColor(Color.parseColor("#F87171"));
            canvas.drawText("MUTED", w - dp(18f), dp(26f), percentPaint);
        } else {
            percentPaint.setColor(Color.WHITE);
            canvas.drawText(targetPercent + "%", w - dp(18f), dp(26f), percentPaint);
        }

        // 4. Progress Track
        canvas.drawRoundRect(trackRect, dp(4f), dp(4f), trackPaint);

        // 5. Progress Fill
        if (!isMuted && animatedPercent > 0.5f) {
            float progressWidth = (trackRect.width() * animatedPercent) / 100f;
            progressRect.set(trackRect.left, trackRect.top, trackRect.left + progressWidth, trackRect.bottom);
            canvas.drawRoundRect(progressRect, dp(4f), dp(4f), progressPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
