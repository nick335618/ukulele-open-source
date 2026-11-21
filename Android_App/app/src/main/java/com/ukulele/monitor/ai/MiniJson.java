package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 序列化工具（纯 Java，零依赖，可脱离 Android 在 JVM 上单测）。
 *
 * <p><b>为什么不用 org.json：</b>Android SDK 里的 {@code org.json} 在普通桌面 JVM 上只是
 * 桩实现（方法体是 {@code throw new RuntimeException("Stub!")}），而 AI 核心层的关键逻辑
 * （事件行提取、灯效参数解析、在线搜索响应的字段读取）必须在 JVM 上做单元测试；引入第三方
 * JSON 库又会给 APK 增加依赖。这里只实现 DeepSeek 接口实际用到的 JSON 子集：
 * 对象、数组、字符串、数字、布尔和 null。
 *
 * <p><b>数据映射：</b>{@link #parse(String)} 返回 {@link LinkedHashMap}（保持键顺序）、
 * {@link ArrayList}、{@link String}、{@link Double}、{@link Boolean} 或 {@code null}。
 * 数字统一用 {@link Double} 承载，调用方按需取整（{@link #asInt}）。
 *
 * <p><b>线程安全：</b>无状态，所有方法可并发调用。
 */
public final class MiniJson {

    /** 单次解析允许的最大嵌套层数，防止恶意/损坏响应造成栈溢出。 */
    private static final int MAX_DEPTH = 64;

    private MiniJson() {
    }

    // ------------------------------------------------------------------ 构造便捷方法

    /** 新建空对象（{@link LinkedHashMap}，保持插入顺序）。 */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<String, Object>();
    }

    /** 新建空数组。 */
    public static List<Object> arr() {
        return new ArrayList<Object>();
    }

    /** 往对象里放一个键值对并返回该对象，方便链式构造请求体。null 键会被忽略。 */
    public static Map<String, Object> put(Map<String, Object> obj, String key, Object value) {
        if (obj != null && key != null) {
            obj.put(key, value);
        }
        return obj;
    }

    /** 往数组里追加一个元素并返回该数组。 */
    public static List<Object> add(List<Object> array, Object value) {
        if (array != null) {
            array.add(value);
        }
        return array;
    }

    // ------------------------------------------------------------------ 类型读取工具

    /** 把值当对象读；不是对象（含 null）返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** 把值当数组读；不是数组（含 null）返回 null。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    /** 从对象里取一个对象字段；键不存在或类型不符返回 null。 */
    public static Map<String, Object> object(Map<String, Object> obj, String key) {
        return obj == null ? null : asObject(obj.get(key));
    }

    /** 从对象里取一个数组字段；键不存在或类型不符返回 null。 */
    public static List<Object> array(Map<String, Object> obj, String key) {
        return obj == null ? null : asArray(obj.get(key));
    }

    /** 从对象里取字符串字段；null 或非字符串返回 {@code def}。 */
    public static String asString(Object value, String def) {
        return value instanceof String ? (String) value : def;
    }

    /** 从对象里取字符串字段；键不存在/非字符串返回 {@code def}。 */
    public static String string(Map<String, Object> obj, String key, String def) {
        return obj == null ? def : asString(obj.get(key), def);
    }

    /**
     * 把任意标量转成展示用文本：字符串原样、数字不带多余小数、布尔 true/false、
     * null 返回 {@code def}；对象/数组用 JSON 序列化兜底（接口偶尔会把数字字段返回成对象）。
     */
    public static String text(Object value, String def) {
        if (value == null) {
            return def;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return stringify(value);
        }
        try {
            return stringify(value);
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 从对象里取展示文本；键不存在返回 {@code def}。 */
    public static String text(Map<String, Object> obj, String key, String def) {
        return obj == null ? def : text(obj.get(key), def);
    }

    /** 把值当数值读：Number 直接转，数字字符串尝试解析，失败返回 {@code def}。 */
    public static double asDouble(Object value, double def) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    /** 从对象里取数值；失败返回 {@code def}。 */
    public static double number(Map<String, Object> obj, String key, double def) {
        return obj == null ? def : asDouble(obj.get(key), def);
    }

    /** 把值当整数读（四舍五入），失败返回 {@code def}。 */
    public static int asInt(Object value, int def) {
        double d = asDouble(value, Double.NaN);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return def;
        }
        long rounded = Math.round(d);
        if (rounded > Integer.MAX_VALUE || rounded < Integer.MIN_VALUE) {
            return def;
        }
        return (int) rounded;
    }

    /** 从对象里取整数；失败返回 {@code def}。 */
    public static int integer(Map<String, Object> obj, String key, int def) {
        return obj == null ? def : asInt(obj.get(key), def);
    }

    /** 把值当布尔读：Boolean 原样，字符串 true/false 与数字 0/1 也认，其余返回 {@code def}。 */
    public static boolean asBoolean(Object value, boolean def) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                return false;
            }
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return def;
    }

    /** 从对象里取布尔；失败返回 {@code def}。 */
    public static boolean bool(Map<String, Object> obj, String key, boolean def) {
        return obj == null ? def : asBoolean(obj.get(key), def);
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析 JSON 文本。
     *
     * @return Map / List / String / Double / Boolean / null
     * @throws IllegalArgumentException 语法错误（消息带字符位置，方便定位接口返回的脏数据）
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 内容为空");
        }
        String source = text;
        // 有些接口/文本文件会带 UTF-8 BOM，先剥掉，否则第一个字符会被当成非法 token。
        if (source.length() > 0 && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        Parser parser = new Parser(source);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("JSON 结束后还有多余内容");
        }
        return value;
    }

    /** 递归下降解析器。持有输入与游标，只在单次 {@link #parse} 内使用。 */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        IllegalArgumentException error(String message) {
            int around = pos;
            int start = Math.max(0, around - 12);
            int end = Math.min(text.length(), around + 12);
            String snippet = text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
            return new IllegalArgumentException("JSON 解析失败（位置 " + around + "）：" + message + "，附近内容：「" + snippet + "」");
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("嵌套层数过多");
            }
            skipWhitespace();
            if (atEnd()) {
                throw error("内容意外结束");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject(depth + 1);
            }
            if (c == '[') {
                return parseArray(depth + 1);
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' && text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw error("无法识别的字符 '" + c + "'");
        }

        Map<String, Object> parseObject(int depth) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            pos++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("对象键必须是字符串");
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw error("对象键后缺少 ':'");
                }
                pos++;
                result.put(key, parseValue(depth));
                skipWhitespace();
                if (atEnd()) {
                    throw error("对象未闭合");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return result;
                }
                throw error("对象里缺少 ',' 或 '}'");
            }
        }

        List<Object> parseArray(int depth) {
            List<Object> result = new ArrayList<Object>();
            pos++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue(depth));
                skipWhitespace();
                if (atEnd()) {
                    throw error("数组未闭合");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return result;
                }
                throw error("数组里缺少 ',' 或 ']'");
            }
        }

        String parseString() {
            pos++; // 开头的引号
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    // 直接接受任意非转义字符（含中文、emoji 的代理对），不做额外校验。
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("转义符后内容结束");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error("\\u 转义不完整");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("\\u 转义不是合法十六进制");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("未知转义字符 '\\" + esc + "'");
                }
            }
        }

        Double parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String token = text.substring(start, pos);
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException e) {
                throw error("数字格式错误：" + token);
            }
        }
    }

    // ------------------------------------------------------------------ 序列化

    /**
     * 把对象序列化成 JSON 文本。支持 Map、Iterable、Object[]、String、Number、Boolean、null；
     * 其它类型走 {@link String#valueOf(Object)} 并按字符串输出，避免因为一个未知字段导致整个请求失败。
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(sb, (String) value);
            return;
        }
        if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
            return;
        }
        if (value instanceof Number) {
            writeNumber(sb, (Number) value);
            return;
        }
        if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Object entryObj : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey() == null ? "" : String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
            return;
        }
        if (value instanceof Object[]) {
            sb.append('[');
            Object[] array = (Object[]) value;
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, array[i]);
            }
            sb.append(']');
            return;
        }
        // 未知类型兜底：按字符串输出。AI 请求体里出现未知类型通常是我们自己的数据，
        // 转成字符串比抛异常让整个对话失败更安全。
        writeString(sb, String.valueOf(value));
    }

    private static void writeNumber(StringBuilder sb, Number number) {
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON 没有 NaN/Infinity，按 null 输出；接口端会把它当缺失字段。
            sb.append("null");
            return;
        }
        if (d == Math.rint(d) && Math.abs(d) <= 1e15) {
            // 整数值输出成不带 ".0" 的形式：接口对 max_tokens 之类的整数参数更友好，
            // 也避免 6.0 这种写法在部分兼容层被当成浮点。
            sb.append(Long.toString((long) d));
            return;
        }
        sb.append(Double.toString(d));
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        // 其余控制字符必须转义，否则 JSON 非法。
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        // 中文/emoji 原样输出：请求体统一按 UTF-8 编码，比反斜杠-u 转义更省体积。
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
