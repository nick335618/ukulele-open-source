package com.ukulele.monitor.ui;

import java.util.*;

/**
 * Application settings for Ukulele Studio, persisted through an injectable {@link Backend}.
 *
 * <p><b>Zero Android dependency.</b> This class only imports {@code java.util.*}; all storage
 * goes through {@link Backend}. That keeps it unit-testable on a plain JVM with an in-memory
 * backend. The single Android touch point is the convenience factory
 * {@link #store(android.content.Context)}, which only delegates to
 * {@link PrefsBackend#of(android.content.Context)}; no other member of this class mentions an
 * Android type. This class must never reference {@code AppTheme} (theme resolution goes the
 * other way: {@code AppTheme} reads this store), so the dependency graph stays acyclic.
 *
 * <p><b>Keys.</b> Every preference key is prefixed {@code uk_}; the public {@code KEY_*} constants
 * below are the exact keys written to storage.
 *
 * <p><b>Defaults &amp; validation.</b> Reading a key that is absent yields the default listed in
 * the field documentation. Reading a <em>stored but illegal</em> value also yields the default:
 * out-of-range enums ({@code textScale}, {@code noteStyle}), unknown theme modes, unexpected
 * song kinds and negative byte counts are all normalised. Setters normalise the same way, so
 * {@code setTextScale(99)} behaves exactly like {@code setTextScale(TEXT_SCALE_NORMAL)}.
 *
 * <p><b>Change notification.</b> A setter notifies listeners through
 * {@link Listener#onSettingsChanged(String)} <em>only when the effective value actually
 * changes</em>; writing the same value again is a no-op (no write, no callback). Listeners are
 * notified on the calling thread, after the value has been stored. Notification iterates over a
 * snapshot of the listener list taken under a lock, so a listener may safely add or remove
 * listeners (including itself) while it is being called. A listener that throws is ignored so
 * that it cannot break persistence or the remaining listeners.
 *
 * <p>Instances are cheap; all methods are safe to call from any thread.
 */
public final class SettingsStore {

    /** Storage abstraction. Implemented by {@link PrefsBackend} on Android and by a plain map in tests. */
    public interface Backend {
        String getString(String key, String def);

        int getInt(String key, int def);

        boolean getBoolean(String key, boolean def);

        void putString(String key, String value);

        void putInt(String key, int value);

        void putBoolean(String key, boolean value);

        void remove(String key);
    }

    /** Receives the key of a setting whose effective value has just changed. */
    public interface Listener {
        void onSettingsChanged(String key);
    }

    // ---------------------------------------------------------------- values

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";

    public static final int TEXT_SCALE_SMALL = 0;
    public static final int TEXT_SCALE_NORMAL = 1;
    public static final int TEXT_SCALE_LARGE = 2;

    public static final int NOTE_STYLE_LETTER = 0;
    public static final int NOTE_STYLE_SOLFEGE = 1;
    public static final int NOTE_STYLE_FRET = 2;

    /** 评分宽容档位：越靠后，判定窗口越往前移，用来抵消设备反馈的传输延迟。 */
    public static final int LEAD_STRICT = 0;
    public static final int LEAD_NORMAL = 1;
    public static final int LEAD_RELAXED = 2;

    /** {@link #lastSongKind()} value for a song that came from the bundled library. */
    public static final String KIND_LIBRARY = "library";
    /** {@link #lastSongKind()} value for a song the user imported. */
    public static final String KIND_IMPORT = "import";
    /** {@link #lastSongKind()} value when nothing is remembered. */
    public static final String KIND_NONE = "";

    // ---------------------------------------------------------------- keys

    public static final String KEY_THEME_MODE = "uk_theme_mode";
    public static final String KEY_TEXT_SCALE = "uk_text_scale";
    public static final String KEY_NOTE_STYLE = "uk_note_style";
    public static final String KEY_PRACTICE_LEAD = "uk_practice_lead";
    public static final String KEY_GUIDE_STRINGS_REVERSED = "uk_guide_strings_reversed";
    public static final String KEY_GUIDE_LANDING_RIGHT = "uk_guide_landing_right";
    public static final String KEY_SHOW_FINGERING = "uk_show_fingering";
    public static final String KEY_KEEP_SCREEN_ON = "uk_keep_screen_on";
    public static final String KEY_HAPTICS_ENABLED = "uk_haptics_enabled";
    public static final String KEY_SOUND_ENABLED = "uk_sound_enabled";
    public static final String KEY_AUTO_CONNECT = "uk_auto_connect";
    public static final String KEY_LAST_DEVICE_ADDRESS = "uk_last_device_address";
    public static final String KEY_LAST_DEVICE_NAME = "uk_last_device_name";
    public static final String KEY_REMEMBER_LAST_SONG = "uk_remember_last_song";
    public static final String KEY_LAST_SONG_ID = "uk_last_song_id";
    public static final String KEY_LAST_SONG_NAME = "uk_last_song_name";
    public static final String KEY_LAST_SONG_KIND = "uk_last_song_kind";
    public static final String KEY_LAST_SONG_ASSET = "uk_last_song_asset";
    public static final String KEY_LAST_SONG_BYTES = "uk_last_song_bytes";
    public static final String KEY_FIRST_RUN = "uk_first_run";
    /** v3.11.0：启动加载动画开关。关闭后冷启动直接进入界面。 */
    public static final String KEY_SPLASH_ENABLED = "uk_splash_enabled";
    /** v3.11.0：试听乐谱输出音量（0..100，默认 100）。手机媒体音量之外再留一档应用内调节。 */
    public static final String KEY_PREVIEW_VOLUME = "uk_preview_volume";

    // Short aliases kept for the names MainActivity's settings-change listener already uses.
    // They are the same strings, NOT separate keys.
    public static final String KEY_THEME = KEY_THEME_MODE;
    public static final String KEY_HAPTICS = KEY_HAPTICS_ENABLED;
    public static final String KEY_SOUND = KEY_SOUND_ENABLED;

    // ---------------------------------------------------------------- factory

    /** Cached instance returned by {@link #store(android.content.Context)}; see that method. */
    private static volatile SettingsStore contextStore;

    private final Backend backend;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Object listenerLock = new Object();

    private SettingsStore(Backend backend) {
        this.backend = backend;
    }

    /**
     * Creates a store writing through {@code backend}. Every call returns a fresh instance; the
     * backend is the shared state, so two stores over the same backend see the same values while
     * listeners stay per-instance.
     *
     * @throws IllegalArgumentException if {@code backend} is {@code null}
     */
    public static SettingsStore store(Backend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        return new SettingsStore(backend);
    }

    /**
     * Convenience factory backed by {@code SharedPreferences} ({@link PrefsBackend}).
     *
     * <p>This returns a lazily created process-wide singleton: the underlying preferences file
     * ({@link PrefsBackend#PREFS_NAME}) is process-wide state anyway, and sharing one instance
     * means listeners registered by different screens all fire for the same store. For isolated
     * instances (tests, alternative backends) use {@link #store(Backend)}.
     *
     * @throws IllegalArgumentException if {@code context} is {@code null}
     */
    public static SettingsStore store(android.content.Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        SettingsStore store = contextStore;
        if (store == null) {
            synchronized (SettingsStore.class) {
                store = contextStore;
                if (store == null) {
                    store = store(PrefsBackend.of(context));
                    contextStore = store;
                }
            }
        }
        return store;
    }

    // ---------------------------------------------------------------- listeners

    /** Registers {@code listener}; {@code null} is ignored and the same listener is only added once. */
    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listenerLock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    /** Removes {@code listener} if present; {@code null} is ignored. */
    public void removeListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listenerLock) {
            listeners.remove(listener);
        }
    }

    // ---------------------------------------------------------------- settings

    /** Theme mode: {@link #THEME_DARK} (default), {@link #THEME_LIGHT} or {@link #THEME_SYSTEM}. */
    public String themeMode() {
        return normalizeThemeMode(backend.getString(KEY_THEME_MODE, THEME_DARK));
    }

    public void setThemeMode(String value) {
        putString(KEY_THEME_MODE, themeMode(), normalizeThemeMode(value));
    }

    /** Text scale: {@link #TEXT_SCALE_SMALL}, {@link #TEXT_SCALE_NORMAL} (default) or {@link #TEXT_SCALE_LARGE}. */
    public int textScale() {
        int value = backend.getInt(KEY_TEXT_SCALE, TEXT_SCALE_NORMAL);
        return (value < TEXT_SCALE_SMALL || value > TEXT_SCALE_LARGE) ? TEXT_SCALE_NORMAL : value;
    }

    public void setTextScale(int value) {
        putInt(KEY_TEXT_SCALE, textScale(), normalizeTextScale(value));
    }

    /** Note naming style: {@link #NOTE_STYLE_LETTER} (default), {@link #NOTE_STYLE_SOLFEGE} or {@link #NOTE_STYLE_FRET}. */
    public int noteStyle() {
        int value = backend.getInt(KEY_NOTE_STYLE, NOTE_STYLE_LETTER);
        return (value < NOTE_STYLE_LETTER || value > NOTE_STYLE_FRET) ? NOTE_STYLE_LETTER : value;
    }

    public void setNoteStyle(int value) {
        putInt(KEY_NOTE_STYLE, noteStyle(), normalizeNoteStyle(value));
    }

    /** Whether fingering hints are drawn. Default {@code true}. */
    public boolean showFingering() {
        return backend.getBoolean(KEY_SHOW_FINGERING, true);
    }

    public void setShowFingering(boolean value) {
        putBoolean(KEY_SHOW_FINGERING, showFingering(), value);
    }

    /**
     * 评分宽容档位：设备拨弦反馈到达手机时已经晚了一截（BLE 传输 + 通知排队 = 20~150ms），
     * 而评分是按「收到反馈的时刻」计时的，所以即使你弹在拍点上也会被判**偏晚**。
     * 这一项让用户把判定窗口整体前移，抵消这段延迟。
     */
    public int practiceLead() {
        int value = backend.getInt(KEY_PRACTICE_LEAD, LEAD_NORMAL);
        return (value < LEAD_STRICT || value > LEAD_RELAXED) ? LEAD_NORMAL : value;
    }

    public void setPracticeLead(int value) {
        putInt(KEY_PRACTICE_LEAD, practiceLead(), normalizePracticeLead(value));
    }

    /** Default keeps the existing left baseline / right-to-left note motion. */
    public boolean guideLandingRight() {
        return backend.getBoolean(KEY_GUIDE_LANDING_RIGHT, false);
    }

    public void setGuideLandingRight(boolean value) {
        putBoolean(KEY_GUIDE_LANDING_RIGHT, guideLandingRight(), value);
    }

    public boolean guideStringsReversed(){return backend.getBoolean(KEY_GUIDE_STRINGS_REVERSED,false);}
    public void setGuideStringsReversed(boolean value){putBoolean(KEY_GUIDE_STRINGS_REVERSED,guideStringsReversed(),value);}

    /** Whether the screen stays on while the app is in front. Default {@code false}. */
    public boolean keepScreenOn() {
        return backend.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public void setKeepScreenOn(boolean value) {
        putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn(), value);
    }

    /** Whether haptic feedback is enabled. Default {@code true}. */
    public boolean hapticsEnabled() {
        return backend.getBoolean(KEY_HAPTICS_ENABLED, true);
    }

    public void setHapticsEnabled(boolean value) {
        putBoolean(KEY_HAPTICS_ENABLED, hapticsEnabled(), value);
    }

    /** Whether UI sounds are enabled. Default {@code false}. */
    public boolean soundEnabled() {
        return backend.getBoolean(KEY_SOUND_ENABLED, false);
    }

    public void setSoundEnabled(boolean value) {
        putBoolean(KEY_SOUND_ENABLED, soundEnabled(), value);
    }

    /** Whether the last device is reconnected automatically. Default {@code true}. */
    public boolean autoConnect() {
        return backend.getBoolean(KEY_AUTO_CONNECT, true);
    }

    public void setAutoConnect(boolean value) {
        putBoolean(KEY_AUTO_CONNECT, autoConnect(), value);
    }

    /** Last connected device address, or {@code ""} (default). */
    public String lastDeviceAddress() {
        return str(KEY_LAST_DEVICE_ADDRESS, "");
    }

    public void setLastDeviceAddress(String value) {
        putString(KEY_LAST_DEVICE_ADDRESS, lastDeviceAddress(), nz(value));
    }

    /** Last connected device name, or {@code ""} (default). */
    public String lastDeviceName() {
        return str(KEY_LAST_DEVICE_NAME, "");
    }

    public void setLastDeviceName(String value) {
        putString(KEY_LAST_DEVICE_NAME, lastDeviceName(), nz(value));
    }

    /** Whether the last played song is remembered. Default {@code true}. */
    public boolean rememberLastSong() {
        return backend.getBoolean(KEY_REMEMBER_LAST_SONG, true);
    }

    public void setRememberLastSong(boolean value) {
        putBoolean(KEY_REMEMBER_LAST_SONG, rememberLastSong(), value);
    }

    /** Id of the last played song, or {@code ""} (default). */
    public String lastSongId() {
        return str(KEY_LAST_SONG_ID, "");
    }

    public void setLastSongId(String value) {
        putString(KEY_LAST_SONG_ID, lastSongId(), nz(value));
    }

    /** Display name of the last played song, or {@code ""} (default). */
    public String lastSongName() {
        return str(KEY_LAST_SONG_NAME, "");
    }

    public void setLastSongName(String value) {
        putString(KEY_LAST_SONG_NAME, lastSongName(), nz(value));
    }

    /**
     * Kind of the last played song: {@link #KIND_LIBRARY}, {@link #KIND_IMPORT} or
     * {@link #KIND_NONE} (default). Any other stored value is treated as {@link #KIND_NONE}.
     */
    public String lastSongKind() {
        return normalizeKind(str(KEY_LAST_SONG_KIND, KIND_NONE));
    }

    public void setLastSongKind(String value) {
        putString(KEY_LAST_SONG_KIND, lastSongKind(), normalizeKind(value));
    }

    /** Asset name of the last library song; only meaningful when {@link #lastSongKind()} is {@code library}. */
    public String lastSongAsset() {
        return str(KEY_LAST_SONG_ASSET, "");
    }

    public void setLastSongAsset(String value) {
        putString(KEY_LAST_SONG_ASSET, lastSongAsset(), nz(value));
    }

    /** Size in bytes of the last imported song; {@code 0} (default) means "not recorded". Never negative. */
    public int lastSongBytes() {
        int value = backend.getInt(KEY_LAST_SONG_BYTES, 0);
        return value < 0 ? 0 : value;
    }

    public void setLastSongBytes(int value) {
        putInt(KEY_LAST_SONG_BYTES, lastSongBytes(), value < 0 ? 0 : value);
    }

    /** Whether the app has never completed its first run. Default {@code true}. */
    public boolean firstRun() {
        return backend.getBoolean(KEY_FIRST_RUN, true);
    }

    public void setFirstRun(boolean value) {
        putBoolean(KEY_FIRST_RUN, firstRun(), value);
    }

    /**
     * 是否在冷启动时播放品牌加载动画。默认 {@code true}。
     *
     * <p>做成开关而不是强制播放：动画会延后约 2 秒进入界面，给习惯快速连设备的用户
     * 一个跳过的权利；首启欢迎弹窗也会等动画结束后再出现。
     */
    public boolean splashEnabled() {
        return backend.getBoolean(KEY_SPLASH_ENABLED, true);
    }

    public void setSplashEnabled(boolean value) {
        putBoolean(KEY_SPLASH_ENABLED, splashEnabled(), value);
    }

    /**
     * 试听乐谱的应用内音量，0..200，默认 {@code 130}（100 以上代表继续提升）。
     *
     * <p>0–100 通过 AudioTrack 增益衰减；100–200 交给合成器在软限幅前继续加增益，
     * 这样手机媒体音量拉满仍偏轻时还有明确的提升空间，且峰值由软限幅兜住不会硬削波。
     */
    public int previewVolume() {
        int value = backend.getInt(KEY_PREVIEW_VOLUME, 130);
        return value < 0 ? 0 : (value > 200 ? 200 : value);
    }

    public void setPreviewVolume(int value) {
        int clamped = value < 0 ? 0 : (value > 200 ? 200 : value);
        putInt(KEY_PREVIEW_VOLUME, previewVolume(), clamped);
    }

    // ---------------------------------------------------------------- internals

    private String str(String key, String def) {
        String value = backend.getString(key, def);
        return value == null ? def : value;
    }

    /** Null-coalescing helper for values coming from callers (never reads the backend). */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private void putString(String key, String current, String next) {
        String value = next == null ? "" : next;
        if (value.equals(current)) {
            return;
        }
        backend.putString(key, value);
        fireChanged(key);
    }

    private void putInt(String key, int current, int next) {
        if (next == current) {
            return;
        }
        backend.putInt(key, next);
        fireChanged(key);
    }

    private void putBoolean(String key, boolean current, boolean next) {
        if (next == current) {
            return;
        }
        backend.putBoolean(key, next);
        fireChanged(key);
    }

    /** Notifies every listener registered at this moment; add/remove during iteration is safe. */
    private void fireChanged(String key) {
        Listener[] snapshot;
        synchronized (listenerLock) {
            if (listeners.isEmpty()) {
                return;
            }
            snapshot = listeners.toArray(new Listener[listeners.size()]);
        }
        for (int i = 0; i < snapshot.length; i++) {
            Listener listener = snapshot[i];
            if (listener == null) {
                continue;
            }
            try {
                listener.onSettingsChanged(key);
            } catch (Throwable ignored) {
                // A misbehaving listener must never break persistence or the other listeners.
            }
        }
    }

    static String normalizeThemeMode(String value) {
        return AppTheme.Style.fromId(value).id;
    }

    static int normalizeTextScale(int value) {
        return (value < TEXT_SCALE_SMALL || value > TEXT_SCALE_LARGE) ? TEXT_SCALE_NORMAL : value;
    }

    static int normalizeNoteStyle(int value) {
        return (value < NOTE_STYLE_LETTER || value > NOTE_STYLE_FRET) ? NOTE_STYLE_LETTER : value;
    }

    static int normalizePracticeLead(int value) {
        return (value < LEAD_STRICT || value > LEAD_RELAXED) ? LEAD_NORMAL : value;
    }

    static String normalizeKind(String value) {
        if (KIND_LIBRARY.equals(value) || KIND_IMPORT.equals(value)) {
            return value;
        }
        return KIND_NONE;
    }
}
