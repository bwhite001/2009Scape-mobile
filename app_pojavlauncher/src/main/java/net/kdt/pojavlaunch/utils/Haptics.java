package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Minimal one-shot haptic helper for touch interactions. Launcher-side only. */
public final class Haptics {
    private Haptics() {}

    /** Default long-press vibration duration in milliseconds.
     *  Intensity becomes a user setting in a later tier (modernisation phase 2d). */
    public static final int LONG_PRESS_MS = 45;

    /** Fire a one-shot vibration. No-op when the device has no vibrator or duration <= 0. */
    public static void vibrate(Context context, int durationMs) {
        if (context == null || durationMs <= 0) return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(durationMs);
        }
    }
}
