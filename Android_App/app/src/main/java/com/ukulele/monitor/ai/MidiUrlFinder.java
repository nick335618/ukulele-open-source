package com.ukulele.monitor.ai;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 MIDI 页面（Markdown/HTML 文本）里找可下载的 .mid 链接（纯 Java，可 JVM 单测）。
 *
 * <p><b>为什么需要：</b>ABC 路线没有命中时，MIDI 谱是另一种机器可读来源：搜到 MIDI 页面后
 * 不用模型「编」任何东西，直接下载 .mid 交给 App 既有 MIDI 解析器（MThd 校验 + 解析）。
 *
 * <p>规则：绝对 URL 与页面内相对路径（bitmidi 的 {@code /uploads/xxx.mid}）都支持；
 * 去重、过滤明显的跟踪/广告链接、同域名优先、最多 8 条。
 */
public final class MidiUrlFinder {

    private static final int MAX_URLS = 8;

    private static final Pattern ABSOLUTE = Pattern.compile(
            "(?i)https?://[^\\s)\\\"'<>]+\\.(?:midi|mid)(?:\\?[^\\s)\\\"'<>]*)?");
    private static final Pattern RELATIVE = Pattern.compile(
            "(?i)(?:^|[\\s(\"\\[])((?:/|\\./|\\.\\./)[^\\s)\\\"'<>]+\\.(?:midi|mid)(?:\\?[^\\s)\\\"'<>]*)?)");
    /**
     * bitmidi 的下载直链形如 {@code /uploads/106069.mid}：页面 HTML 里可能出现在 href/src/JS
     * 字符串里，前面不一定是 RELATIVE 模式要求的空白/括号/引号场景，所以单独识别。
     */
    private static final Pattern BITMIDI_UPLOAD = Pattern.compile(
            "(?i)(?:https?://(?:www\\.)?bitmidi\\.com)?(/uploads/[0-9]+\\.mid)");

    private MidiUrlFinder() {
    }

    /** 提取绝对 MIDI URL。 */
    public static List<String> find(String markdown) {
        return find(markdown, null);
    }

    /** 从页面 HTML 文本提取 MIDI 链接（先反转义，再按普通 Markdown/HTML 文本处理）。 */
    public static List<String> findFromHtml(String html, String pageUrl) {
        return find(html, pageUrl);
    }

    /**
     * 提取 MIDI URL；{@code pageUrl} 非空时用于解析相对路径并把同域名链接排到前面。
     */
    public static List<String> find(String markdown, String pageUrl) {
        List<String> result = new ArrayList<String>();
        if (markdown == null || markdown.isEmpty()) {
            return result;
        }
        String text = unescapeMarkdown(markdown);
        Set<String> seen = new LinkedHashSet<String>();
        Matcher absolute = ABSOLUTE.matcher(text);
        while (absolute.find()) {
            addCandidate(seen, trimTrailingPunctuation(absolute.group()));
        }
        Matcher relative = RELATIVE.matcher(text);
        while (relative.find()) {
            String path = trimTrailingPunctuation(relative.group(1));
            String resolved = resolve(pageUrl, path);
            if (resolved != null) {
                addCandidate(seen, resolved);
            }
        }
        Matcher bitmidi = BITMIDI_UPLOAD.matcher(text);
        while (bitmidi.find()) {
            String path = trimTrailingPunctuation(bitmidi.group(1));
            String resolved = resolve(pageUrl, path);
            if (resolved == null || !resolved.toLowerCase(Locale.ROOT).contains("bitmidi.com")) {
                // 页面不是 bitmidi 时仍按 bitmidi 官方域名拼绝对地址（该站直链域名固定）。
                resolved = "https://bitmidi.com" + path;
            }
            addCandidate(seen, resolved);
        }
        List<String> ordered = new ArrayList<String>(seen);
        if (pageUrl != null && !pageUrl.isEmpty()) {
            String host = hostOf(pageUrl);
            if (!host.isEmpty()) {
                List<String> sameHost = new ArrayList<String>();
                List<String> others = new ArrayList<String>();
                for (String url : ordered) {
                    if (host.equalsIgnoreCase(hostOf(url))) {
                        sameHost.add(url);
                    } else {
                        others.add(url);
                    }
                }
                ordered.clear();
                ordered.addAll(sameHost);
                ordered.addAll(others);
            }
        }
        for (int i = 0; i < ordered.size() && result.size() < MAX_URLS; i++) {
            String url = ordered.get(i);
            if (isTracking(url)) {
                continue;
            }
            result.add(url);
        }
        return result;
    }

    private static void addCandidate(Set<String> seen, String url) {
        if (url == null) {
            return;
        }
        String value = url.trim();
        if (value.isEmpty() || !value.toLowerCase(Locale.ROOT).startsWith("http")) {
            return;
        }
        seen.add(value);
    }

    private static String resolve(String pageUrl, String path) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return null;
        }
        try {
            URL base = new URL(pageUrl);
            URL resolved = new URL(base, path);
            String protocol = resolved.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return null;
            }
            return resolved.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimTrailingPunctuation(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' || c == '>'
                    || c == '"' || c == '\'') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static boolean isTracking(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String[] blocked = {"utm_", "doubleclick", "googlesyndication", "adservice", "click?", "/redirect",
                "tracking", "affiliate", "sponsor", "analytics", "?ref="};
        for (String token : blocked) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** DeepSeek fetch 返回的 Markdown 会把标点转义成 {@code \.}、{@code \/} 等。 */
    private static String unescapeMarkdown(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (!Character.isLetterOrDigit(next) && !Character.isWhitespace(next)) {
                    builder.append(next);
                    i++;
                    continue;
                }
            }
            builder.append(c);
        }
        return builder.toString();
    }
}
