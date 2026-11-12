package com.picoxr.resfix.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;

import com.bytedance.osui.utils.OSUIHelper;

public class HapticUtils {
    private static final String TAG = "PicoHaptic";

    private static int sLastDeviceId = 0;
    private static long sLastHoverTimeMs = 0;
    private static final long HOVER_DEBOUNCE_MS = 80;

    public enum HapticProfile {
        HOVER(120f, 177f, 10f),
        CLICK(255f, 100f, 35f),
        TOGGLE(240f, 120f, 25f),
        SUCCESS(255f, 90f, 40f),
        ERROR(255f, 70f, 60f);

        public final float amplitude;
        public final float frequency;
        public final float duration;

        HapticProfile(float amplitude, float frequency, float duration) {
            this.amplitude = amplitude;
            this.frequency = frequency;
            this.duration = duration;
        }
    }

    public static void setLastDeviceId(int deviceId) {
        if (deviceId != 0) {
            sLastDeviceId = deviceId;
        }
    }

    public static int getLastDeviceId() {
        return sLastDeviceId;
    }

    public static boolean isHapticEnabled(Context context) {
        if (context == null) return true;
        try {
            SharedPreferences prefs = context.getSharedPreferences("resfix_prefs", Context.MODE_PRIVATE);
            return prefs.getBoolean("pref_haptic_on", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void triggerHaptic(boolean isHover, int deviceId) {
        triggerHaptic(isHover ? HapticProfile.HOVER : HapticProfile.CLICK, deviceId, null);
    }

    public static void triggerHaptic(HapticProfile profile, int deviceId, Context context) {
        if (context != null && !isHapticEnabled(context)) {
            return;
        }

        boolean isHover = (profile == HapticProfile.HOVER);
        if (isHover) {
            long now = SystemClock.uptimeMillis();
            if (now - sLastHoverTimeMs < HOVER_DEBOUNCE_MS) {
                return;
            }
            sLastHoverTimeMs = now;
        }

        try {
            if (!OSUIHelper.isLoaded) {
                Log.w(TAG, "OSUIHelper is not loaded");
                return;
            }

            int targetDeviceId = (deviceId != 0) ? deviceId : getLastDeviceId();
            if (deviceId != 0) {
                setLastDeviceId(deviceId);
            }

            int nativeDeviceId = switch (targetDeviceId) {
                case 100001, 1 -> 1;
                case 100002, 2 -> 2;
                default -> 0;
            };

            float amp = profile.amplitude;
            float freq = profile.frequency;
            float dur = profile.duration;

            if (nativeDeviceId == 1) {
                OSUIHelper.a(1, amp, freq, dur);
            } else if (nativeDeviceId == 2) {
                OSUIHelper.a(2, amp, freq, dur);
            } else {
                // Trigger both left (1) and right (2) controllers for default/unspecified deviceId
                OSUIHelper.a(1, amp, freq, dur);
                OSUIHelper.a(2, amp, freq, dur);
            }
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetVCMotorEvent failed", t);
        }
    }

    /**
     * Attaches haptic hover listener to an interactive view.
     */
    public static void applyHoverHaptic(View view) {
        if (view == null) return;
        view.setOnHoverListener((v, event) -> {
            int deviceId = event.getDeviceId();
            setLastDeviceId(deviceId);
            if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                triggerHaptic(HapticProfile.HOVER, deviceId, v.getContext());
            }
            return false;
        });
    }

    /**
     * Attaches haptic touch/click feedback and hover haptic to an interactive view and its children.
     */
    public static void applyInteractiveHaptic(View view) {
        if (view == null) return;
        applyHoverHaptic(view);
        view.setOnTouchListener((v, event) -> {
            int deviceId = event.getDeviceId();
            setLastDeviceId(deviceId);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                triggerHaptic(HapticProfile.CLICK, deviceId, v.getContext());
            }
            return false;
        });
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                applyInteractiveHaptic(group.getChildAt(i));
            }
        }
    }

    /**
     * Attaches haptic hover & touch feedback to a Toolbar and all its child views.
     */
    public static void attachHapticToToolbar(Toolbar toolbar) {
        if (toolbar == null) return;
        applyInteractiveHaptic(toolbar);
        toolbar.post(() -> applyInteractiveHaptic(toolbar));
    }

    public static void attachHapticToDialog(android.app.Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                applyInteractiveHaptic(decor);
                decor.post(() -> applyInteractiveHaptic(decor));
            }
        });
    }
}
