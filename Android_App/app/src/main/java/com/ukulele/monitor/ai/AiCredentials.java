package com.ukulele.monitor.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AI 接入配置（DeepSeek 通道、个人 Key、模型、语音开关等）的本地存储。
 *
 * <p><b>个人 Key 的保存：</b>优先用 Android Keystore 的 AES/GCM 加密后再写入
 * SharedPreferences。Keystore 在极少数设备/模拟器上可能不可用（密钥库损坏、指纹变更导致
 * Key 失效等），此时降级为带 {@code PLAIN:} 前缀的明文存储，并在读取侧兼容；
 * 宁可功能可用，也不让用户因为系统密钥库异常而完全无法使用 AI。
 * 降级属于兜底路径，正常设备上 Key 永远不会以明文落盘。
 *
 * <p><b>安全约定：</b>本类不打印/记录 Key；{@link #activeKey()} 只给
 * {@link DeepSeekClient} 用，UI 展示时请用掩码。官方通道的开发者 Key 以占位常量形式放在
 * {@link OfficialKey}，由构建脚本注入，本类从不读取外部文件。
 */
public final class AiCredentials {

    public static final int MODE_OFF = 0;
    public static final int MODE_PERSONAL = 1;
    public static final int MODE_OFFICIAL = 2;

    public static final String PREFS_NAME = "uk_ai";

    private static final String KEY_MODE = "uk_ai_mode";
    private static final String KEY_PERSONAL_KEY = "uk_ai_personal_key";
    private static final String KEY_OFFICIAL_UNLOCKED = "uk_ai_official_unlocked";
    private static final String KEY_MODEL = "uk_ai_model";
    private static final String KEY_BASE_URL = "uk_ai_base_url";
    private static final String KEY_VOICE_ENABLED = "uk_ai_voice_enabled";
    private static final String KEY_COMPANION_PERSONA = "uk_ai_companion_persona";

    /** 官方通道口令。口令只是「防止普通用户误点进开发者通道」的门槛，真正的限制在服务端 Key。 */
    private static final String OFFICIAL_PASSWORD = "gowin";

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "uk_ai_personal_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final String ENCRYPTED_PREFIX = "ENC1:";
    private static final String PLAIN_PREFIX = "PLAIN:";

    private final SharedPreferences prefs;

    public AiCredentials(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 通道模式

    /** 当前通道：{@link #MODE_OFF} / {@link #MODE_PERSONAL} / {@link #MODE_OFFICIAL}。 */
    public int mode() {
        int value = prefs.getInt(KEY_MODE, MODE_OFF);
        if (value < MODE_OFF || value > MODE_OFFICIAL) {
            return MODE_OFF;
        }
        return value;
    }

    public void setMode(int mode) {
        int normalized = (mode < MODE_OFF || mode > MODE_OFFICIAL) ? MODE_OFF : mode;
        prefs.edit().putInt(KEY_MODE, normalized).apply();
    }

    // ------------------------------------------------------------------ 个人 Key

    /**
     * 读取个人 Key；没有/解密失败返回空串（解密失败说明 Key 已被系统清理，需要用户重新填写）。
     * 任何情况下都不会抛异常。
     */
    public String personalKey() {
        String stored = prefs.getString(KEY_PERSONAL_KEY, "");
        return decrypt(stored);
    }

    /** 保存个人 Key。空值等价于删除。 */
    public void setPersonalKey(String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_PERSONAL_KEY).apply();
            return;
        }
        prefs.edit().putString(KEY_PERSONAL_KEY, encrypt(value)).apply();
    }

    public boolean hasPersonalKey() {
        return !personalKey().isEmpty();
    }

    /**
     * 是否为「看起来像 DeepSeek Key」的格式：{@code sk-} 前缀且足够长。
     *
     * <p>只作为输入提示，不作为硬性拦截：用户可能使用代理服务提供的自定义格式 Key，
     * 界面应提示格式不常见但允许继续保存。
     */
    public static boolean looksLikeKey(String key) {
        if (key == null) {
            return false;
        }
        String value = key.trim();
        return value.length() > 16 && value.startsWith("sk-");
    }

    // ------------------------------------------------------------------ 官方通道

    /** 开发者官方 Key（构建时注入的占位常量；未注入时为空串）。 */
    public String officialKey() {
        String key = OfficialKey.DEVELOPER_KEY;
        return key == null ? "" : key.trim();
    }

    /**
     * 用口令解锁官方通道。口令大小写不敏感、两侧空白忽略。
     *
     * <p>注意：即使本安装包没有注入开发者 Key（{@link #officialKey()} 为空），口令正确也返回
     * true —— 是否可用交给调用方根据 officialKey() 判断并提示，避免把「包没配置」误报成
     * 「口令错误」。
     */
    public boolean unlockOfficial(String password) {
        String value = password == null ? "" : password.trim();
        if (!OFFICIAL_PASSWORD.equalsIgnoreCase(value)) {
            return false;
        }
        setOfficialUnlocked(true);
        setMode(MODE_OFFICIAL);
        return true;
    }

    public boolean officialUnlocked() {
        return prefs.getBoolean(KEY_OFFICIAL_UNLOCKED, false);
    }

    public void setOfficialUnlocked(boolean unlocked) {
        prefs.edit().putBoolean(KEY_OFFICIAL_UNLOCKED, unlocked).apply();
    }

    /**
     * 当前通道实际可用的 Key；未配置/未解锁/模式关闭均返回空串。
     * 官方通道额外要求已解锁，防止开发者 Key 被未解锁的界面误用。
     */
    public String activeKey() {
        int mode = mode();
        if (mode == MODE_PERSONAL) {
            return personalKey();
        }
        if (mode == MODE_OFFICIAL) {
            return officialUnlocked() ? officialKey() : "";
        }
        return "";
    }

    // ------------------------------------------------------------------ 模型 / 地址

    /** 当前模型：{@link DeepSeekClient#MODEL_FLASH}（默认）或 {@link DeepSeekClient#MODEL_PRO}。 */
    public String model() {
        String value = prefs.getString(KEY_MODEL, DeepSeekClient.MODEL_FLASH);
        if (DeepSeekClient.MODEL_PRO.equals(value)) {
            return DeepSeekClient.MODEL_PRO;
        }
        // 空值或历史脏数据一律回退到支持看图、延迟更低的 flash。
        return DeepSeekClient.MODEL_FLASH;
    }

    public void setModel(String model) {
        String value = DeepSeekClient.MODEL_PRO.equals(model)
                ? DeepSeekClient.MODEL_PRO : DeepSeekClient.MODEL_FLASH;
        prefs.edit().putString(KEY_MODEL, value).apply();
    }

    public String baseUrl() {
        String value = prefs.getString(KEY_BASE_URL, DeepSeekClient.DEFAULT_BASE_URL);
        if (value == null || value.trim().isEmpty()) {
            return DeepSeekClient.DEFAULT_BASE_URL;
        }
        return value.trim();
    }

    public void setBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_BASE_URL).apply();
        } else {
            prefs.edit().putString(KEY_BASE_URL, value).apply();
        }
    }

    // ------------------------------------------------------------------ 语音 / 搭子人格

    /** AI 音乐搭子语音播报总开关：默认关闭，需要时在聊天页打开（避免一进聊天就申请麦克风）。 */
    public boolean voiceEnabled() {
        return prefs.getBoolean(KEY_VOICE_ENABLED, false);
    }

    public void setVoiceEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply();
    }

    /** 当前搭子人格 id；默认 sunny（与 CompanionPersona 的预设 id 对齐）。 */
    public String companionPersonaId() {
        String value = prefs.getString(KEY_COMPANION_PERSONA, "sunny");
        if (value == null || value.trim().isEmpty()) {
            return "sunny";
        }
        return value.trim();
    }

    public void setCompanionPersonaId(String personaId) {
        String value = personaId == null ? "" : personaId.trim();
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_COMPANION_PERSONA).apply();
        } else {
            prefs.edit().putString(KEY_COMPANION_PERSONA, value).apply();
        }
    }

    // ------------------------------------------------------------------ Keystore 加密

    private String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }
        try {
            SecretKey secretKey = keystoreKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length == 0) {
                throw new IllegalStateException("GCM IV 缺失");
            }
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] bundle = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, bundle, 0, iv.length);
            System.arraycopy(cipherText, 0, bundle, iv.length, cipherText.length);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(bundle);
        } catch (Throwable t) {
            // 降级：个别设备 Keystore 异常（密钥库损坏、模拟器无硬件支持等）不能挡住用户用 AI。
            // 明文前缀让读取侧能区分「加密失败降级」与「加密数据损坏」，且下次读取仍可用。
            return PLAIN_PREFIX + plainText;
        }
    }

    private String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            return "";
        }
        try {
            if (storedValue.startsWith(PLAIN_PREFIX)) {
                return storedValue.substring(PLAIN_PREFIX.length());
            }
            if (!storedValue.startsWith(ENCRYPTED_PREFIX)) {
                // 兼容早期版本/测试环境直接写入的裸值。
                return storedValue;
            }
            byte[] bundle = Base64.getDecoder().decode(storedValue.substring(ENCRYPTED_PREFIX.length()));
            if (bundle.length <= GCM_IV_BYTES) {
                return "";
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, bundle, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(bundle, GCM_IV_BYTES, bundle.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 解密失败通常意味着密钥被系统重置（如恢复出厂/换机备份）或数据损坏；
            // 返回空串让界面提示重新输入，绝不让异常冒到 UI。
            return "";
        }
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 256 位在 Android 7+ 全平台可用；若某设备不支持会抛异常走明文降级。
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
