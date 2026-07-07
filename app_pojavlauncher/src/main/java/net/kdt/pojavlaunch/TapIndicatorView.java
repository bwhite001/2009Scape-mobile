package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Transparent full-screen overlay that draws a brief fading ring at the last tap
 * location, so the touch point is visible behind the finger. Launcher-only visual;
 * it never consumes touch (not clickable / not focusable).
 *
 * Single colour by design: the OSRS red-vs-yellow ("interacting vs not") distinction
 * requires knowing whether the tap hit an entity, which is client-side (RT4) knowledge
 * not available in the launcher. That variant belongs to the Bucket B client spec.
 */
public class TapIndicatorView extends View {
    private static final long DURATION_MS = 350L;
    private static final float MAX_RADIUS_DP = 22f;

    private static TapIndicatorView sInstance;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mMaxRadiusPx;
    private float mTapX, mTapY;
    private long mStartTime = -1L;

    public TapIndicatorView(Context context) { this(context, null); }

    public TapIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setClickable(false);
        mMaxRadiusPx = MAX_RADIUS_DP * getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(2f, mMaxRadiusPx * 0.12f));
        sInstance = this;
    }

    /** Trigger a ring centred at the given view-space (screen-pixel) coordinates. */
    public void showAt(float x, float y) {
        mTapX = x;
        mTapY = y;
        mStartTime = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Forward a tap to the active overlay instance, if any. */
    public static void showTap(float x, float y) {
        TapIndicatorView v = sInstance;
        if (v != null) v.showAt(x, y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mStartTime < 0) return;
        long elapsed = SystemClock.uptimeMillis() - mStartTime;
        if (elapsed >= DURATION_MS) { mStartTime = -1L; return; }
        float t = elapsed / (float) DURATION_MS;              // 0..1 progress
        float radius = mMaxRadiusPx * (0.35f + 0.65f * t);    // grows outward
        int alpha = (int) (200 * (1f - t));                   // fades out
        mPaint.setColor(Color.argb(alpha, 255, 225, 90));     // yellow ring
        canvas.drawCircle(mTapX, mTapY, radius, mPaint);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sInstance == this) sInstance = null;
    }
}
