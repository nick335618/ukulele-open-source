package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ABC 记谱法解析器（纯 Java、零依赖，可 JVM 单测）。
 *
 * <p><b>为什么要有它：</b>用户反馈「AI 曲谱助手不靠谱」，根因是让模型把网页正文直接「编」成
 * 演奏事件；现在改成 AI 只负责搜索/识别，App 对机器可读的 ABC 谱做确定性解析。
 * abcnotation.com 的曲谱页抓下来就是标准 ABC 文本，解析结果稳定、可复现。
 *
 * <p><b>支持子集：</b>X/T/M/L/Q/K 头字段；A-G/a-g 音高（大写 C4 八度、小写 C5 八度，
 * {@code '} 升八度、{@code ,} 降八度）；临时升降 ^ ^^ _ __ =；常用调号；时值后缀
 * 数字、/、/2、3/2、附点；连音 -；休止 z/Z/x；小节线（反复记号按普通小节线处理——
 * 单遍解析，可预测、不会把 42 音的小星星展开成 56/84 音）；和弦 [CEG] 取最高音；
 * 忽略装饰 {…}、!…!、+…+、{@code "…"}、%注释、w:/W: 歌词行、%% 指令行、& 叠音。
 *
 * <p><b>Markdown 反转义：</b>DeepSeek 的 fetch 接口返回 Markdown，abcnotation 页面里的
 * 冒号/竖线/点/引号等都被反斜杠转义（{@code X\:1}、{@code \|\:}）。{@link #extractAbc}
 * 先去掉这些前导反斜杠，再截取第一个 X: 到空行之间的 ABC 块。
 */
public final class AbcParser {

    /** 解析出的单个音符；{@code midi < 0} 表示休止。 */
    public static final class ParsedNote {
        public final int midi;
        public final long durationMs;

        public ParsedNote(int midi, long durationMs) {
            this.midi = midi;
            this.durationMs = Math.max(0, durationMs);
        }

        public boolean isRest() {
            return midi < 0;
        }

        @Override
        public String toString() {
            return (isRest() ? "REST" : String.valueOf(midi)) + "@" + durationMs;
        }
    }

    /** 解析结果；{@link #error} 非空表示失败（不抛异常，调用方直接展示原因）。 */
    public static final class ParseResult {
        public final List<ParsedNote> notes;
        public final String title;
        public final int bpm;
        public final String meter;
        public final String key;
        public final String error;
        public final long durationMs;

        private ParseResult(List<ParsedNote> notes, String title, int bpm,
                            String meter, String key, String error, long durationMs) {
            this.notes = Collections.unmodifiableList(notes);
            this.title = title == null ? "" : title;
            this.bpm = bpm;
            this.meter = meter == null ? "" : meter;
            this.key = key == null ? "" : key;
            this.error = error;
            this.durationMs = durationMs;
        }

        public boolean ok() {
            return error == null && !notes.isEmpty();
        }
    }

    /** 最少音符数：太短的多半是解析到页脚/歌词，宁可让调用方换来源。 */
    private static final int MIN_NOTES = 8;
    private static final int MAX_INPUT_CHARS = 200000;
    private static final int MAX_NOTES = 20000;

    /** 升号顺序 F C G D A E B（音级下标 0=C,1=D,2=E,3=F,4=G,5=A,6=B）。 */
    private static final int[] SHARP_ORDER = {3, 0, 4, 1, 5, 2, 6};
    /** 降号顺序 B E A D G C F。 */
    private static final int[] FLAT_ORDER = {6, 2, 5, 1, 4, 0, 3};
    /** 自然音音级到半音。 */
    private static final int[] LETTER_SEMITONE = {0, 2, 4, 5, 7, 9, 11};

    private AbcParser() {
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 从抓取到的 Markdown 里提取 ABC 块。找不到返回空串（不抛异常）。
     */
    public static String extractAbc(String fetchedMarkdown) {
        if (fetchedMarkdown == null || fetchedMarkdown.isEmpty()) {
            return "";
        }
        String text = unescapeMarkdown(fetchedMarkdown);
        String[] lines = text.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("^X\\s*:.*")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();
            // 空行 = ABC 块结束（abcnotation 页面在曲谱块后一定空一行再放页脚）。
            if (trimmed.isEmpty()) {
                break;
            }
            if (!isAbcBlockLine(trimmed)) {
                break;
            }
            builder.append(raw).append('\n');
        }
        return builder.toString().trim();
    }

    /** 解析 ABC 文本（可包含前导说明/页脚，内部会先截取 ABC 块）。永不抛异常。 */
    public static ParseResult parse(String abcText) {
        if (abcText == null || abcText.trim().isEmpty()) {
            return fail("没有 ABC 内容");
        }
        if (abcText.length() > MAX_INPUT_CHARS) {
            return fail("ABC 内容过长，无法解析");
        }
        try {
            // abcnotation 等站点对受版权保护的曲目只显示头信息："This tune may be copyright,
            // so no music is displayed here." —— 没有任何音符，必须明确跳过而不是当成功。
            String lower = abcText.toLowerCase(Locale.ROOT);
            if (lower.contains("so no music is displayed") || lower.contains("no music is displayed here")
                    || (lower.contains("copyright") && !lower.contains("k:") && !lower.contains("m:"))) {
                return fail("该页面为版权页，未显示 ABC 谱面");
            }
            String block = extractAbc(abcText);
            if (block.isEmpty()) {
                block = abcText.trim();
            }
            return parseBlock(block);
        } catch (Throwable t) {
            return fail("ABC 解析异常：" + safeMessage(t));
        }
    }

    private static ParseResult fail(String error) {
        return new ParseResult(new ArrayList<ParsedNote>(), "", 120, "", "", error, 0);
    }

    // ------------------------------------------------------------------ 头部字段

    private static final class Headers {
        String title = "";
        String meter = "4/4";
        int meterNum = 4;
        int meterDen = 4;
        double defaultLength = 1.0 / 8.0; // L: 缺省 1/8（按 ABC 常见约定）
        int quarterBpm = 120;
        String keyName = "C";
        int[] keyAlter = new int[7];
    }

    private static ParseResult parseBlock(String block) {
        Headers headers = new Headers();
        StringBuilder body = new StringBuilder();
        String[] lines = block.split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int comment = findCommentStart(line);
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
                if (line.isEmpty()) {
                    continue;
                }
            }
            if (line.startsWith("%%")) {
                continue;
            }
            if (line.matches("^[A-Za-z]\\s*:.*")) {
                char field = Character.toUpperCase(line.charAt(0));
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (field == 'X') {
                    continue;
                }
                if (field == 'T') {
                    if (headers.title.isEmpty()) {
                        headers.title = cleanupTitle(value);
                    }
                    continue;
                }
                if (field == 'M') {
                    parseMeter(value, headers);
                    continue;
                }
                if (field == 'L') {
                    double fraction = parseFraction(value);
                    if (fraction > 0 && fraction <= 1) {
                        headers.defaultLength = fraction;
                    }
                    continue;
                }
                if (field == 'Q') {
                    headers.quarterBpm = parseTempo(value, headers.quarterBpm);
                    continue;
                }
                if (field == 'K') {
                    applyKey(value, headers);
                    continue;
                }
                // C:/O:/Z:/N:/P:/R:/S:/B:/F:/w:/W: 等其它字段一律忽略。
                continue;
            }
            body.append(line).append('\n');
        }
        if (body.length() == 0) {
            return fail("ABC 里没有音符行（可能只有头信息/版权页）");
        }
        BodyParser parser = new BodyParser(headers);
        parser.parse(body.toString());
        List<ParsedNote> notes = parser.resultNotes();
        if (notes.isEmpty()) {
            return fail("ABC 里没有可解析的音符");
        }
        if (notes.size() < MIN_NOTES) {
            return new ParseResult(notes, headers.title, headers.quarterBpm, headers.meter,
                    headers.keyName, "ABC 音符过少（只有 " + notes.size() + " 个，至少需要 " + MIN_NOTES + " 个）",
                    sumDuration(notes));
        }
        return new ParseResult(notes, headers.title, headers.quarterBpm, headers.meter,
                headers.keyName, null, sumDuration(notes));
    }

    private static long sumDuration(List<ParsedNote> notes) {
        long total = 0;
        for (ParsedNote note : notes) {
            total += note.durationMs;
        }
        return total;
    }

    private static int findCommentStart(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '%' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static String cleanupTitle(String value) {
        if (value == null) {
            return "";
        }
        String title = value.trim();
        // 去掉 abcnotation 的编号后缀，例如 "Twinkle, Twinkle Little Star (#011)"。
        String cleaned = title.replaceAll("\\(#[0-9]+\\)\\s*$", "").trim();
        return cleaned.isEmpty() ? title : cleaned;
    }

    private static void parseMeter(String value, Headers headers) {
        String v = value.trim();
        if (v.startsWith("C|")) {
            headers.meter = "2/2";
            headers.meterNum = 2;
            headers.meterDen = 2;
            return;
        }
        if (v.startsWith("C")) {
            headers.meter = "4/4";
            headers.meterNum = 4;
            headers.meterDen = 4;
            return;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(v);
        if (matcher.find()) {
            int num = Integer.parseInt(matcher.group(1));
            int den = Integer.parseInt(matcher.group(2));
            if (num > 0 && den > 0) {
                headers.meterNum = num;
                headers.meterDen = den;
                headers.meter = num + "/" + den;
            }
        }
    }

    private static double parseFraction(String value) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(value);
        if (matcher.find()) {
            int num = Integer.parseInt(matcher.group(1));
            int den = Integer.parseInt(matcher.group(2));
            if (num > 0 && den > 0) {
                return (double) num / (double) den;
            }
        }
        return -1;
    }

    /** Q: 形如 "1/4=120"、"120"、"C"、"1/8 = 60"；缺省 120 BPM（四分音符每分钟）。 */
    private static int parseTempo(String value, int fallback) {
        String v = value == null ? "" : value.replace("\"", " ").trim();
        int equals = v.indexOf('=');
        if (equals >= 0) {
            String left = v.substring(0, equals).trim();
            String right = v.substring(equals + 1).trim();
            java.util.regex.Matcher numberMatcher =
                    java.util.regex.Pattern.compile("(\\d+)").matcher(right);
            if (!numberMatcher.find()) {
                return fallback;
            }
            int bpm = Integer.parseInt(numberMatcher.group(1));
            double unit = parseFraction(left);
            if (unit > 0 && unit <= 1) {
                // 单位是 whole-note 的比例：1/4 拍=四分音符，1/8 则把 8 分音符数换算成四分音符数。
                double quarterBeats = unit * 4.0;
                int converted = (int) Math.round(bpm * quarterBeats);
                return clampBpm(converted);
            }
            return clampBpm(bpm);
        }
        java.util.regex.Matcher numberMatcher = java.util.regex.Pattern.compile("(\\d+)").matcher(v);
        if (numberMatcher.find()) {
            return clampBpm(Integer.parseInt(numberMatcher.group(1)));
        }
        return fallback;
    }

    private static int clampBpm(int bpm) {
        if (bpm < 20) {
            return 20;
        }
        return Math.min(bpm, 400);
    }

    private static void applyKey(String value, Headers headers) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return;
        }
        String token = raw.split("\\s+")[0].replace("\"", "");
        int semitoneShift = 0;
        int minor = 0;
        int length = token.length();
        // 处理末尾的 m / min / minor / maj / mix / dor ... 等模式后缀。
        String lower = token.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{"minor", "min", "m"}) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                minor = 1;
                length = token.length() - suffix.length();
                lower = lower.substring(0, length);
                break;
            }
        }
        for (String suffix : new String[]{"major", "maj", "mixolydian", "mix", "dorian", "dor",
                "phrygian", "phr", "lydian", "lyd", "locrian", "loc", "ionian", "ion"}) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                length = token.length() - suffix.length();
                lower = lower.substring(0, length);
                break;
            }
        }
        if (token.length() == 0 || length <= 0) {
            return;
        }
        char letter = Character.toUpperCase(token.charAt(0));
        int letterIndex = "CDEFGAB".indexOf(letter);
        if (letterIndex < 0) {
            // K:HP（风笛调）等特殊值：按 D 大调两个升号处理，让音高尽量贴近。
            if ("hp".equalsIgnoreCase(token)) {
                applySharps(2, headers);
            }
            return;
        }
        int pc = LETTER_SEMITONE[letterIndex];
        for (int i = 1; i < length; i++) {
            char c = token.charAt(i);
            if (c == '#') {
                semitoneShift++;
            } else if (c == 'b') {
                semitoneShift--;
            } else {
                break;
            }
        }
        pc = ((pc + semitoneShift) % 12 + 12) % 12;
        if (minor == 1) {
            pc = (pc + 3) % 12; // 关系大调
        }
        int count = sharpsForPitchClass(pc);
        if (count >= 0) {
            applySharps(count, headers);
        } else {
            applyFlats(-count, headers);
        }
        headers.keyName = token;
    }

    /** 主音音级 → 调号升降数（正=升号、负=降号）；按五度圈查表。 */
    private static int sharpsForPitchClass(int pc) {
        switch (pc) {
            case 0:
                return 0;   // C
            case 7:
                return 1;   // G
            case 2:
                return 2;   // D
            case 9:
                return 3;   // A
            case 4:
                return 4;   // E
            case 11:
                return 5;   // B
            case 6:
                return 6;   // F#
            case 1:
                return 7;   // C#
            case 5:
                return -1;  // F
            case 10:
                return -2;  // Bb
            case 3:
                return -3;  // Eb
            case 8:
                return -4;  // Ab
            default:
                return 0;
        }
    }

    private static void applySharps(int count, Headers headers) {
        int[] alter = new int[7];
        for (int i = 0; i < Math.min(7, count); i++) {
            alter[SHARP_ORDER[i]] = 1;
        }
        headers.keyAlter = alter;
    }

    private static void applyFlats(int count, Headers headers) {
        int[] alter = new int[7];
        for (int i = 0; i < Math.min(7, count); i++) {
            alter[FLAT_ORDER[i]] = -1;
        }
        headers.keyAlter = alter;
    }

    // ------------------------------------------------------------------ 正文解析

    private static final class BodyParser {
        private final Headers headers;
        private final double beatMs;
        private final List<ParsedNote> notes = new ArrayList<ParsedNote>();
        private final Map<Integer, Integer> barAccidentals = new HashMap<Integer, Integer>();

        private String text = "";
        private int pos = 0;
        private int tieMidi = -1;
        private long tieDurationMs = 0;
        private double brokenFactor = 1.0;
        private int tupletRemaining = 0;
        private double tupletFactor = 1.0;

        BodyParser(Headers headers) {
            this.headers = headers;
            this.beatMs = 60000.0 / Math.max(20, headers.quarterBpm);
        }

        void parse(String body) {
            this.text = body;
            this.pos = 0;
            while (pos < text.length() && notes.size() < MAX_NOTES) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '%') {
                    skipToLineEnd();
                    continue;
                }
                if (c == '\\') {
                    pos++; // 行续接符
                    continue;
                }
                if (c == '"') {
                    skipQuoted();
                    continue;
                }
                if (c == '{') {
                    skipUntil('}');
                    continue;
                }
                if (c == '!' || c == '+') {
                    skipUntil(c);
                    continue;
                }
                if (c == '[') {
                    if (isInlineField()) {
                        consumeInlineField();
                    } else if (isChordStart()) {
                        parseChord();
                    } else {
                        // [1 / [2 反复段标记：忽略。
                        pos++;
                        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                            pos++;
                        }
                    }
                    continue;
                }
                if (c == ']' || c == '(' || c == ')' || c == '~' || c == '&') {
                    if (c == '(') {
                        parseTupletMarker();
                    } else if (c == '&') {
                        skipToLineEnd(); // 叠音声部：只保留主声部
                    } else {
                        pos++;
                    }
                    continue;
                }
                if (c == '>' || c == '<') {
                    handleBrokenRhythm(c);
                    continue;
                }
                if (isBarLineChar(c)) {
                    handleBarLine();
                    continue;
                }
                if (c == '^' || c == '_' || c == '=') {
                    parseAccidentalNote();
                    continue;
                }
                if (isNoteLetter(c)) {
                    parsePlainNote();
                    continue;
                }
                if (c == 'z' || c == 'Z' || c == 'x' || c == 'X') {
                    parseRest();
                    continue;
                }
                if (Character.isDigit(c) || c == '/' || c == '.' || c == '-' || c == ' ') {
                    pos++;
                    continue;
                }
                // 其它装饰字符（如 'o' 泛音、'u' 上弓等）直接跳过。
                pos++;
            }
            flushTie();
        }

        private void skipToLineEnd() {
            while (pos < text.length() && text.charAt(pos) != '\n') {
                pos++;
            }
        }

        private void skipQuoted() {
            pos++;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '\\' && pos < text.length()) {
                    pos++;
                    continue;
                }
                if (c == '"') {
                    return;
                }
            }
        }

        private void skipUntil(char endChar) {
            pos++;
            boolean inQuote = false;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (!inQuote && c == endChar) {
                    return;
                }
            }
        }

        private boolean isInlineField() {
            int i = pos + 1;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            if (i >= text.length() || !Character.isLetter(text.charAt(i))) {
                return false;
            }
            i++;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            return i < text.length() && text.charAt(i) == ':';
        }

        private void consumeInlineField() {
            int end = text.indexOf(']', pos + 1);
            if (end < 0) {
                pos++;
                return;
            }
            String content = text.substring(pos + 1, end).trim();
            pos = end + 1;
            if (content.length() >= 2 && Character.toUpperCase(content.charAt(0)) == 'K') {
                applyKey(content.substring(content.indexOf(':') + 1).trim(), headers);
                barAccidentals.clear();
            }
        }

        private boolean isChordStart() {
            int i = pos + 1;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            if (i >= text.length()) {
                return false;
            }
            char c = text.charAt(i);
            return isNoteLetter(c) || c == '^' || c == '_' || c == '=' || c == 'z' || c == 'Z';
        }

        /** [CEG] 和弦：旋律提取取最高音（和弦符号 "D" 之类已在前面被跳过）。 */
        private void parseChord() {
            int end = text.indexOf(']', pos + 1);
            if (end < 0) {
                pos++;
                return;
            }
            String content = text.substring(pos + 1, end);
            pos = end + 1;
            int bestMidi = -1;
            int i = 0;
            while (i < content.length()) {
                char c = content.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int accidental = 0;
                int consumed;
                int j = i;
                while (j < content.length() && (content.charAt(j) == '^' || content.charAt(j) == '_'
                        || content.charAt(j) == '=')) {
                    char a = content.charAt(j);
                    if (a == '^') {
                        accidental++;
                    } else if (a == '_') {
                        accidental--;
                    } else {
                        accidental = 99; // 还原记号
                    }
                    j++;
                }
                if (j >= content.length() || !isNoteLetter(content.charAt(j))) {
                    i = j + 1;
                    continue;
                }
                char letter = content.charAt(j);
                int letterIndex = "CDEFGAB".indexOf(Character.toUpperCase(letter));
                int octave = Character.isUpperCase(letter) ? 4 : 5;
                j++;
                while (j < content.length() && (content.charAt(j) == '\'' || content.charAt(j) == ',')) {
                    octave += content.charAt(j) == '\'' ? 1 : -1;
                    j++;
                }
                int midi = midiFor(letterIndex, octave,
                        accidental == 99 ? 0 : (accidental != 0 ? accidental : headers.keyAlter[letterIndex]));
                if (midi > bestMidi) {
                    bestMidi = midi;
                }
                i = j;
            }
            double length = parseLength();
            int dots = parseDots();
            long durationMs = durationMs(length, dots);
            if (bestMidi >= 0) {
                addNote(bestMidi, durationMs);
            } else {
                addRest(durationMs);
            }
        }

        private void parseTupletMarker() {
            pos++; // '('
            int number = 0;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                number = number * 10 + (text.charAt(pos) - '0');
                pos++;
            }
            if (number >= 3) {
                tupletRemaining = number;
                tupletFactor = 2.0 / number;
            }
        }

        private void handleBrokenRhythm(char c) {
            int count = 0;
            while (pos < text.length() && text.charAt(pos) == c) {
                count++;
                pos++;
            }
            // 单级 > ：当前音 ×3/2、下一个音 ×2/3；< 相反。多级按 2-1/2^n 近似。
            double first = 2.0 - 1.0 / Math.pow(2.0, count);
            double second = 1.0 / first;
            if (c == '>') {
                if (!notes.isEmpty()) {
                    ParsedNote last = notes.get(notes.size() - 1);
                    notes.set(notes.size() - 1, new ParsedNote(last.midi,
                            Math.round(last.durationMs * first)));
                }
                brokenFactor = second;
            } else {
                if (!notes.isEmpty()) {
                    ParsedNote last = notes.get(notes.size() - 1);
                    notes.set(notes.size() - 1, new ParsedNote(last.midi,
                            Math.round(last.durationMs * second)));
                }
                brokenFactor = first;
            }
        }

        private boolean isBarLineChar(char c) {
            return c == '|' || c == ':' || c == ']';
        }

        private void handleBarLine() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '|' || c == ':' || c == ']' || c == '[') {
                    pos++;
                } else {
                    break;
                }
            }
            // 小节线清空临时升降号，符合 ABC「升号仅在同一小节内有效」的语义。
            barAccidentals.clear();
        }

        private void parseAccidentalNote() {
            int accidental = 0;
            boolean natural = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '^') {
                    accidental++;
                    pos++;
                } else if (c == '_') {
                    accidental--;
                    pos++;
                } else if (c == '=') {
                    natural = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos >= text.length() || !isNoteLetter(text.charAt(pos))) {
                return;
            }
            parseNoteBody(natural ? 0 : accidental, true);
        }

        private void parsePlainNote() {
            parseNoteBody(0, false);
        }

        /**
         * @param explicitAlter 显式升降量（^=+1、_=-1、= 还原时为 0 且 explicit=true）
         * @param hasExplicit   是否有显式临时记号
         */
        private void parseNoteBody(int explicitAlter, boolean hasExplicit) {
            char letter = text.charAt(pos);
            int letterIndex = "CDEFGAB".indexOf(Character.toUpperCase(letter));
            if (letterIndex < 0) {
                pos++;
                return;
            }
            pos++;
            int octave = Character.isUpperCase(letter) ? 4 : 5;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\'') {
                    octave++;
                    pos++;
                } else if (c == ',') {
                    octave--;
                    pos++;
                } else {
                    break;
                }
            }
            int keyId = letterIndex * 20 + octave;
            int alter;
            if (hasExplicit) {
                alter = explicitAlter;
                barAccidentals.put(keyId, alter);
            } else if (barAccidentals.containsKey(keyId)) {
                alter = barAccidentals.get(keyId);
            } else {
                alter = headers.keyAlter[letterIndex];
            }
            int midi = midiFor(letterIndex, octave, alter);
            double length = parseLength();
            int dots = parseDots();
            long durationMs = durationMs(length, dots);
            // 连音：E- E 应合并成一个两倍时值的音符。
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
                tieMidi = midi;
                tieDurationMs = durationMs;
                pendingTieFollow = true;
                return;
            }
            if (pendingTieFollow && tieMidi == midi) {
                tieDurationMs += durationMs;
                pendingTieFollow = false;
                tieMidi = -1;
                addNote(midi, tieDurationMs);
                tieDurationMs = 0;
                return;
            }
            flushTie();
            addNote(midi, durationMs);
        }

        private boolean pendingTieFollow = false;

        private void flushTie() {
            if (pendingTieFollow && tieMidi >= 0) {
                addNote(tieMidi, tieDurationMs);
            }
            pendingTieFollow = false;
            tieMidi = -1;
            tieDurationMs = 0;
        }

        private void parseRest() {
            char kind = text.charAt(pos);
            pos++;
            long durationMs;
            if (kind == 'Z' || kind == 'X') {
                int count = 0;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    count = count * 10 + (text.charAt(pos) - '0');
                    pos++;
                }
                if (count <= 0) {
                    count = 1;
                }
                double measureWhole = (double) headers.meterNum / (double) headers.meterDen;
                durationMs = Math.round(measureWhole * 4.0 * beatMs * count);
            } else {
                double length = parseLength();
                int dots = parseDots();
                durationMs = durationMs(length, dots);
            }
            addRest(durationMs);
        }

        private int midiFor(int letterIndex, int octave, int alter) {
            int midi = 12 * (octave + 1) + LETTER_SEMITONE[letterIndex] + alter;
            return (midi >= 0 && midi <= 127) ? midi : -1;
        }

        /** 解析时值后缀：数字 / / /2 / 3/2 等，返回相对 L: 的倍数。 */
        private double parseLength() {
            int start = pos;
            int numerator = 0;
            boolean hasNumber = false;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                numerator = numerator * 10 + (text.charAt(pos) - '0');
                hasNumber = true;
                pos++;
            }
            int slashCount = 0;
            int explicitDen = 0;
            while (pos < text.length() && text.charAt(pos) == '/') {
                slashCount++;
                pos++;
                if (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    int value = 0;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        value = value * 10 + (text.charAt(pos) - '0');
                        pos++;
                    }
                    if (explicitDen == 0) {
                        explicitDen = value;
                    }
                    break;
                }
            }
            if (!hasNumber && slashCount == 0) {
                pos = start;
                return 1.0;
            }
            double value = hasNumber ? numerator : 1.0;
            if (slashCount > 0) {
                if (explicitDen > 0) {
                    value /= explicitDen;
                } else {
                    value /= Math.pow(2.0, slashCount);
                }
            }
            return value;
        }

        private int parseDots() {
            int dots = 0;
            while (pos < text.length() && text.charAt(pos) == '.') {
                dots++;
                pos++;
            }
            return dots;
        }

        /** 时值：L 的比例 → 毫秒；再乘附点/连音/连音组/broken rhythm 系数。 */
        private long durationMs(double lengthMultiplier, int dots) {
            double wholeLength = headers.defaultLength * lengthMultiplier;
            double ms = wholeLength * 4.0 * beatMs;
            if (dots > 0) {
                ms *= (2.0 - Math.pow(2.0, -dots));
            }
            if (tupletRemaining > 0) {
                ms *= tupletFactor;
                tupletRemaining--;
            }
            if (brokenFactor != 1.0) {
                ms *= brokenFactor;
                brokenFactor = 1.0;
            }
            return Math.max(1, Math.round(ms));
        }

        private void addNote(int midi, long durationMs) {
            if (midi < 0) {
                addRest(durationMs);
                return;
            }
            notes.add(new ParsedNote(midi, Math.max(1, durationMs)));
        }

        private void addRest(long durationMs) {
            notes.add(new ParsedNote(-1, Math.max(1, durationMs)));
        }

        List<ParsedNote> resultNotes() {
            return notes;
        }
    }

    // ------------------------------------------------------------------ 通用工具

    private static boolean isNoteLetter(char c) {
        return (c >= 'A' && c <= 'G') || (c >= 'a' && c <= 'g');
    }

    /**
     * 去掉 Markdown 的前导反斜杠转义：DeepSeek fetch 返回的页面里，冒号/竖线/点/引号/
     * 括号等标点统一带一个反斜杠（如 {@code X\:1}、{@code \|\:}），不还原就无法识别 ABC。
     */
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

    private static boolean isAbcBlockLine(String trimmedLine) {
        if (trimmedLine.matches("^[A-Za-z]\\s*:.*")) {
            return true;
        }
        // ABC 正文里的 % 注释行（例如 happy_birthday 谱中间的 "% - - -"）属于曲谱块，
        // 不能把它当成页脚而提前截断。
        if (trimmedLine.startsWith("%")) {
            return true;
        }
        String cleaned = trimmedLine;
        int comment = findCommentStart(cleaned);
        if (comment >= 0) {
            cleaned = cleaned.substring(0, comment);
        }
        cleaned = cleaned.replaceAll("\"[^\"]*\"", "");
        // 装饰记号（!trill!、{grace}、+accent+）内部的字母不在音符字符集里，先整体去掉再判断，
        // 否则带装饰的合法曲谱行会被误判成正文而提前截断 ABC 块。
        cleaned = cleaned.replaceAll("![^!]*!", "").replaceAll("\\{[^}]*\\}", "")
                .replaceAll("\\+[^+]*\\+", "");
        if (cleaned.trim().isEmpty()) {
            return true;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            boolean allowed = Character.isWhitespace(c)
                    || (c >= 'A' && c <= 'G') || (c >= 'a' && c <= 'g')
                    || c == 'z' || c == 'Z' || c == 'x' || c == 'X'
                    || (c >= '0' && c <= '9')
                    || "|:,.'^_=[]{}!+<>()~&\\/-".indexOf(c) >= 0;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        String flat = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }
}
