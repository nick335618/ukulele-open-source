package com.ukulele.monitor.ai;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公开曲谱文件下载器（平台 Java：HttpURLConnection + 主线程回调）。
 *
 * <p>用途：AI 搜到 MIDI 页面后，下载页面里的 .mid 文件交给 App 既有 MIDI 解析器。
 * <b>不使用 DeepSeek API Key</b>：这些是公开曲谱站点的普通下载，鉴权头会泄露 Key 且没有必要。
 *
 * <p>安全/健壮性：只允许 https（防止明文降级）、手动跟随最多 3 次重定向、连接 15s / 读取 30s
 * 超时、按 Content-Length 与读取上限双重限制文件大小（默认 4MB）、任何异常都转成中文错误。
 */
public final class WebDownloader {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_REDIRECTS = 3;
    public static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;

    public interface DownloadCallback {
        void onSuccess(byte[] data);

        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "score-download-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile Handler mainHandler;

    private WebDownloader() {
    }

    public static void download(String url, DownloadCallback callback) {
        download(url, DEFAULT_MAX_BYTES, callback);
    }

    /** 文本回调：用于下载 HTML/Markdown 页面（MIDI 站点、ukulele tab 站点）。 */
    public interface TextCallback {
        void onSuccess(String text);

        void onError(String message);
    }

    /** 下载网页并转成 UTF-8 文本（最多 {@link #DEFAULT_MAX_BYTES}）。 */
    public static void downloadText(String url, TextCallback callback) {
        downloadText(url, DEFAULT_MAX_BYTES, callback);
    }

    /**
     * 下载网页并转成 UTF-8 文本。页面被部分站点声明成别的编码时不在这里猜：AI 搜索管线只关心
     * URL/ABС/品格字符，UTF-8 是这些站点的主流编码，乱码行会被解析器自然忽略。
     */
    public static void downloadText(String url, int maxBytes, TextCallback callback) {
        final TextCallback target = callback != null ? callback : new TextCallback() {
            @Override
            public void onSuccess(String text) {
            }

            @Override
            public void onError(String message) {
            }
        };
        download(url, maxBytes, new DownloadCallback() {
            private final AtomicBoolean done = new AtomicBoolean(false);

            @Override
            public void onSuccess(byte[] data) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                try {
                    target.onSuccess(new String(data == null ? new byte[0] : data,
                            java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    target.onError("页面文本解析失败：" + t.getClass().getSimpleName());
                }
            }

            @Override
            public void onError(String message) {
                if (done.compareAndSet(false, true)) {
                    target.onError(message);
                }
            }
        });
    }

    /**
     * 下载 {@code url}，最多 {@code maxBytes} 字节。
     *
     * @param maxBytes &lt;=0 时使用 {@link #DEFAULT_MAX_BYTES}
     */
    public static void download(final String url, final int maxBytes, final DownloadCallback callback) {
        final OnceCallback out = new OnceCallback(callback);
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            deliverError(out, "下载链接为空");
            return;
        }
        if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            deliverError(out, "只支持 https 链接下载曲谱文件");
            return;
        }
        final int limit = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
        try {
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] data = fetch(url.trim(), limit);
                        deliverSuccess(out, data);
                    } catch (Throwable t) {
                        deliverError(out, mapError(t));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            deliverError(out, "下载线程池已关闭，请重试");
        }
    }

    /** 是否为标准 MIDI 文件（MThd 头）。 */
    public static boolean looksLikeMidi(byte[] data) {
        return data != null && data.length >= 8
                && data[0] == 'M' && data[1] == 'T' && data[2] == 'h' && data[3] == 'd';
    }

    // ------------------------------------------------------------------ 下载实现

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        String current = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "UkuleleStudio-Android/1.0");
                connection.setRequestProperty("Accept", "application/octet-stream,*/*");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("重定向地址缺失（HTTP " + code + "）");
                    }
                    current = new URL(new URL(current), location.trim()).toString();
                    if (!current.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                        throw new IOException("下载被重定向到非 https 地址，已拒绝");
                    }
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new HttpStatusException(code);
                }
                long contentLength = parseContentLength(connection.getHeaderField("Content-Length"));
                if (contentLength > maxBytes) {
                    throw new IOException("文件过大（" + formatSize(contentLength) + "），可能不是 MIDI 文件");
                }
                InputStream input = connection.getInputStream();
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        if (out.size() + read > maxBytes) {
                            throw new IOException("文件超过 " + formatSize(maxBytes) + " 上限，已中止下载");
                        }
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                } finally {
                    closeQuietly(input);
                }
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        throw new IOException("重定向次数过多");
    }

    private static long parseContentLength(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.ROOT, "%.0fKB", bytes / 1024.0);
    }

    private static String mapError(Throwable t) {
        if (t instanceof HttpStatusException) {
            int code = ((HttpStatusException) t).code;
            if (code == 404) {
                return "曲谱文件不存在或已删除";
            }
            if (code == 403) {
                return "曲谱站点拒绝下载（可能需要登录或验证）";
            }
            if (code == 429) {
                return "下载被站点限流，请稍后重试";
            }
            if (code >= 500 && code <= 599) {
                return "曲谱站点暂时不可用（HTTP " + code + "），请稍后重试";
            }
            return "下载失败（HTTP " + code + "）";
        }
        if (t instanceof SocketTimeoutException) {
            return "下载超时，请检查网络后重试";
        }
        if (t instanceof UnknownHostException) {
            return "连不上曲谱站点，请检查手机网络";
        }
        if (t instanceof ConnectException) {
            return "连接曲谱站点失败，请检查网络后重试";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "下载的安全连接失败，请检查网络代理设置";
        }
        String message = t == null ? "" : t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "下载失败，请稍后重试";
        }
        String flat = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 100) + "…";
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
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

    // ------------------------------------------------------------------ 主线程回调

    private static void deliverSuccess(final OnceCallback callback, final byte[] data) {
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(data);
            }
        });
    }

    private static void deliverError(final OnceCallback callback, final String message) {
        postMain(new Runnable() {
            @Override
            public void run() {
                callback.onError(message == null ? "下载失败" : message);
            }
        });
    }

    private static void postMain(Runnable action) {
        Handler handler = mainHandler();
        if (handler != null) {
            handler.post(action);
        } else {
            // 桌面 JVM（单测/联调）没有 Android 主线程 Looper：直接执行，保证回调不丢失。
            action.run();
        }
    }

    private static Handler mainHandler() {
        Handler handler = mainHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (WebDownloader.class) {
            handler = mainHandler;
            if (handler == null) {
                try {
                    handler = new Handler(Looper.getMainLooper());
                } catch (Throwable ignored) {
                    handler = null;
                }
                mainHandler = handler;
            }
        }
        return handler;
    }

    private static final class OnceCallback implements DownloadCallback {
        private final DownloadCallback target;
        private final AtomicBoolean done = new AtomicBoolean(false);

        OnceCallback(DownloadCallback target) {
            this.target = target != null ? target : new DownloadCallback() {
                @Override
                public void onSuccess(byte[] data) {
                }

                @Override
                public void onError(String message) {
                }
            };
        }

        @Override
        public void onSuccess(byte[] data) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(data);
            }
        }

        @Override
        public void onError(String message) {
            if (done.compareAndSet(false, true)) {
                target.onError(message);
            }
        }
    }
}
