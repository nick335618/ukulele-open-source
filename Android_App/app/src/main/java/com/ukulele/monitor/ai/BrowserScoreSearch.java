package com.ukulele.monitor.ai;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Browser search keeps the user's song/author query, without a site allowlist. */
public final class BrowserScoreSearch {
    private BrowserScoreSearch() { }
    public static String searchUrl(String title, boolean bing) {
        String query = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (query.isEmpty()) throw new IllegalArgumentException("请填写歌名与作者");
        if (query.length() > 200) throw new IllegalArgumentException("歌名与作者请控制在200字以内");
        try {
            String encoded = URLEncoder.encode(query + " 曲谱", StandardCharsets.UTF_8.name());
            return (bing ? "https://cn.bing.com/search?q=" : "https://www.baidu.com/s?wd=") + encoded;
        } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    public static String pageUrl(String input) {
        try {
            String text = input == null ? "" : input.trim();
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (text.length() > 4096 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception error) { throw new IllegalArgumentException("请粘贴完整的 http 或 https 谱页链接"); }
    }
}
