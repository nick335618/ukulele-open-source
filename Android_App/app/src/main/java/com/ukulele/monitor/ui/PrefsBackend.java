package com.ukulele.monitor.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Android {@link SharedPreferences} implementation of {@link SettingsStore.Backend}.
 *
 * <p>This is the only Android-aware storage glue in the settings stack: {@code SettingsStore}
 * itself never imports an Android type, which keeps it testable on a plain JVM.
 *
 * <p>Reads are defensive. {@code SharedPreferences} throws {@link ClassCastException} when a key
 * holds a value of a different type than the caller asked for (for example after a key changed
 * type between app versions); that exception is converted into the requested default so a stale
 * preference can never crash a settings read.
 *
 * <p>Writes use {@link SharedPreferences.Editor#apply()}: the in-memory map is updated
 * synchronously (so a read right after a write sees the new value) while the disk write happens
 * in the background.
 */
public final class PrefsBackend {

    /** Preferences file used by {@link #of(Context)} and {@link SettingsStore#store(Context)}. */
    public static final String PREFS_NAME = "ukulele_settings";

    private PrefsBackend() {
    }

    /**
     * Wraps an existing {@link SharedPreferences} instance.
     *
     * @throws IllegalArgumentException if {@code prefs} is {@code null}
     */
    public static SettingsStore.Backend of(SharedPreferences prefs) {
        if (prefs == null) {
            throw new IllegalArgumentException("prefs must not be null");
        }
        return new SharedPreferencesBackend(prefs);
    }

    /**
     * Opens {@link #PREFS_NAME} with {@link Context#MODE_PRIVATE} and wraps it.
     *
     * @throws IllegalArgumentException if {@code context} is {@code null}
     */
    public static SettingsStore.Backend of(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        return of(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    /** {@link SettingsStore.Backend} on top of a {@link SharedPreferences} instance. */
    public static final class SharedPreferencesBackend implements SettingsStore.Backend {

        private final SharedPreferences prefs;

        public SharedPreferencesBackend(SharedPreferences prefs) {
            if (prefs == null) {
                throw new IllegalArgumentException("prefs must not be null");
            }
            this.prefs = prefs;
        }

        @Override
        public String getString(String key, String def) {
            try {
                String value = prefs.getString(key, def);
                return value == null ? def : value;
            } catch (ClassCastException e) {
                return def;
            }
        }

        @Override
        public int getInt(String key, int def) {
            try {
                return prefs.getInt(key, def);
            } catch (ClassCastException e) {
                return def;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            try {
                return prefs.getBoolean(key, def);
            } catch (ClassCastException e) {
                return def;
            }
        }

        @Override
        public void putString(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @Override
        public void putInt(String key, int value) {
            prefs.edit().putInt(key, value).apply();
        }

        @Override
        public void putBoolean(String key, boolean value) {
            prefs.edit().putBoolean(key, value).apply();
        }

        @Override
        public void remove(String key) {
            prefs.edit().remove(key).apply();
        }
    }
}
