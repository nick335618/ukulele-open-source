package com.ukulele.monitor.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 搭子的语音通道：系统识别与离线 Vosk 中文识别回退 + TextToSpeech（中文朗读）。
 *
 * <p>系统可能只暴露占位识别服务；本机 Vosk 模型补足这一情况。录音权限由 UI 的实际授权回调处理，
 * 模型解包、加载和解码在后台运行，音频不上传；AI 聊天仍沿用用户选择的网络通道。
 *
 * <p>v2 的「更连贯、带点感情」是这么做的：
 * <ul>
 *   <li>朗读前先清洗文本（去掉 Markdown/emoji/URL/行为标签/奇怪符号），再按句切分、
 *       合并成约 30~120 字的朗读块，用 {@link TextToSpeech#QUEUE_ADD} 连续入队，
 *       而不是一句一停——块间几乎无缝，听感不「一顿一顿」。</li>
 *   <li>按人格选择中文 voice（优先本地高质量 zh-CN），并用
 *       {@link #setEmotionProfile(String)} 给每个预设一套基础音调/语速；
 *       具体到每一块再按标点微调：感叹句音调↑、语速↑，疑问句音调↑、语速略降，陈述句保持。</li>
 *   <li>整段读完回调 {@link Listener#onSpeechFinished()}；连续对话模式下 UI 可据此自动重新开麦
 *       （VoiceChatManager 只暴露状态和回调，不自动开麦，避免权限问题）。</li>
 * </ul>
 *
 * <p>约定：
 * <ul>
 *   <li>所有方法主线程调用；内部若发现不在主线程会 post 回主线程，回调也一律主线程。</li>
 *   <li>无麦克风权限时不主动申请，直接回调 {@link Listener#onSpeechError(String)}
 *       「需要麦克风权限」，由 UI 决定是否 requestPermissions。</li>
 *   <li>TTS 不支持中文时返回明确状态，保留文字回复并退出连续开麦。</li>
 *   <li>{@link #release()} 幂等；Activity 已销毁后的迟到回调全部丢弃，不会崩。</li>
 * </ul>
 */
public final class VoiceChatManager {

    /** 语音事件回调；全部主线程。 */
    public interface Listener {
        /** 已经开始收音。 */
        void onListening();

        default void onPreparing(String message) { }
        default void onPlaybackError(String message) { }

        /** 识别中的临时结果（可能多次）。 */
        void onPartial(String text);

        /** 最终识别结果（此后本次识别结束）。 */
        void onFinal(String text);

        /** 语音识别错误（中文可读消息）。 */
        void onSpeechError(String message);

        /** TTS 开始/结束朗读；UI 可据此显示「正在说」状态。 */
        void onSpeaking(boolean speaking);

        /**
         * 整段朗读结束（自然读完/播报出错，各块都排空后回调一次）。
         * 新增为 default 方法，旧的 Listener 实现不用改也能编译。
         */
        default void onSpeechFinished() {
        }
    }

    private static final String TAG_UTTERANCE_PREFIX = "uk_companion_";

    /** 朗读块目标长度：30~120 字。太短会频繁起停，太长不好换气。 */
    static final int SPEECH_CHUNK_MIN = 30;
    static final int SPEECH_CHUNK_MAX = 120;

    /** 各人格的基础音调/音速（与 CompanionPersona 的 voicePitch/voiceRate 同一套审美）。 */
    private static final String[] PROFILE_IDS = {"sunny", "gentle", "strict", "prof", "rocker"};
    private static final float[][] PROFILE_VOICES = {
            {1.08f, 1.05f}, // sunny：偏亮、稍快
            {0.98f, 0.92f}, // gentle：柔和、偏慢
            {1.00f, 1.08f}, // strict：干脆、偏快
            {0.96f, 0.95f}, // prof：沉稳、稍慢
            {1.02f, 1.12f}, // rocker：亮、快
    };

    private final Activity activity;
    private final Handler main;
    private Listener listener;

    private SpeechRecognizer recognizer;
    private boolean listening;
    private int recognitionToken;
    private boolean systemReady;
    private boolean offlineBackend;
    private OfflineVoiceRecognizer offline;
    private Runnable recognitionDeadline;
    private Runnable readyDeadline;

    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private boolean ttsInitAttempted;
    private String pendingSpeak;
    /** 本次朗读的最后一块 utterance id：只有它结束才回调 onSpeechFinished。 */
    private String lastUtteranceId;
    private int speakToken;
    private boolean speaking;
    private boolean continuousMode;

    /** 基础音调/语速（人格基线）。逐块的感叹/疑问微调在 speak 时叠加。 */
    private float pitch = 1f;
    private float rate = 1f;
    /** 当前情感档案 ID（五预设之一；自定义人格为空串，沿用 setVoice 的基线）。 */
    private String emotionProfile = "";

    private volatile boolean released;

    public VoiceChatManager(Activity activity) {
        this.activity = activity;
        this.main = new Handler(Looper.getMainLooper());
        initTts();
    }

    // ------------------------------------------------------------ 能力探测

    /** 设备是否有可用的语音识别服务。 */
    public static boolean canRecognize(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return SpeechRecognizer.isRecognitionAvailable(context) || OfflineVoiceRecognizer.available(context);
        } catch (Throwable ignored) {
            return OfflineVoiceRecognizer.available(context);
        }
    }

    /** 设备是否装了 TTS 引擎（不保证中文语音包）。 */
    public static boolean canSpeak(Context context) {
        if (context == null) {
            return false;
        }
        try {
            // 用包管理器查 TTS 服务，而不是 new TextToSpeech（构造会拉起引擎服务，探测成本太高）
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
            return services != null && !services.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ 语音识别

    /** Listener is installed before TTS or ASR, including the initial greeting. */
    public void setListener(Listener listener) { this.listener = listener; }
    public void start() { start(listener); }

    /** Each request owns its callbacks; cancelled generations cannot alter the next request. */
    public void start(Listener listener) {
        if (!isMainThread()) { main.post(() -> start(listener)); return; }
        this.listener = listener;
        if (listener == null || released || !uiAlive()) return;
        if (!hasRecordPermission()) { dispatchSpeechError("需要麦克风权限"); return; }
        silence();
        stopListeningInternal();
        final int token = recognitionToken;
        listening = true;
        systemReady = false;
        recognitionDeadline = () -> {
            if (listening && recognitionToken == token) {
                stopListeningInternal();
                dispatchSpeechError("没有收到语音结果，请重新点击麦克风");
            }
        };
        main.postDelayed(recognitionDeadline, 45000L);
        boolean system = false;
        try {
            String component = Settings.Secure.getString(activity.getContentResolver(), "voice_recognition_service");
            system = SpeechRecognizer.isRecognitionAvailable(activity)
                    && (component == null || !component.toLowerCase(Locale.ROOT).contains("fakerecognition"));
        } catch (RuntimeException ignored) { }
        if (!system) { startOffline(token); return; }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(recognitionListener(token));
            listener.onPreparing("正在连接系统语音…");
            recognizer.startListening(buildRecognizeIntent());
            readyDeadline = () -> {
                if (listening && recognitionToken == token && !systemReady && !offlineBackend) {
                    destroySystemRecognizer();
                    startOffline(token);
                }
            };
            main.postDelayed(readyDeadline, 4000L);
        } catch (RuntimeException error) {
            destroySystemRecognizer();
            startOffline(token);
        }
    }

    private void startOffline(final int token) {
        if (released || !listening || token != recognitionToken || offlineBackend) return;
        if (readyDeadline != null) main.removeCallbacks(readyDeadline);
        readyDeadline = null;
        // Attempt the bundled decoder directly; a service/asset capability probe is not a result.
        offlineBackend = true;
        android.util.Log.i("UkuleleVoice", "using offline Mandarin recognizer");
        if (offline == null) offline = new OfflineVoiceRecognizer(activity);
        offline.start(new OfflineVoiceRecognizer.Callback() {
            private boolean valid() { return !released && listening && recognitionToken == token; }
            @Override public void preparing(String text) { if (valid() && listener != null) listener.onPreparing(text); }
            @Override public void ready() { if (valid()) dispatchListening(); }
            @Override public void partial(String text) { if (valid()) dispatchPartial(text); }
            @Override public void result(String text) {
                if (!valid()) return;
                stopListeningInternal();
                dispatchFinal(text);
            }
            @Override public void error(String text) {
                if (!valid()) return;
                stopListeningInternal();
                dispatchSpeechError(text);
            }
        });
    }

    /** Finish a spoken sentence and retain its result; cancel is reserved for closing/editing. */
    public void finishListening() {
        if (!isMainThread()) { main.post(this::finishListening); return; }
        if (!listening) return;
        if (offlineBackend && offline != null) offline.finish();
        else if (recognizer != null) {
            try { recognizer.stopListening(); }
            catch (RuntimeException error) { handleRecognizerError(SpeechRecognizer.ERROR_CLIENT); }
        }
    }

    /** 停止语音识别（用户主动停止/弹窗关闭时调用）。 */
    public void stop() {
        if (!isMainThread()) {
            main.post(this::stop);
            return;
        }
        stopListeningInternal();
    }

    /** 是否正在收音。 */
    public boolean isListening() {
        return listening && !released;
    }

    // ------------------------------------------------------------ TTS

    /**
     * 设置情感档案（sunny/gentle/strict/prof/rocker），决定基础音调/语速。
     * 未知 ID（例如用户自定义人格）不覆盖基础值，只清空档案 ID——调用方仍可用
     * {@link #setVoice(float, float)} 自己给基线。主线程调用。
     */
    public void setEmotionProfile(String personaId) {
        if (!isMainThread()) {
            final String value = personaId;
            main.post(() -> setEmotionProfile(value));
            return;
        }
        if (released) {
            return;
        }
        float[] base = profileVoice(personaId);
        if (base == null) {
            emotionProfile = "";
            return;
        }
        emotionProfile = normalizeProfileId(personaId);
        pitch = base[0];
        rate = base[1];
        applyBaseVoiceToTts();
    }

    /** 当前是否在朗读（含排队中的块）。 */
    public boolean isSpeaking() {
        return speaking && !released;
    }

    /** 连续对话模式状态：只做状态位 + onSpeechFinished 回调，不自动开麦（由 UI 决定）。 */
    public void setContinuousMode(boolean enabled) {
        if (!isMainThread()) {
            final boolean value = enabled;
            main.post(() -> setContinuousMode(value));
            return;
        }
        continuousMode = enabled;
    }

    /** 连续对话模式是否开启。 */
    public boolean continuousMode() {
        return continuousMode;
    }

    /**
     * 朗读一段文字。
     *
     * <p>v2 行为：先停下上一段朗读，清洗文本后按句合并成 30~120 字的块，用 QUEUE_ADD 连续入队，
     * 每块按句末标点做音调/语速微调；整段最后一块读完回调 {@link Listener#onSpeechFinished()}。
     * TTS 不可用时静默降级。
     */
    public void speak(String text) {
        if (!isMainThread()) {
            final String value = text;
            main.post(() -> speak(value));
            return;
        }
        if (released) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String value = text.trim();
        if (tts == null) {
            if (!ttsInitAttempted) {
                initTts();
            }
            if (tts == null) {
                // 没有 TTS 引擎：静默降级，但通知 UI 恢复「不在朗读」状态
                dispatchSpeaking(false);
                dispatchPlaybackError("系统中文朗读服务不可用，仍可阅读文字回复");
                return;
            }
        }
        if (ttsReady) {
            doSpeakAll(value);
        } else if (ttsFailed) {
            dispatchSpeaking(false);
            dispatchPlaybackError("系统中文朗读服务不可用，仍可阅读文字回复");
        } else {
            // 初始化中：等 onInit 回调后再读，避免丢掉这句回复（后来的 speak 覆盖前面的）
            pendingSpeak = value;
        }
    }

    /** 立刻停止朗读（不回调 onSpeechFinished：用户主动打断不该触发连续对话重开）。 */
    public void silence() {
        if (!isMainThread()) {
            main.post(this::silence);
            return;
        }
        pendingSpeak = null;
        lastUtteranceId = null;
        speakToken++; // 让仍在途的 onDone 回调失效
        try {
            if (tts != null) {
                tts.stop();
            }
        } catch (Throwable ignored) {
        }
        dispatchSpeaking(false);
    }

    /** 设置基础音调/语速（0.5~2.0，越界夹紧）；下一块生效。会覆盖情感档案的基线。 */
    public void setVoice(float pitch, float rate) {
        if (!isMainThread()) {
            final float p = pitch;
            final float r = rate;
            main.post(() -> setVoice(p, r));
            return;
        }
        this.pitch = clampVoice(pitch, this.pitch);
        this.rate = clampVoice(rate, this.rate);
        applyBaseVoiceToTts();
    }

    /**
     * 释放全部语音资源；幂等。之后所有方法都会静默无操作/直接丢弃回调。
     * UI 在 Dialog dismiss / Activity onDestroy 时调用。
     */
    public void release() {
        if (!isMainThread()) {
            main.post(this::release);
            return;
        }
        if (released) {
            return;
        }
        released = true;
        listener = null;
        pendingSpeak = null;
        lastUtteranceId = null;
        speakToken++;
        speaking = false;
        listening = false;
        stopListeningInternal();
        if (offline != null) { offline.release(); offline = null; }
        shutdownTts();
        try {
            main.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 识别内部实现

    private Intent buildRecognizeIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        } catch (Throwable ignored) {
        }
        return intent;
    }

    private boolean hasRecordPermission() {
        try {
            return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void stopListeningInternal() {
        recognitionToken++;
        listening = false;
        offlineBackend = false;
        if (recognitionDeadline != null) main.removeCallbacks(recognitionDeadline);
        if (readyDeadline != null) main.removeCallbacks(readyDeadline);
        recognitionDeadline = null;
        readyDeadline = null;
        if (offline != null) offline.cancel();
        destroySystemRecognizer();
    }

    private void destroySystemRecognizer() {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    private String extractText(Bundle data) {
        if (data == null) {
            return "";
        }
        try {
            ArrayList<String> results = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (results == null) {
                return "";
            }
            for (String item : results) {
                if (item != null && !item.trim().isEmpty()) {
                    return item.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void handleRecognizerError(int error) {
        stopListeningInternal();
        dispatchSpeechError(errorMessage(error));
    }

    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音出错，检查一下麦克风是否被占用";
            case SpeechRecognizer.ERROR_CLIENT:
                return "语音识别被中断，再试一次？";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "需要麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音识别网络异常，检查网络后重试";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "没听清，再说一次？";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别正忙，稍后再试";
            case SpeechRecognizer.ERROR_SERVER:
                return "语音识别服务暂时不可用";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有听到声音，先点一下麦克风再说话";
            default:
                return "语音识别失败（错误码 " + error + "）";
        }
    }

    private RecognitionListener recognitionListener(final int token) {
        return new RecognitionListener() {
            private boolean valid() { return !released && listening && recognitionToken == token && !offlineBackend && recognizer != null; }
            @Override public void onReadyForSpeech(Bundle params) {
                if (!valid()) return;
                systemReady = true;
                if (readyDeadline != null) main.removeCallbacks(readyDeadline);
                dispatchListening();
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                if (!valid()) return;
                // A service can exist without actually implementing third-party recognition.
                if (!systemReady || error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == 12 || error == 13) {
                    destroySystemRecognizer();
                    startOffline(token);
                } else handleRecognizerError(error);
            }
            @Override public void onResults(Bundle results) {
                if (!valid()) return;
                String text = extractText(results);
                stopListeningInternal();
                if (text.isEmpty()) dispatchSpeechError("没听清，再说一次？");
                else dispatchFinal(text);
            }
            @Override public void onPartialResults(Bundle results) {
                if (valid()) { String text = extractText(results); if (!text.isEmpty()) dispatchPartial(text); }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        };
    }

    // ------------------------------------------------------------ TTS 内部实现

    private void initTts() {
        if (ttsInitAttempted || released) {
            return;
        }
        ttsInitAttempted = true;
        try {
            Context context = activity == null ? null : activity.getApplicationContext();
            if (context == null) {
                ttsFailed = true;
                return;
            }
            tts = new TextToSpeech(context, status -> main.post(() -> handleTtsInit(status)));
        } catch (Throwable ignored) {
            tts = null;
            ttsFailed = true;
        }
    }

    private void handleTtsInit(int status) {
        if (released) {
            return;
        }
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsFailed = true;
            ttsReady = false;
            dispatchPlaybackError("系统中文朗读服务不可用，仍可阅读文字回复");
            if (pendingSpeak != null) {
                pendingSpeak = null;
                dispatchSpeaking(false);
            }
            return;
        }
        if (!chooseChineseVoice()) {
            ttsFailed = true;
            pendingSpeak = null;
            dispatchPlaybackError("系统缺少中文朗读语音，请在系统文字转语音设置中安装中文语音");
            return;
        }
        try {
            tts.setOnUtteranceProgressListener(utteranceListener);
        } catch (Throwable ignored) {
        }
        applyBaseVoiceToTts();
        ttsReady = true;
        if (pendingSpeak != null) {
            String text = pendingSpeak;
            pendingSpeak = null;
            doSpeakAll(text);
        }
    }

    /**
     * 优先挑中文 voice：zh 语言里优先本地、高质量、CN 地区的；都不行再退回 setLanguage，
     * 仍然不行就保持引擎默认（可能读英文，但不至于完全不响）。全程异常兜底。
     */
    private boolean chooseChineseVoice() {
        TextToSpeech engine = tts;
        if (engine == null) {
            return false;
        }
        try {
            Set<Voice> voices = engine.getVoices();
            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            if (voices != null) {
                for (Voice voice : voices) {
                    if (voice == null) {
                        continue;
                    }
                    Locale locale = voice.getLocale();
                    if (locale == null || !"zh".equalsIgnoreCase(locale.getLanguage())) {
                        continue;
                    }
                    int score = voice.getQuality();
                    if (!voice.isNetworkConnectionRequired()) {
                        score += 10000; // 本地语音优先：离线可用、延迟更低
                    }
                    if ("CN".equalsIgnoreCase(locale.getCountry())) {
                        score += 1000;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = voice;
                    }
                }
            }
            if (best != null && engine.setVoice(best) == TextToSpeech.SUCCESS) {
                return true;
            }
        } catch (Throwable ignored) {
            // 某些引擎 getVoices/setVoice 会抛异常，继续走 setLanguage 兜底
        }
        try {
            int result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = engine.setLanguage(Locale.CHINESE);
            }
            return result >= TextToSpeech.LANG_AVAILABLE;
        } catch (Throwable ignored) { return false; }
    }

    /** 基础音调/语速应用到引擎（每次 speak 前会再按块微调）。 */
    private void applyBaseVoiceToTts() {
        TextToSpeech engine = tts;
        if (engine == null || !ttsReady) {
            return;
        }
        try {
            engine.setPitch(pitch);
            engine.setSpeechRate(rate);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 真正把文本排进 TTS 队列：清洗 -> 分句 -> 合并 30~120 字块 -> 每块按句末标点微调音调/语速
     * -> QUEUE_ADD 连续入队。第一块用 QUEUE_FLUSH 清掉 stop 之后可能残留的队列。
     */
    private void doSpeakAll(String text) {
        TextToSpeech engine = tts;
        if (engine == null || !ttsReady || released) {
            dispatchSpeaking(false);
            return;
        }
        List<String> chunks = buildSpeechChunks(text);
        if (chunks.isEmpty()) {
            dispatchSpeaking(false);
            dispatchSpeechFinished();
            return;
        }
        int token = ++speakToken;
        lastUtteranceId = null;
        try {
            engine.stop();
        } catch (Throwable ignored) {
        }
        int queued = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (released) {
                break;
            }
            String chunk = chunks.get(i);
            float[] voice = emotionVoice(emotionProfile, chunk, i, pitch, rate);
            try {
                engine.setPitch(voice[0]);
                engine.setSpeechRate(voice[1]);
            } catch (Throwable ignored) {
            }
            String utteranceId = TAG_UTTERANCE_PREFIX + token + "_" + i;
            int result;
            try {
                result = engine.speak(chunk, i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        null, utteranceId);
            } catch (Throwable ignored) {
                result = TextToSpeech.ERROR;
            }
            if (result == TextToSpeech.SUCCESS) {
                queued++;
                lastUtteranceId = utteranceId;
            } else if (queued == 0) {
                break; // 第一块就失败：整段放弃
            }
        }
        if (queued == 0) {
            lastUtteranceId = null;
            dispatchSpeaking(false);
            dispatchPlaybackError("中文朗读未能启动，文字回复已保留");
            return;
        }
        dispatchSpeaking(true);
    }

    private void shutdownTts() {
        TextToSpeech engine = tts;
        tts = null;
        ttsReady = false;
        if (engine == null) {
            return;
        }
        try {
            engine.stop();
        } catch (Throwable ignored) {
        }
        try {
            engine.shutdown();
        } catch (Throwable ignored) {
        }
    }

    /** 单参 onError 在 API 21 起标记 deprecated，但仍是抽象方法，必须实现（双参版本转发到它）。 */
    @SuppressWarnings("deprecation")
    private final UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            // 朗读开始的状态在 speak() 成功入队时已经回调，不重复
        }

        @Override
        public void onDone(String utteranceId) {
            postUtteranceFinished(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            main.post(() -> {
                if (!released && utteranceId != null && utteranceId.equals(lastUtteranceId)) {
                    lastUtteranceId = null;
                    dispatchSpeaking(false);
                    dispatchPlaybackError("中文朗读失败，请检查系统语音设置");
                }
            });
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            main.post(() -> {
                if (!released && utteranceId != null && utteranceId.equals(lastUtteranceId)) {
                    lastUtteranceId = null;
                    dispatchSpeaking(false);
                    dispatchPlaybackError("朗读已中断，可重新点击麦克风继续");
                }
            });
        }

        private void postUtteranceFinished(final String utteranceId) {
            main.post(() -> handleUtteranceFinished(utteranceId));
        }
    };

    /** 只有「本段最后一块」结束才算整段读完；中间块或旧 generation 的回调一律忽略。 */
    private void handleUtteranceFinished(String utteranceId) {
        if (released || utteranceId == null) {
            return;
        }
        if (lastUtteranceId == null || !lastUtteranceId.equals(utteranceId)) {
            return;
        }
        lastUtteranceId = null;
        dispatchSpeaking(false);
        dispatchSpeechFinished();
    }

    // ------------------------------------------------------------ 文本清洗 / 分句 / 情感（包内可见，JVM 可纯测）

    /**
     * 清洗成适合 TTS 朗读的纯文本：去掉行为标签、Markdown、URL、代码、emoji 和奇怪符号，
     * 换行折算成句号；保留中英文、数字和用于停顿的标点。
     */
    static String cleanSpeechText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String t = raw;
        try {
            t = t.replaceAll("\\[\\[[\\s\\S]*?\\]\\]", " ");
            t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            t = t.replaceAll("(?i)https?://\\S+", " ");
            t = t.replaceAll("(?i)www\\.\\S+", " ");
            t = t.replaceAll("```[\\s\\S]*?```", " ");
            t = t.replace("`", "");
            t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
            t = t.replaceAll("(?m)^\\s{0,3}>\\s?", "");
            t = t.replaceAll("(?m)^\\s*[*+\\-]\\s+", "");
            t = t.replaceAll("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$", "");
            t = t.replace("**", "").replace("__", "").replace("*", "");
            t = t.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n+", "。");
        } catch (Throwable ignored) {
            // 正则出问题就退回原文继续过滤，显示/朗读比崩溃重要
        }
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                continue; // emoji 等补充平面字符：整对丢弃
            }
            if (Character.isLetter(c) || Character.isDigit(c)) {
                sb.append(c);
                continue;
            }
            if (c == ' ' || c == '\t') {
                sb.append(' ');
                continue;
            }
            // 只保留有助于停顿/语气的标点；括号、运算符等一律去掉（括号里的文字保留）
            if ("，。！？；：、…—～,.!?;:'\"“”‘’「」".indexOf(c) >= 0) {
                sb.append(c);
            }
        }
        String out = sb.toString();
        try {
            out = out.replaceAll("\\s+", " ").trim();
            out = out.replaceAll("([，,]){2,}", "，");
            out = out.replaceAll("([。！？；])\\1+", "$1");
            out = out.replaceAll("…+", "…");
            out = out.replaceAll("^[，。！？；：、,.!?;:]+", "");
        } catch (Throwable ignored) {
            // ignore
        }
        return out.trim();
    }

    /**
     * 清洗 + 分句 + 合并朗读块：切成句子后贪心合并到不超过 {@link #SPEECH_CHUNK_MAX} 字，
     * 尽量减少块数（块越少，QUEUE_ADD 播放时越连贯）；超长句先在逗号处切开。
     */
    static List<String> buildSpeechChunks(String raw) {
        List<String> out = new ArrayList<>();
        String clean = cleanSpeechText(raw);
        if (clean.isEmpty()) {
            return out;
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '…') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        List<String> pieces = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.length() <= SPEECH_CHUNK_MAX) {
                pieces.add(sentence);
            } else {
                pieces.addAll(splitLongSentence(sentence));
            }
        }
        StringBuilder chunk = new StringBuilder();
        for (String piece : pieces) {
            if (chunk.length() > 0 && chunk.length() + piece.length() > SPEECH_CHUNK_MAX) {
                out.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(piece);
        }
        if (chunk.length() > 0) {
            out.add(chunk.toString());
        }
        return out;
    }

    /** 超长句在逗号/顿号处切成 ≤120 字的片段；找不到合适标点就硬切。 */
    private static List<String> splitLongSentence(String sentence) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + SPEECH_CHUNK_MAX, sentence.length());
            if (end < sentence.length()) {
                int cut = -1;
                int minCut = start + SPEECH_CHUNK_MIN;
                for (int i = end - 1; i >= minCut && i > start; i--) {
                    if ("，、：,;；".indexOf(sentence.charAt(i)) >= 0) {
                        cut = i + 1;
                        break;
                    }
                }
                if (cut > start) {
                    end = cut;
                }
            }
            parts.add(sentence.substring(start, end));
            start = end;
        }
        return parts;
    }

    /** 已知人格的情感基线 {pitch, rate}；未知返回 null。 */
    static float[] profileVoice(String personaId) {
        String id = normalizeProfileId(personaId);
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(id)) {
                return new float[]{PROFILE_VOICES[i][0], PROFILE_VOICES[i][1]};
            }
        }
        return null;
    }

    /**
     * 单个朗读块的音调/语速：在人格基线上按句末标点做微调（需求给的幅度）：
     * 感叹句 pitch ×1.05（+5%）、rate ×1.06（+6%）；疑问句 pitch ×1.06（+6%）、rate ×0.98（-2%）；
     * 陈述句保持；结果统一夹到 0.5~2.0，避免破音和音量突变。
     */
    static float[] emotionVoice(String personaId, String chunk, int index, float basePitch, float baseRate) {
        float p = clampVoice(basePitch, 1f);
        float r = clampVoice(baseRate, 1f);
        char last = lastMeaningfulChar(chunk);
        if (last == '！' || last == '!') {
            p *= 1.05f;
            r *= 1.06f;
        } else if (last == '？' || last == '?') {
            p *= 1.06f;
            r *= 0.98f;
        }
        return new float[]{clampVoice(p, 1f), clampVoice(r, 1f)};
    }

    private static char lastMeaningfulChar(String text) {
        if (text == null) {
            return 0;
        }
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return 0;
    }

    private static String normalizeProfileId(String personaId) {
        return personaId == null ? "" : personaId.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 回调分发（全部主线程）

    private void dispatchListening() {
        Listener target = listener;
        if (target == null || !uiAlive()) {
            return;
        }
        try {
            target.onListening();
        } catch (Throwable ignored) {
        }
    }

    private void dispatchPartial(String text) {
        Listener target = listener;
        if (target == null || !uiAlive()) {
            return;
        }
        try {
            target.onPartial(text);
        } catch (Throwable ignored) {
        }
    }

    private void dispatchFinal(String text) {
        Listener target = listener;
        if (target == null || !uiAlive()) {
            return;
        }
        try {
            target.onFinal(text);
        } catch (Throwable ignored) {
        }
    }

    private void dispatchSpeechError(String message) {
        Listener target = listener;
        if (target == null || !uiAlive()) {
            return;
        }
        try {
            target.onSpeechError(message);
        } catch (Throwable ignored) {
        }
    }

    private void dispatchPlaybackError(String message) {
        if (listener != null && uiAlive()) listener.onPlaybackError(message);
    }

    /** 朗读状态只在真正变化时回调，避免 UI 被重复事件刷屏。 */
    private void dispatchSpeaking(boolean value) {
        boolean changed = speaking != value;
        speaking = value;
        Listener target = listener;
        if (!changed || target == null || !uiAlive()) {
            return;
        }
        try {
            target.onSpeaking(value);
        } catch (Throwable ignored) {
        }
    }

    /** 整段朗读结束回调；Activity 已销毁/已释放则丢弃。 */
    private void dispatchSpeechFinished() {
        Listener target = listener;
        if (target == null || !uiAlive()) {
            return;
        }
        try {
            target.onSpeechFinished();
        } catch (Throwable ignored) {
        }
    }

    /** Activity 正在销毁/已销毁时丢弃回调，避免 Dialog 关闭后更新已死界面。 */
    private boolean uiAlive() {
        if (released) {
            return false;
        }
        Activity a = activity;
        if (a == null) {
            return false;
        }
        try {
            return !a.isFinishing() && !a.isDestroyed();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------ 工具

    private static boolean isMainThread() {
        try {
            return Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static float clampVoice(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < 0.5f) {
            return 0.5f;
        }
        return value > 2f ? 2f : value;
    }
}
