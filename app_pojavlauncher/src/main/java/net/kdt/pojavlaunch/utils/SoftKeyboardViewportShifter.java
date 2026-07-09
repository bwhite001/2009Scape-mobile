package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Shifts a game view upward by the soft-keyboard (IME) height so the bottom of the
 * game surface (the RuneScape chat line) stays visible above the keyboard instead of
 * being hidden behind it.
 *
 * <p>The hosting activity must declare {@code android:windowSoftInputMode="adjustNothing"}
 * so the system does not also pan/resize the window — this class takes full manual
 * control of the viewport offset.</p>
 *
 * <p>IME insets are reliably reported on API 30+ (Android R), where they are dispatched
 * to {@link ViewCompat#setOnApplyWindowInsetsListener} even in {@code adjustNothing}
 * mode. On older versions the IME bottom inset may report {@code 0}, in which case the
 * view is simply not shifted — i.e. the pre-existing behaviour, so there is no
 * regression on those devices.</p>
 */
public final class SoftKeyboardViewportShifter {
    private SoftKeyboardViewportShifter() {}

    /**
     * @param activity    the activity whose window insets are observed
     * @param viewToShift the view translated upward while the keyboard is showing
     *                    (typically the game content root)
     */
    public static void attach(Activity activity, final View viewToShift) {
        final View root = activity.findViewById(android.R.id.content);
        if (root == null || viewToShift == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            // Guard against re-layout thrash: only touch the view when the offset changes.
            if (viewToShift.getTranslationY() != -imeBottom) {
                viewToShift.setTranslationY(-imeBottom);
            }
            return insets;
        });
    }
}
