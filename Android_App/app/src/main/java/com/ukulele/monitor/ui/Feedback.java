package com.ukulele.monitor.ui;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Lightweight haptic + audible UI feedback for Ukulele Studio.
 *
 * <p>Both channels are best-effort and never throw: a device with no vibrator, a missing
 * {@code android.permission.VIBRATE} grant, an unavailable audio sink or a released tone
 * generator all degrade silently to "no feedback". Callers therefore never need to guard a
 * {@link #tap()} / {@link #confirm()} / {@link #warn()} call.
 *
 * <p>Haptics use {@link VibrationEffect} (API 26+, the app's minSdk): a short low-amplitude
 * one-shot for {@link #tap()}, a two-pulse waveform for {@link #confirm()} and a slightly
 * stronger one-shot for {@link #warn()}. On API 31+ the vibrator is obtained from
 * {@link VibratorManager#getDefaultVibrator()}, below that from
 * {@link Context#getSystemService(String)} with {@link Context#VIBRATOR_SERVICE}.
 *
 * <p>Sounds use {@link ToneGenerator}, which is created lazily on first use (its constructor is
 * comparatively expensive and may throw) and must be released with {@link #release()}.
 * {@link #setSoundEnabled(boolean)} with {@code false} silences playback and releases the tone
 * generator immediately; a later {@code true} recreates it on demand.
 *
 * <p><b>Threading.</b> All public methods are safe to call from any thread and are intended to be
 * called from the main thread. The constructor only resolves the system vibrator; no audio object
 * is created until a sound is actually requested.
 */
public final class Feedback {

    private static final int TAP_MS = 12;
    private static final int TAP_AMPLITUDE = 90;

    /** Two-pulse confirmation: silence, pulse, gap, longer pulse. */
    private static final long[] CONFIRM_TIMINGS = {0L, 22L, 62L, 30L};
    private static final int[] CONFIRM_AMPLITUDES = {0, 130, 0, 180};

    private static final int WARN_MS = 28;
    private static final int WARN_AMPLITUDE = 210;

    private static final int TONE_VOLUME = 70;
    private static final int CONFIRM_TONE_MS = 120;
    private static final int WARN_TONE_MS = 190;

    private final Object toneLock = new Object();
    private final Vibrator vibrator;

    private volatile boolean hapticsEnabled = true;
    private volatile boolean soundEnabled = false;
    private ToneGenerator tone;

    public Feedback(Context context) {
        this.vibrator = resolveVibrator(context);
    }

    /** Enables/disables the haptic channel (default {@code true}). */
    public void setHapticsEnabled(boolean enabled) {
        this.hapticsEnabled = enabled;
    }

    /** Enables/disables the audible channel (default {@code false}); disabling releases the tone generator. */
    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        if (!enabled) {
            releaseTone();
        }
    }

    /** Short, low-amplitude tap. Haptic only. */
    public void tap() {
        vibrateOneShot(TAP_MS, TAP_AMPLITUDE);
    }

    /** Success confirmation: two haptic pulses plus a short acknowledge tone when sound is on. */
    public void confirm() {
        vibrateWaveform(CONFIRM_TIMINGS, CONFIRM_AMPLITUDES);
        playTone(ToneGenerator.TONE_PROP_ACK, CONFIRM_TONE_MS);
    }

    /** Warning/failure: one longer haptic pulse plus a negative tone when sound is on. */
    public void warn() {
        vibrateOneShot(WARN_MS, WARN_AMPLITUDE);
        playTone(ToneGenerator.TONE_PROP_NACK, WARN_TONE_MS);
    }

    /**
     * Releases the tone generator (vibration needs no release). Safe to call more than once and
     * safe to call while other threads are playing; a later sound request recreates the generator
     * lazily. Typical use: {@code onDestroy()}.
     */
    public void release() {
        releaseTone();
    }

    // ---------------------------------------------------------------- haptics

    private void vibrateOneShot(int durationMs, int amplitude) {
        if (!hapticsEnabled) {
            return;
        }
        Vibrator device = vibrator;
        if (device == null || !canVibrate(device)) {
            return;
        }
        try {
            device.vibrate(VibrationEffect.createOneShot(durationMs, clampAmplitude(amplitude)));
        } catch (Throwable ignored) {
            // Missing VIBRATE permission, disabled motor, vendor quirk: stay silent.
        }
    }

    private void vibrateWaveform(long[] timings, int[] amplitudes) {
        if (!hapticsEnabled) {
            return;
        }
        Vibrator device = vibrator;
        if (device == null || !canVibrate(device)) {
            return;
        }
        try {
            VibrationEffect effect;
            if (hasAmplitudeControl(device)) {
                effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
            } else {
                // Motor without amplitude control: keep the rhythm, drop the amplitudes.
                effect = VibrationEffect.createWaveform(timings, -1);
            }
            device.vibrate(effect);
        } catch (Throwable ignored) {
            // Same silent degradation as above.
        }
    }

    private static boolean canVibrate(Vibrator device) {
        try {
            return device.hasVibrator();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAmplitudeControl(Vibrator device) {
        try {
            return device.hasAmplitudeControl();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int clampAmplitude(int amplitude) {
        if (amplitude < 1) {
            return 1;
        }
        return amplitude > 255 ? 255 : amplitude;
    }

    /**
     * API 31+: {@code VibratorManager.getDefaultVibrator()}; below that the legacy
     * {@code VIBRATOR_SERVICE}. Never throws: returns {@code null} when unavailable.
     */
    @SuppressWarnings("deprecation") // Context.VIBRATOR_SERVICE is required on API 26..30
    private static Vibrator resolveVibrator(Context context) {
        if (context == null) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return fromVibratorManager(context);
            }
            Object service = context.getSystemService(Context.VIBRATOR_SERVICE);
            return service instanceof Vibrator ? (Vibrator) service : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation") // legacy fallback for API 26..30
    private static Vibrator fromVibratorManager(Context context) {
        try {
            Object service = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (service instanceof VibratorManager) {
                return ((VibratorManager) service).getDefaultVibrator();
            }
        } catch (Throwable ignored) {
            // Fall through to the legacy service lookup below.
        }
        try {
            Object service = context.getSystemService(Context.VIBRATOR_SERVICE);
            return service instanceof Vibrator ? (Vibrator) service : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- sound

    private void playTone(int toneType, int durationMs) {
        if (!soundEnabled) {
            return;
        }
        ToneGenerator generator = obtainTone();
        if (generator == null) {
            return;
        }
        try {
            generator.startTone(toneType, durationMs);
        } catch (Throwable ignored) {
            // Audio focus lost / device without speaker: keep it silent.
        }
    }

    /** Lazily creates the tone generator; {@code null} when the platform refuses to build one. */
    private ToneGenerator obtainTone() {
        synchronized (toneLock) {
            if (tone != null) {
                return tone;
            }
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME);
            } catch (Throwable ignored) {
                // ToneGenerator's constructor throws RuntimeException on some devices.
                tone = null;
            }
            return tone;
        }
    }

    private void releaseTone() {
        synchronized (toneLock) {
            if (tone == null) {
                return;
            }
            try {
                tone.stopTone();
            } catch (Throwable ignored) {
                // Nothing to stop.
            }
            try {
                tone.release();
            } catch (Throwable ignored) {
                // Already released by the platform.
            }
            tone = null;
        }
    }
}
