package com.ukulele.monitor.ai;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * DeepSeek API 客户端（纯平台 API，无第三方库）。
 *
 * <p><b>职责：</b>对话（含思考模式开关）、多模态识图、联网搜索、网页抓取、连接测试。
 * 所有网络 IO 都在后台线程执行，结果通过主线程 {@link Handler} 回调（每个请求恰好回调一次）。
 * 密钥只放在 Authorization 头里，<b>任何日志/异常消息都不会打印完整 Key</b>。
 *
 * <p><b>为什么默认关闭思考：</b>实测 2026 版 DeepSeek 默认为思考模式（响应里带
 * {@code reasoning_content}），一个简单对话也要 1 秒多；而且思考 token 会占用
 * {@code max_tokens}，小额度时 content 可能为空。对话/问答这类低时延需求显式传
 * {@code "thinking":{"type":"disabled"}}；曲谱转换需要推理时用 {@link #setThinkingEnabled(boolean)}
 * 或带 thinking 参数的重载打开，并给足 max_tokens。
 *
 * <p><b>为什么用 java.util.Base64：</b>{@code android.util.Base64} 在桌面 JVM 上只有桩实现，
 * 用它会让本类无法在 JVM 上做真实接口联调；minSdk 26 起已自带 {@code java.util.Base64}。
 */
public class DeepSeekClient {

    /** 官方接口根地址。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** 支持图像理解、低时延，默认模型。 */
    public static final String MODEL_FLASH = "deepseek-flash";
    /** 纯文本推理模型，不支持看图。 */
    public static final String MODEL_PRO = "deepseek-v4-pro";

    private static final String CHAT_PATH = "/chat/completions";
    private static final String SEARCH_PATH = "/v1/tools/search";
    private static final String FETCH_PATH = "/v1/tools/fetch";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 90000;
    /** 请求体上限：超大图片（base64 膨胀 4/3）在这里提前拦下，避免把内存撑爆。 */
    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    /** 输出 token 上限：接口本身允许很大，这里收口防止误传超大值造成高额消耗。 */
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int DEFAULT_MAX_TOKENS = 1024;

    // ------------------------------------------------------------------ 数据结构

    /** 统一的错误对象。message 一律是可直接展示的中文。 */
    public static final class AiError {
        /** HTTP 状态码；网络层错误（超时/断网/无 Key）为 0。 */
        public final int httpCode;
        /** 机器可读的错误码，例如 invalid_key / insufficient_balance / rate_limited。 */
        public final String code;
        /** 面向用户的中文错误描述。 */
        public final String message;
        /** 是否值得稍后重试（限流、5xx、超时、断网为 true）。 */
        public final boolean retryable;

        public AiError(int httpCode, String code, String message, boolean retryable) {
            this.httpCode = httpCode;
            this.code = code == null ? "" : code;
            this.message = message == null ? "AI 请求失败" : message;
            this.retryable = retryable;
        }

        @Override
        public String toString() {
            return "AiError{httpCode=" + httpCode + ", code=" + code + ", retryable=" + retryable + ", message=" + message + "}";
        }
    }

    /** 一条对话消息。role 只使用 user / assistant / system。 */
    public static final class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }
    }

    /** 对话结果回调。两个方法都保证在主线程调用，且每个请求只会触发其中一个一次。 */
    public interface ChatCallback {
        void onSuccess(String text);

        void onError(AiError error);
    }

    /** 联网搜索结果条目。 */
    public static final class SearchResult {
        public final String title;
        public final String url;
        public final String snippet;
        public final String summary;
        public final String content;
        public final String date;
        /** 权威度，接口给数字（越高越权威）；也兼容 high/medium/low 这类文字值。 */
        public final String authority;

        public SearchResult(String title, String url, String snippet, String summary,
                            String content, String date, String authority) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.snippet = snippet == null ? "" : snippet;
            this.summary = summary == null ? "" : summary;
            this.content = content == null ? "" : content;
            this.date = date == null ? "" : date;
            this.authority = authority == null ? "" : authority;
        }

        /** 权威度的数值形式，供「挑最可信的一条」排序；无法解析时返回 0。 */
        public double authorityRank() {
            return rankAuthority(authority);
        }

        @Override
        public String toString() {
            return "SearchResult{title=" + title + ", url=" + url + ", authority=" + authority + "}";
        }
    }

    /** 网页正文。 */
    public static final class PageContent {
        public final String title;
        public final String content;

        public PageContent(String title, String content) {
            this.title = title == null ? "" : title;
            this.content = content == null ? "" : content;
        }
    }

    public interface SearchCallback {
        void onSuccess(List<SearchResult> results);

        void onError(AiError error);
    }

    public interface FetchCallback {
        void onSuccess(PageContent page);

        void onError(AiError error);
    }

    // ------------------------------------------------------------------ 状态

    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;
    /** 默认是否开启思考模式；{@link #chat} 等便捷入口读取它。 */
    private volatile boolean thinkingEnabled = false;
    /** 采样温度，默认 1（模型默认）。确定性的曲谱转写场景会显式传 0.2。 */
    private volatile float temperature = 1.0f;

    private final ExecutorService io;
    private final Object connectionLock = new Object();
    private final List<HttpURLConnection> activeConnections = new ArrayList<HttpURLConnection>();
    private volatile Handler mainHandler;

    /** 取消代际：cancel() 自增；在途请求发现自己代际过期就回「已取消」而不去解析旧响应。 */
    private final AtomicInteger cancelEpoch = new AtomicInteger(0);

    // ------------------------------------------------------------------ 构造 / 配置

    public DeepSeekClient(String apiKey, String baseUrl, String model) {
        this.apiKey = normalizeKey(apiKey);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = normalizeModel(model);
        this.io = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "deepseek-io-" + index.incrementAndGet());
                // 守护线程：Activity 销毁后即使还有在途请求也不会拖住进程退出。
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public String model() {
        return model;
    }

    public void setModel(String value) {
        this.model = normalizeModel(value);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        this.baseUrl = normalizeBaseUrl(value);
    }

    public void setApiKey(String value) {
        this.apiKey = normalizeKey(value);
    }

    /** 是否已配置 Key（空字符串视为未配置；离线/关闭 AI 时上层直接跳过请求）。 */
    public boolean hasKey() {
        String key = apiKey;
        return key != null && !key.isEmpty();
    }

    /** 打开/关闭默认思考模式。适合在「曲谱转换」前打开、回到普通对话时关闭。 */
    public void setThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
    }

    public boolean thinkingEnabled() {
        return thinkingEnabled;
    }

    /**
     * 设置默认采样温度（0~2，默认 1）。曲谱转写/别名归一这类需要稳定输出的请求用 0.2；
     * 聊天/创意类保持 1。单次请求也可以用带 temperature 参数的重载覆盖。
     */
    public void setTemperature(float value) {
        this.temperature = clampTemperature(value);
    }

    public float temperature() {
        return temperature;
    }

    /** 释放线程池并中断在途连接。Activity 销毁时调用；之后本实例不再可用。 */
    public void shutdown() {
        cancel();
        io.shutdownNow();
    }

    /**
     * 取消当前所有请求。在途连接会被 disconnect，排队中的请求会直接回调「已取消」，
     * 因此每个已发出的请求仍会恰好收到一次回调（不会悬空）。
     */
    public void cancel() {
        cancelEpoch.incrementAndGet();
        List<HttpURLConnection> snapshot;
        synchronized (connectionLock) {
            snapshot = new ArrayList<HttpURLConnection>(activeConnections);
        }
        for (HttpURLConnection connection : snapshot) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // 连接可能已被请求线程关闭，disconnect 失败无需处理。
            }
        }
    }

    // ------------------------------------------------------------------ 对话

    /**
     * 发起一次对话。默认按 {@link #thinkingEnabled()} 决定是否思考（默认关闭，快）。
     *
     * @param systemPrompt 系统提示词，可为空
     * @param history      多轮历史（不含 systemPrompt 本身），可为空
     * @param jsonMode     是否要求模型返回 JSON（response_format=json_object，提示词里仍需出现 json 字样）
     * @param maxTokens    输出 token 上限，&lt;=0 使用默认值
     * @param callback     主线程回调
     */
    public void chat(String systemPrompt, List<ChatMessage> history, boolean jsonMode,
                     int maxTokens, ChatCallback callback) {
        chat(systemPrompt, history, jsonMode, maxTokens, thinkingEnabled, callback);
    }

    /**
     * 带显式思考开关的对话。曲谱转换这类需要推理的场景传 {@code true}，并给足 maxTokens
     * （建议 3000~4000）；普通聊天传 {@code false} 以降低延迟。
     */
    public void chat(String systemPrompt, List<ChatMessage> history, boolean jsonMode,
                     int maxTokens, boolean thinking, ChatCallback callback) {
        chat(systemPrompt, history, jsonMode, maxTokens, thinking, temperature, callback);
    }

    /**
     * 同时显式控制思考开关与采样温度的对话。曲谱别名归一/转写用低温度（0.2）让输出更稳定。
     */
    public void chat(final String systemPrompt, final List<ChatMessage> history,
                     final boolean jsonMode, final int maxTokens, final boolean thinking,
                     final float requestTemperature, final ChatCallback callback) {
        final ChatCallback out = new OnceChat(callback);
        if (!hasKey()) {
            deliverChatError(out, noKeyError());
            return;
        }
        final int epoch = cancelEpoch.get();
        final String body;
        try {
            body = buildChatBody(systemPrompt, history, jsonMode, maxTokens, thinking, requestTemperature);
        } catch (Throwable t) {
            deliverChatError(out, mapThrowable(t));
            return;
        }
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                        return;
                    }
                    HttpResult result = postJson(CHAT_PATH, body);
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                        return;
                    }
                    if (result.code < 200 || result.code >= 300) {
                        deliverChatError(out, mapHttpError(result.code, result.body));
                        return;
                    }
                    handleChatResponse(result.body, out);
                } catch (Throwable t) {
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                    } else {
                        deliverChatError(out, mapThrowable(t));
                    }
                }
            }
        };
        submit(worker, out);
    }

    /**
     * 多模态识图：只允许在 user 消息里带图（接口约束），图片用 base64 data URL 传输。
     *
     * @param systemPrompt 系统提示词（不能带图）
     * @param userText     用户文字要求
     * @param imageBytes   图片原始字节（建议 jpeg/png，压缩后 2MB 以内）
     * @param mime         图片 MIME，空值按 image/jpeg
     */
    public void chatWithImage(String systemPrompt, String userText, byte[] imageBytes,
                              String mime, ChatCallback callback) {
        chatWithImage(systemPrompt, userText, imageBytes, mime, false, 2048, thinkingEnabled, callback);
    }

    /** 带 JSON 模式 / 思考开关 / maxTokens 的识图重载。 */
    public void chatWithImage(String systemPrompt, String userText, byte[] imageBytes, String mime,
                              boolean jsonMode, int maxTokens, boolean thinking,
                              ChatCallback callback) {
        chatWithImage(systemPrompt, userText, imageBytes, mime, jsonMode, maxTokens, thinking,
                temperature, callback);
    }

    /** 同时显式控制采样温度的识图重载（识谱转写用 0.2）。 */
    public void chatWithImage(final String systemPrompt, final String userText,
                              final byte[] imageBytes, final String mime,
                              final boolean jsonMode, final int maxTokens, final boolean thinking,
                              final float requestTemperature, final ChatCallback callback) {
        final ChatCallback out = new OnceChat(callback);
        if (!hasKey()) {
            deliverChatError(out, noKeyError());
            return;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            deliverChatError(out, new AiError(0, "invalid_input", "没有读取到图片数据，请重新选择图片", false));
            return;
        }
        if (imageBytes.length > 10 * 1024 * 1024) {
            deliverChatError(out, new AiError(0, "image_too_large", "图片太大（超过 10MB），请先压缩或换一张更小的图片", false));
            return;
        }
        final int epoch = cancelEpoch.get();
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                        return;
                    }
                    String body = buildImageChatBody(systemPrompt, userText, imageBytes, mime,
                            jsonMode, maxTokens, thinking, requestTemperature);
                    HttpResult result = postJson(CHAT_PATH, body);
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                        return;
                    }
                    if (result.code < 200 || result.code >= 300) {
                        deliverChatError(out, mapHttpError(result.code, result.body));
                        return;
                    }
                    handleChatResponse(result.body, out);
                } catch (Throwable t) {
                    if (epoch != cancelEpoch.get()) {
                        deliverChatError(out, cancelledError());
                    } else {
                        deliverChatError(out, mapThrowable(t));
                    }
                }
            }
        };
        submit(worker, out);
    }

    /**
     * 最小对话验证 Key 是否可用。成功回调里返回模型文本（一般是 OK）。
     * 刻意关掉思考：测试连接要的是「快且确定」，思考模式会显著变慢。
     */
    public void testConnection(final ChatCallback callback) {
        final ChatCallback out = new OnceChat(callback);
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        history.add(ChatMessage.user("请回复 OK"));
        chat("你是连接测试助手，只回复 OK 两个字，不要解释。", history, false, 16, false,
                new ChatCallback() {
                    @Override
                    public void onSuccess(String text) {
                        String value = text == null ? "" : text.trim();
                        out.onSuccess(value.isEmpty() ? "OK" : value);
                    }

                    @Override
                    public void onError(AiError error) {
                        out.onError(error);
                    }
                });
    }

    // ------------------------------------------------------------------ 搜索 / 抓取

    /**
     * 联网搜索（DeepSeek 官方工具接口）。
     *
     * @param count 期望条数，夹紧到 1..50；返回结果可能少于请求数甚至为空数组
     */
    public void search(final String query, final int count, final SearchCallback callback) {
        final SearchCallback out = new OnceSearch(callback);
        if (!hasKey()) {
            deliverSearchError(out, noKeyError());
            return;
        }
        if (query == null || query.trim().isEmpty()) {
            deliverSearchError(out, new AiError(0, "invalid_input", "搜索词不能为空", false));
            return;
        }
        final int limit = Math.max(1, Math.min(50, count <= 0 ? 10 : count));
        final int epoch = cancelEpoch.get();
        final String body;
        try {
            Map<String, Object> request = MiniJson.obj();
            request.put("query", query.trim());
            request.put("count", limit);
            // need_content=true 时接口会把网页正文一并带回来，便于后续「抓不到就退回搜索结果正文」。
            request.put("need_content", true);
            body = MiniJson.stringify(request);
        } catch (Throwable t) {
            deliverSearchError(out, mapThrowable(t));
            return;
        }
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != cancelEpoch.get()) {
                        deliverSearchError(out, cancelledError());
                        return;
                    }
                    HttpResult result = postJson(SEARCH_PATH, body);
                    if (epoch != cancelEpoch.get()) {
                        deliverSearchError(out, cancelledError());
                        return;
                    }
                    if (result.code < 200 || result.code >= 300) {
                        deliverSearchError(out, mapHttpError(result.code, result.body));
                        return;
                    }
                    deliverSearchSuccess(out, parseSearchResults(result.body));
                } catch (Throwable t) {
                    if (epoch != cancelEpoch.get()) {
                        deliverSearchError(out, cancelledError());
                    } else {
                        deliverSearchError(out, mapThrowable(t));
                    }
                }
            }
        };
        submit(worker, out);
    }

    /** 抓取网页正文（Markdown）。 */
    public void fetchPage(final String url, final FetchCallback callback) {
        final FetchCallback out = new OnceFetch(callback);
        if (!hasKey()) {
            deliverFetchError(out, noKeyError());
            return;
        }
        String trimmed = url == null ? "" : url.trim();
        if (!isHttpUrl(trimmed)) {
            deliverFetchError(out, new AiError(0, "invalid_input", "网页链接必须以 http:// 或 https:// 开头", false));
            return;
        }
        final int epoch = cancelEpoch.get();
        final String body;
        try {
            Map<String, Object> request = MiniJson.obj();
            request.put("url", trimmed);
            body = MiniJson.stringify(request);
        } catch (Throwable t) {
            deliverFetchError(out, mapThrowable(t));
            return;
        }
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != cancelEpoch.get()) {
                        deliverFetchError(out, cancelledError());
                        return;
                    }
                    HttpResult result = postJson(FETCH_PATH, body);
                    if (epoch != cancelEpoch.get()) {
                        deliverFetchError(out, cancelledError());
                        return;
                    }
                    if (result.code < 200 || result.code >= 300) {
                        deliverFetchError(out, mapHttpError(result.code, result.body));
                        return;
                    }
                    PageContent page = parsePageContent(result.body);
                    if (page == null || page.content.trim().isEmpty()) {
                        deliverFetchError(out, new AiError(0, "empty_page",
                                "这个网页没有抓到可读正文（可能需要登录，或不是文章/曲谱页），换个链接或改用粘贴文本吧", false));
                        return;
                    }
                    deliverFetchSuccess(out, page);
                } catch (Throwable t) {
                    if (epoch != cancelEpoch.get()) {
                        deliverFetchError(out, cancelledError());
                    } else {
                        deliverFetchError(out, mapThrowable(t));
                    }
                }
            }
        };
        submit(worker, out);
    }

    // ------------------------------------------------------------------ 请求体构造

    private String buildChatBody(String systemPrompt, List<ChatMessage> history, boolean jsonMode,
                                 int maxTokens, boolean thinking, float requestTemperature) {
        Map<String, Object> request = MiniJson.obj();
        request.put("model", model);
        request.put("messages", buildMessages(systemPrompt, history));
        request.put("max_tokens", clampMaxTokens(maxTokens));
        request.put("stream", Boolean.FALSE);
        request.put("thinking", thinkingSpec(thinking));
        request.put("temperature", clampTemperature(requestTemperature));
        if (jsonMode) {
            Map<String, Object> responseFormat = MiniJson.obj();
            responseFormat.put("type", "json_object");
            request.put("response_format", responseFormat);
        }
        return MiniJson.stringify(request);
    }

    private String buildImageChatBody(String systemPrompt, String userText, byte[] imageBytes,
                                      String mime, boolean jsonMode, int maxTokens, boolean thinking,
                                      float requestTemperature) {
        String safeMime = normalizeImageMime(mime);
        String dataUrl = "data:" + safeMime + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> imageUrl = MiniJson.obj();
        imageUrl.put("url", dataUrl);
        // detail=high：谱面里的数字/小节线很小，低分辨率会直接识别失败。
        imageUrl.put("detail", "high");
        Map<String, Object> imageBlock = MiniJson.obj();
        imageBlock.put("type", "image_url");
        imageBlock.put("image_url", imageUrl);
        Map<String, Object> textBlock = MiniJson.obj();
        textBlock.put("type", "text");
        textBlock.put("text", userText == null ? "" : userText);

        List<Object> contentBlocks = MiniJson.arr();
        contentBlocks.add(textBlock);
        contentBlocks.add(imageBlock);
        Map<String, Object> userMessage = MiniJson.obj();
        userMessage.put("role", "user");
        userMessage.put("content", contentBlocks);

        List<Object> messages = MiniJson.arr();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> systemMessage = MiniJson.obj();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        messages.add(userMessage);

        Map<String, Object> request = MiniJson.obj();
        request.put("model", model);
        request.put("messages", messages);
        request.put("max_tokens", clampMaxTokens(maxTokens));
        request.put("stream", Boolean.FALSE);
        request.put("thinking", thinkingSpec(thinking));
        request.put("temperature", clampTemperature(requestTemperature));
        if (jsonMode) {
            Map<String, Object> responseFormat = MiniJson.obj();
            responseFormat.put("type", "json_object");
            request.put("response_format", responseFormat);
        }
        return MiniJson.stringify(request);
    }

    private static List<Object> buildMessages(String systemPrompt, List<ChatMessage> history) {
        List<Object> messages = MiniJson.arr();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> systemMessage = MiniJson.obj();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        if (history != null) {
            for (ChatMessage message : history) {
                if (message == null) {
                    continue;
                }
                // 接口只接受 user / assistant / system；非法角色丢弃，避免整个请求 400。
                String role = message.role;
                if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                    continue;
                }
                Map<String, Object> item = MiniJson.obj();
                item.put("role", role);
                item.put("content", message.content == null ? "" : message.content);
                messages.add(item);
            }
        }
        return messages;
    }

    private static Map<String, Object> thinkingSpec(boolean enabled) {
        Map<String, Object> spec = MiniJson.obj();
        spec.put("type", enabled ? "enabled" : "disabled");
        return spec;
    }

    private static int clampMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            return DEFAULT_MAX_TOKENS;
        }
        return Math.min(maxTokens, MAX_OUTPUT_TOKENS);
    }

    private static float clampTemperature(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 1.0f;
        }
        if (value < 0f) {
            return 0f;
        }
        return Math.min(value, 2f);
    }

    private static String normalizeImageMime(String mime) {
        String value = mime == null ? "" : mime.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("image/") && value.length() > 6) {
            return value;
        }
        // 没有 MIME 时按 jpeg 处理：手机相册/相机导出的图片绝大多数是 jpeg。
        return "image/jpeg";
    }

    // ------------------------------------------------------------------ 响应解析

    private void handleChatResponse(String body, ChatCallback out) {
        Map<String, Object> root;
        try {
            root = MiniJson.asObject(MiniJson.parse(body));
        } catch (IllegalArgumentException e) {
            deliverChatError(out, new AiError(0, "parse_error", "AI 响应解析失败，请稍后重试", true));
            return;
        }
        if (root == null) {
            deliverChatError(out, new AiError(0, "parse_error", "AI 响应格式异常，请稍后重试", true));
            return;
        }
        Map<String, Object> errorObject = MiniJson.object(root, "error");
        if (errorObject != null) {
            deliverChatError(out, apiBodyError(errorObject));
            return;
        }
        List<Object> choices = MiniJson.array(root, "choices");
        if (choices == null || choices.isEmpty()) {
            deliverChatError(out, new AiError(0, "empty_response", "AI 没有返回结果，请重试", true));
            return;
        }
        Map<String, Object> choice = MiniJson.asObject(choices.get(0));
        Map<String, Object> message = MiniJson.object(choice, "message");
        String content = MiniJson.string(message, "content", "");
        if (content.trim().isEmpty()) {
            // 思考模式下 content 可能被 reasoning 先占满 token 而返回空串，这里给出可操作的提示。
            String finish = MiniJson.string(choice, "finish_reason", "");
            boolean truncated = "length".equals(finish);
            deliverChatError(out, new AiError(0, "empty_response",
                    truncated ? "AI 输出达到长度上限（思考模式占用较多 token），请重试或缩短输入"
                            : "AI 没有返回文本内容，请重试",
                    true));
            return;
        }
        deliverChatSuccess(out, content);
    }

    private static List<SearchResult> parseSearchResults(String body) throws SearchParseException {
        Object parsed;
        try {
            parsed = MiniJson.parse(body);
        } catch (IllegalArgumentException e) {
            throw new SearchParseException("搜索响应解析失败，请稍后重试");
        }
        List<Object> rows = null;
        Map<String, Object> root = MiniJson.asObject(parsed);
        if (root != null) {
            Object errorObject = root.get("error");
            Map<String, Object> errorMap = MiniJson.asObject(errorObject);
            if (errorMap != null) {
                throw new SearchParseException(MiniJson.text(errorMap, "message", "搜索接口返回错误"));
            }
            rows = MiniJson.array(root, "results");
        } else {
            rows = MiniJson.asArray(parsed);
        }
        if (rows == null) {
            throw new SearchParseException("搜索接口返回格式异常，请稍后重试");
        }
        List<SearchResult> results = new ArrayList<SearchResult>();
        for (Object row : rows) {
            Map<String, Object> item = MiniJson.asObject(row);
            if (item == null) {
                continue;
            }
            String url = MiniJson.text(item, "url", "");
            if (url.isEmpty()) {
                continue;
            }
            results.add(new SearchResult(
                    MiniJson.text(item, "title", ""),
                    url,
                    MiniJson.text(item, "snippet", ""),
                    MiniJson.text(item, "summary", ""),
                    MiniJson.text(item, "content", ""),
                    MiniJson.text(item, "date", ""),
                    MiniJson.text(item, "authority", "")));
        }
        return results;
    }

    private static PageContent parsePageContent(String body) throws SearchParseException {
        Map<String, Object> root;
        try {
            root = MiniJson.asObject(MiniJson.parse(body));
        } catch (IllegalArgumentException e) {
            throw new SearchParseException("网页抓取响应解析失败，请稍后重试");
        }
        if (root == null) {
            throw new SearchParseException("网页抓取接口返回格式异常，请稍后重试");
        }
        Map<String, Object> errorMap = MiniJson.object(root, "error");
        if (errorMap != null) {
            throw new SearchParseException(MiniJson.text(errorMap, "message", "网页抓取失败"));
        }
        String title = MiniJson.text(root, "title", "");
        String content = MiniJson.text(root, "content", "");
        if (content.isEmpty()) {
            // 个别兼容层把正文放在 text/markdown 字段，按顺序兜底。
            content = MiniJson.text(root, "text", "");
        }
        if (content.isEmpty()) {
            content = MiniJson.text(root, "markdown", "");
        }
        return new PageContent(title, content);
    }

    private static AiError apiBodyError(Map<String, Object> errorObject) {
        String message = MiniJson.text(errorObject, "message", "");
        String code = MiniJson.text(errorObject, "code", "");
        if (code.isEmpty()) {
            code = MiniJson.text(errorObject, "type", "api_error");
        }
        if (message.isEmpty()) {
            message = "AI 服务返回错误，请稍后重试";
        }
        return new AiError(0, code, limit(message, 200), false);
    }

    // ------------------------------------------------------------------ 错误映射（纯函数，可 JVM 单测）

    /**
     * 把 HTTP 非 2xx 响应映射成用户可读的错误。
     *
     * @param httpCode     状态码
     * @param responseBody 响应体（可能为空/非 JSON）
     */
    public static AiError mapHttpError(int httpCode, String responseBody) {
        String detail = extractServerMessage(responseBody);
        String suffix = detail.isEmpty() ? "" : "（" + detail + "）";
        if (httpCode == 401) {
            return new AiError(401, "invalid_key",
                    "API Key 无效或已过期，请到「AI 设置」里重新填写并测试连接" + suffix, false);
        }
        if (httpCode == 402) {
            return new AiError(402, "insufficient_balance",
                    "DeepSeek 账户余额不足，请充值后再试" + suffix, false);
        }
        if (httpCode == 403) {
            return new AiError(403, "forbidden",
                    "当前 Key 没有使用该模型/接口的权限" + suffix, false);
        }
        if (httpCode == 404) {
            return new AiError(404, "not_found",
                    "接口地址或模型不存在，请检查 Base URL 与模型名" + suffix, false);
        }
        if (httpCode == 408) {
            return new AiError(408, "timeout", "请求超时，请检查网络后重试" + suffix, true);
        }
        if (httpCode == 413) {
            return new AiError(413, "payload_too_large",
                    "请求内容太大（例如图片或文本过长），请压缩或缩短后重试" + suffix, false);
        }
        if (httpCode == 429) {
            return new AiError(429, "rate_limited", "请求过于频繁（被限流），请稍等几秒再试" + suffix, true);
        }
        if (httpCode >= 500 && httpCode <= 599) {
            return new AiError(httpCode, "server_error",
                    "DeepSeek 服务暂时不可用（HTTP " + httpCode + "），请稍后重试" + suffix, true);
        }
        if (httpCode >= 400 && httpCode < 500) {
            return new AiError(httpCode, "bad_request",
                    "AI 请求参数有误（HTTP " + httpCode + "）" + suffix, false);
        }
        return new AiError(httpCode, "http_error",
                "AI 请求失败（HTTP " + httpCode + "）" + suffix, httpCode == 0);
    }

    /** 把网络/解析异常映射成用户可读的错误（纯函数，可 JVM 单测）。 */
    public static AiError mapThrowable(Throwable throwable) {
        if (throwable == null) {
            return new AiError(0, "unknown", "AI 请求失败，请稍后重试", true);
        }
        if (throwable instanceof PayloadTooLargeException) {
            return new AiError(0, "payload_too_large", throwable.getMessage(), false);
        }
        if (throwable instanceof SearchParseException) {
            return new AiError(0, "parse_error", throwable.getMessage(), true);
        }
        if (throwable instanceof SocketTimeoutException) {
            return new AiError(0, "timeout", "网络请求超时，请检查网络后重试", true);
        }
        if (throwable instanceof UnknownHostException) {
            return new AiError(0, "no_network", "连不上 DeepSeek 服务器，请检查手机网络（WiFi/流量）", true);
        }
        if (throwable instanceof ConnectException) {
            return new AiError(0, "connect_failed", "连接 DeepSeek 服务器失败，请检查网络后重试", true);
        }
        if (throwable instanceof javax.net.ssl.SSLException) {
            return new AiError(0, "tls_error", "建立安全连接失败，请检查系统时间或网络代理设置", true);
        }
        if (throwable instanceof IOException) {
            String message = safeThrowableMessage(throwable);
            return new AiError(0, "network_error",
                    "网络请求失败" + (message.isEmpty() ? "，请检查网络后重试" : "：" + message), true);
        }
        if (throwable instanceof IllegalArgumentException) {
            return new AiError(0, "parse_error", "AI 响应解析失败：" + safeThrowableMessage(throwable), true);
        }
        return new AiError(0, "internal_error", "AI 请求失败：" + safeThrowableMessage(throwable), false);
    }

    /** 未配置 Key 的错误。 */
    public static AiError noKeyError() {
        return new AiError(0, "no_key",
                "还没有配置 DeepSeek API Key。请先在「AI 设置」里填入个人 Key，或输入口令使用官方通道", false);
    }

    /** 请求被取消的错误。 */
    public static AiError cancelledError() {
        return new AiError(0, "cancelled", "请求已取消", false);
    }

    /** 权威度文本转数值：数字原样；high/medium/low 等文字按档位折算。 */
    public static double rankAuthority(String authority) {
        if (authority == null || authority.trim().isEmpty()) {
            return 0;
        }
        String value = authority.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            // 非数字：按常见档位文字折算。
        }
        if (value.contains("high") || value.contains("高")) {
            return 5;
        }
        if (value.contains("medium") || value.contains("mid") || value.contains("中")) {
            return 3;
        }
        if (value.contains("low") || value.contains("低")) {
            return 1;
        }
        return 0;
    }

    private static String extractServerMessage(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "";
        }
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(responseBody));
            if (root != null) {
                Map<String, Object> errorObject = MiniJson.object(root, "error");
                if (errorObject != null) {
                    String message = MiniJson.text(errorObject, "message", "");
                    if (!message.isEmpty()) {
                        return limit(message, 160);
                    }
                }
                String message = MiniJson.text(root, "message", "");
                if (!message.isEmpty()) {
                    return limit(message, 160);
                }
            }
        } catch (Throwable ignored) {
            // 非 JSON 响应（例如网关返回 HTML）不展示原始内容，避免把一大段 HTML 糊到界面上。
        }
        return "";
    }

    private static String safeThrowableMessage(Throwable throwable) {
        try {
            String message = throwable.getMessage();
            if (message == null) {
                return "";
            }
            // 异常消息里可能带 URL，但不会带 Key（Key 只在请求头里）；仍然做长度裁剪。
            return limit(message.replace('\n', ' ').replace('\r', ' '), 120);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }

    // ------------------------------------------------------------------ HTTP 实现

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /** 请求体超过上限（例如超大图片）时抛出，交给 mapThrowable 转成可读错误。 */
    private static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /** 工具接口响应格式异常。 */
    private static final class SearchParseException extends IOException {
        SearchParseException(String message) {
            super(message);
        }
    }

    private HttpResult postJson(String path, String requestBody) throws IOException {
        if (!hasKey()) {
            throw new IOException("未配置 API Key");
        }
        byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_REQUEST_BYTES) {
            throw new PayloadTooLargeException("请求内容超过 16MB 上限，请压缩图片或缩短文本");
        }
        String target = urlFor(path);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(target);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            // 手动接收 gzip：HttpURLConnection 不会帮我们解压；搜索接口正文动辄几百 KB，压缩很划算。
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "UkuleleStudio-Android/1.0");
            // Key 只出现在请求头，绝不写日志。
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            connection.setFixedLengthStreamingMode(payload.length);
            registerConnection(connection);
            connection.connect();
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
                output.flush();
            } finally {
                closeQuietly(output);
            }
            int code = connection.getResponseCode();
            InputStream input = (code >= 200 && code < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String charset = charsetFromContentType(connection.getContentType());
            String body = readBody(input, charset);
            return new HttpResult(code, body);
        } finally {
            unregisterConnection(connection);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 拼接接口地址。官方 Base 有两种合法写法（带不带 /v1），统一处理：
     * Base 已以 /v1 结尾时，聊天路径 /chat/completions 直接拼；工具路径 /v1/tools/... 去掉重复的 /v1。
     */
    private String urlFor(String path) {
        String base = normalizeBaseUrl(baseUrl);
        if (base.endsWith("/v1")) {
            if (path.startsWith("/v1/")) {
                return base + path.substring(3);
            }
            return base + path;
        }
        return base + path;
    }

    private static String readBody(InputStream input, String charset) throws IOException {
        if (input == null) {
            return "";
        }
        byte[] raw;
        try {
            raw = readAllBytes(input, MAX_RESPONSE_BYTES);
        } finally {
            closeQuietly(input);
        }
        // 响应头可能是 gzip 也可能已被中间层解压，用魔数判断最稳。
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
            try {
                raw = readAllBytes(gzip, MAX_RESPONSE_BYTES);
            } finally {
                closeQuietly(gzip);
            }
        }
        Charset decode = StandardCharsets.UTF_8;
        if (charset != null && !charset.isEmpty()) {
            try {
                decode = Charset.forName(charset);
            } catch (Throwable ignored) {
                decode = StandardCharsets.UTF_8;
            }
        }
        return new String(raw, decode);
    }

    private static byte[] readAllBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (out.size() + read > limit) {
                throw new IOException("服务器响应过大，已中止读取");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        int index = lower.indexOf("charset=");
        if (index < 0) {
            return "";
        }
        String charset = contentType.substring(index + 8).trim();
        int semicolon = charset.indexOf(';');
        if (semicolon >= 0) {
            charset = charset.substring(0, semicolon);
        }
        return charset.replace("\"", "").trim();
    }

    private void registerConnection(HttpURLConnection connection) {
        synchronized (connectionLock) {
            activeConnections.add(connection);
        }
    }

    private void unregisterConnection(HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        synchronized (connectionLock) {
            activeConnections.remove(connection);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 线程 / 回调

    private void submit(Runnable worker, ChatCallback out) {
        try {
            io.execute(worker);
        } catch (RejectedExecutionException e) {
            deliverChatError(out, new AiError(0, "shutdown", "AI 客户端已关闭，请重新打开页面", false));
        }
    }

    private void submit(Runnable worker, SearchCallback out) {
        try {
            io.execute(worker);
        } catch (RejectedExecutionException e) {
            deliverSearchError(out, new AiError(0, "shutdown", "AI 客户端已关闭，请重新打开页面", false));
        }
    }

    private void submit(Runnable worker, FetchCallback out) {
        try {
            io.execute(worker);
        } catch (RejectedExecutionException e) {
            deliverFetchError(out, new AiError(0, "shutdown", "AI 客户端已关闭，请重新打开页面", false));
        }
    }

    private void deliverChatSuccess(final ChatCallback callback, final String text) {
        final String value = text == null ? "" : text;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private void deliverChatError(final ChatCallback callback, final AiError error) {
        final AiError value = error == null ? new AiError(0, "unknown", "AI 请求失败", false) : error;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onError(value);
            }
        });
    }

    private void deliverSearchSuccess(final SearchCallback callback, final List<SearchResult> results) {
        final List<SearchResult> value = results == null
                ? Collections.<SearchResult>emptyList() : results;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private void deliverSearchError(final SearchCallback callback, final AiError error) {
        final AiError value = error == null ? new AiError(0, "unknown", "AI 搜索失败", false) : error;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onError(value);
            }
        });
    }

    private void deliverFetchSuccess(final FetchCallback callback, final PageContent page) {
        final PageContent value = page == null ? new PageContent("", "") : page;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private void deliverFetchError(final FetchCallback callback, final AiError error) {
        final AiError value = error == null ? new AiError(0, "unknown", "网页抓取失败", false) : error;
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onError(value);
            }
        });
    }

    private void postMain(Runnable action) {
        Handler handler = mainHandler();
        if (handler != null) {
            handler.post(action);
        } else {
            // 桌面 JVM（单元测试/联调脚本）没有 Android 主线程 Looper，直接在当前线程执行，
            // 保证回调不丢失；Android 真机上永远走上面的 Handler 分支。
            action.run();
        }
    }

    private Handler mainHandler() {
        Handler handler = mainHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (this) {
            handler = mainHandler;
            if (handler == null) {
                try {
                    handler = new Handler(Looper.getMainLooper());
                } catch (Throwable ignored) {
                    // 非 Android 运行环境：Looper 是桩实现会抛异常，保持 null 走同步回调兜底。
                    handler = null;
                }
                mainHandler = handler;
            }
        }
        return handler;
    }

    // ------------------------------------------------------------------ 回调只执行一次

    private static final class OnceChat implements ChatCallback {
        private final ChatCallback target;
        private final AtomicBoolean done = new AtomicBoolean(false);

        OnceChat(ChatCallback target) {
            this.target = target != null ? target : new ChatCallback() {
                @Override
                public void onSuccess(String text) {
                }

                @Override
                public void onError(AiError error) {
                }
            };
        }

        @Override
        public void onSuccess(String text) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(text);
            }
        }

        @Override
        public void onError(AiError error) {
            if (done.compareAndSet(false, true)) {
                target.onError(error);
            }
        }
    }

    private static final class OnceSearch implements SearchCallback {
        private final SearchCallback target;
        private final AtomicBoolean done = new AtomicBoolean(false);

        OnceSearch(SearchCallback target) {
            this.target = target != null ? target : new SearchCallback() {
                @Override
                public void onSuccess(List<SearchResult> results) {
                }

                @Override
                public void onError(AiError error) {
                }
            };
        }

        @Override
        public void onSuccess(List<SearchResult> results) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(results);
            }
        }

        @Override
        public void onError(AiError error) {
            if (done.compareAndSet(false, true)) {
                target.onError(error);
            }
        }
    }

    private static final class OnceFetch implements FetchCallback {
        private final FetchCallback target;
        private final AtomicBoolean done = new AtomicBoolean(false);

        OnceFetch(FetchCallback target) {
            this.target = target != null ? target : new FetchCallback() {
                @Override
                public void onSuccess(PageContent page) {
                }

                @Override
                public void onError(AiError error) {
                }
            };
        }

        @Override
        public void onSuccess(PageContent page) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(page);
            }
        }

        @Override
        public void onError(AiError error) {
            if (done.compareAndSet(false, true)) {
                target.onError(error);
            }
        }
    }

    // ------------------------------------------------------------------ 配置归一化

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String base = value == null ? "" : value.trim();
        if (base.isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isEmpty() ? DEFAULT_BASE_URL : base;
    }

    private static String normalizeModel(String value) {
        String model = value == null ? "" : value.trim();
        if (MODEL_PRO.equals(model)) {
            return MODEL_PRO;
        }
        // 目前官方只开放这两个模型名；空值/未知值一律回退到支持看图的 flash，避免请求 400。
        return MODEL_FLASH;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
