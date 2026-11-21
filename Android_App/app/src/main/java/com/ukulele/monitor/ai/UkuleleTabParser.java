package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ukulele 四线 tab 解析器（纯 Java、零依赖，可 JVM 单测）。
 *
 * <p><b>为什么需要：</b>用户要求「导入乐谱不能只搜 abcnotation，ukulele facilele 等也要有」。
 * ukutabs / ukulele-tabs / ukulele-facile 这类站点以「四行品格 tab」为主（如
 * {@code A|---------0-0--|}），它是机器可读的品格数字，可以确定性转成演奏事件；
 * 但很多页面只有和弦名+歌词或图片谱，这时必须明确「未采用」，绝不能编造旋律。
 *
 * <p><b>解析规则：</b>识别连续 4 行品格线：
 * <ul>
 *   <li>行首可带弦名 G/C/E/A（顺序任意，常见 AECG 或 GCEA；大小写不敏感）；</li>
 *   <li>无弦名时要求恰好 4 行连续品格线，按 G→C→E→A 顺序解释；</li>
 *   <li>字符列对齐；数字 0-18 为品位，{@code x}/{@code X} 为不弹，{@code - | 空格 ~ +} 为
 *       延音/分隔；parse()为旧旋律接口取最高音；实际导入使用parseColumns()保留多音和原弦位；</li>
 *   <li>多位数品（10-18）按相邻数字解析；超界交由事件生成器明确报错；</li>
 *   <li>每列默认 250ms（即 BPM 120 的八分音符）；文本里出现 {@code BPM:120} / {@code BPM=120}
 *       时按 quarter=60000/BPM、列=半拍重算。</li>
 * </ul>
 *
 * <p>输入可以是纯文本、SearchResult.content 或整个 HTML；会先去标签/实体
 * （见 {@link #htmlToText}）。
 */
public final class UkuleleTabParser {

    /** 空弦 MIDI：物理顺序 G,C,E,A（高 G 定弦）。 */
    private static final int[] OPEN_MIDI = {67, 60, 64, 69};
    private static final char[] GCEA = {'G', 'C', 'E', 'A'};
    private static final int MAX_FRET = 18;
    private static final long DEFAULT_COLUMN_MS = 250L;
    private static final double DEFAULT_BPM = 120.0;

    /** 带弦名的品格线（允许行前缀有 UI 文本，如 ukulele-tabs 的 "Font size: A- A A+A|---"）。 */
    private static final Pattern LABELED_LINE = Pattern.compile(
            "(?:^|[\\s+:])([GCEA])\\s*[|:]?([-0-9xX|~+ ]{3,})\\s*$");
    /** 不带弦名的品格线：至少 2 个 - 且含数字/x。 */
    private static final Pattern BARE_LINE = Pattern.compile(
            "^\\s*([-0-9xX|~+]{3,})\\s*$");
    private static final Pattern BPM_PATTERN = Pattern.compile(
            "(?i)\\bBPM\\s*[:=]?\\s*(\\d{2,3})\\b");

    /** 解析结果；{@link #error} 非空表示未找到可用 tab。 */
    public static final class ParseResult {
        public final List<AbcParser.ParsedNote> notes;
        /** 成功解析的 tab 行数（4 的倍数）。 */
        public final int lineCount;
        /** 解析出的音符列数。 */
        public final int columnCount;
        public final String error;

        private ParseResult(List<AbcParser.ParsedNote> notes, int lineCount, int columnCount,
                            String error) {
            this.notes = Collections.unmodifiableList(notes);
            this.lineCount = lineCount;
            this.columnCount = columnCount;
            this.error = error;
        }

        public boolean ok() {
            return error == null && !notes.isEmpty();
        }

        public long durationMs() {
            long total = 0;
            for (AbcParser.ParsedNote note : notes) {
                total += note.durationMs;
            }
            return total;
        }
    }

    private UkuleleTabParser() {
    }

    /** 解析文本（可以是整页正文/HTML）。永不抛异常。 */
    public static ParseResult parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return fail("没有 tab 文本");
        }
        String plain = text.indexOf('<') >= 0 ? htmlToText(text) : text;
        if (plain.trim().isEmpty()) {
            return fail("页面里没有可读文本");
        }
        ScanResult scan = scanTab(plain);
        if (scan.tabLines == 0) {
            return fail("没有找到 4 行品格 tab（页面可能只有和弦/歌词或图片谱）");
        }
        List<AbcParser.ParsedNote> notes = new ArrayList<AbcParser.ParsedNote>();
        for (Column column : scan.columns) {
            if (column.midis.isEmpty()) {
                continue;
            }
            int highest = -1;
            for (Integer midi : column.midis) {
                if (midi != null && midi > highest) {
                    highest = midi;
                }
            }
            if (highest > 0) {
                notes.add(new AbcParser.ParsedNote(highest, column.durationMs));
            }
        }
        if (notes.isEmpty()) {
            return fail("tab 里没有可演奏的音符");
        }
        return new ParseResult(notes, scan.tabLines, scan.columnCount, null);
    }

    /**
     * 列级解析：同一列多根弦的数字保留为「和弦/双音」，而不是只取最高音。
     *
     * <p>「相册识谱 → TAB」需要它：弹唱谱里同一列常有 0/0/0/3 这样的和弦按法，
     * 只取最高音会把 C 和弦弹成单音；列级结果可以同时发声，听感才对。
     */
    public static List<Column> parseColumns(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String plain = text.indexOf('<') >= 0 ? htmlToText(text) : text;
        if (plain.trim().isEmpty()) {
            return Collections.emptyList();
        }
        ScanResult scan = scanTab(plain);
        if (scan.tabLines == 0) {
            return Collections.emptyList();
        }
        return scan.columns;
    }

    /** 一个字符列：同列多弦的 MIDI 同时发声；空列表表示该列没有按弦。 */
    public static final class Column {
        public final List<Integer> midis;
        public final List<Integer> strings, frets;
        public final int invalidFrets;
        public final long durationMs;

        private Column(List<Integer> midis, List<Integer> strings, List<Integer> frets, int invalidFrets, long durationMs) {
            this.strings=Collections.unmodifiableList(strings);this.frets=Collections.unmodifiableList(frets);this.invalidFrets=invalidFrets;
            this.midis = Collections.unmodifiableList(midis);
            this.durationMs = durationMs;
        }

        public boolean isRest() {
            return midis.isEmpty();
        }
    }

    private static final class ScanResult {
        final List<Column> columns = new ArrayList<Column>();
        int tabLines;
        int columnCount;
    }

    private static ScanResult scanTab(String plain) {
        ScanResult result = new ScanResult();
        double bpm = findBpm(plain);
        long columnMs = Math.max(60L, Math.round(60000.0 / bpm / 2.0));
        String[] lines = plain.replace("\r", "").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (isLabeledTabLine(lines[i])) {
                List<String> labelList = new ArrayList<String>();
                List<String> bodyList = new ArrayList<String>();
                int j = i;
                while (j < lines.length) {
                    LabeledLine labeled = parseLabeledLine(lines[j]);
                    if (labeled == null) {
                        break;
                    }
                    labelList.add(labeled.label);
                    bodyList.add(labeled.body);
                    j++;
                }
                if (j - i < 4 || !hasAllFourLabels(labelList)) {
                    i = j > i ? j : i + 1;
                    continue;
                }
                int groupStart = 0;
                while (groupStart + 3 < labelList.size()) {
                    String[] groupLabels = new String[4];
                    String[] groupBodies = new String[4];
                    for (int k = 0; k < 4; k++) {
                        groupLabels[k] = labelList.get(groupStart + k);
                        groupBodies[k] = bodyList.get(groupStart + k);
                    }
                    if (!hasAllFourLabels(java.util.Arrays.asList(groupLabels))) {
                        groupStart++;
                        continue;
                    }
                    int before = result.columns.size();
                    int cols = appendTabGroupColumns(groupLabels, groupBodies, columnMs,
                            result.columns);
                    if (result.columns.size() > before) {
                        result.tabLines += 4;
                        result.columnCount += cols;
                    }
                    groupStart += 4;
                }
                i = j;
                continue;
            }
            if (isBareTabLine(lines[i])) {
                int j = i;
                while (j < lines.length && isBareTabLine(lines[j])) {
                    j++;
                }
                if (j - i >= 4) {
                    int groupStart = i;
                    while (groupStart + 3 < j) {
                        String[] groupBodies = new String[4];
                        String[] groupLabels = new String[4];
                        for (int k = 0; k < 4; k++) {
                            groupBodies[k] = lines[groupStart + k].trim();
                            groupLabels[k] = String.valueOf(GCEA[k]);
                        }
                        int before = result.columns.size();
                        int cols = appendTabGroupColumns(groupLabels, groupBodies, columnMs,
                                result.columns);
                        if (result.columns.size() > before) {
                            result.tabLines += 4;
                            result.columnCount += cols;
                        }
                        groupStart += 4;
                    }
                }
                i = j > i ? j : i + 1;
                continue;
            }
            i++;
        }
        return result;
    }

    /** HTML → 近似纯文本（去标签、解常见实体、保留换行）。公开便于单测。 */
    public static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String text = html;
        // pre/br/p/div/li 等块级标签转成换行，保证 tab 行不会粘在一起。
        text = text.replaceAll("(?i)<\\s*(br|/p|/div|/li|/tr|/pre|/h[1-6]|/table)\\s*/?>", "\n");
        text = text.replaceAll("(?i)</?[a-zA-Z][^>]*>", "");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'");
        return text;
    }

    /** 是否含 4 行品格 tab（快速判断，供搜索管线记录 skipped 原因）。 */
    public static boolean looksLikeTab(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String plain = text.indexOf('<') >= 0 ? htmlToText(text) : text;
        String[] lines = plain.replace("\r", "").split("\n", -1);
        int consecutive = 0;
        for (String line : lines) {
            if (isLabeledTabLine(line) || isBareTabLine(line)) {
                consecutive++;
                if (consecutive >= 4) {
                    return true;
                }
            } else if (!line.trim().isEmpty()) {
                consecutive = 0;
            }
        }
        return false;
    }

    /**
     * 只抽取页面里的 tab 行（4 行一组），用于候选的 transcription（UI 可编辑展示）。
     * 找不到返回空串。
     */
    public static String extractTabText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String plain = text.indexOf('<') >= 0 ? htmlToText(text) : text;
        String[] lines = plain.replace("\r", "").split("\n", -1);
        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (isLabeledTabLine(lines[i]) || isBareTabLine(lines[i])) {
                int j = i;
                while (j < lines.length && (isLabeledTabLine(lines[j]) || isBareTabLine(lines[j]))) {
                    j++;
                }
                if (j - i >= 4) {
                    for (int k = i; k < j; k++) {
                        String cleaned;
                        LabeledLine labeled = parseLabeledLine(lines[k]);
                        if (labeled != null) {
                            cleaned = labeled.label + "|" + labeled.body;
                        } else {
                            cleaned = lines[k].trim();
                        }
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(cleaned);
                    }
                    builder.append('\n');
                }
                i = j;
                continue;
            }
            i++;
        }
        return builder.toString().trim();
    }

    // ------------------------------------------------------------------ 内部实现

    private static int appendTabGroupColumns(String[] labels, String[] bodies, long columnMs,
                                             List<Column> columns) {
        // 统一按 G,C,E,A 顺序整理 4 行；label 来自页面标签。
        String[] byString = new String[4];
        for (int k = 0; k < labels.length; k++) {
            int index = indexOfString(labels[k]);
            if (index >= 0 && index < 4 && byString[index] == null) {
                byString[index] = bodies[k];
            }
        }
        for (int k = 0; k < 4; k++) {
            if (byString[k] == null) {
                return 0;
            }
        }
        int maxLength = 0;
        for (String body : byString) {
            maxLength = Math.max(maxLength, body.length());
        }
        int count = 0;
        int column = 0;
        boolean[][] consumed = new boolean[4][maxLength];
        while (column < maxLength) {
            List<Integer> midis = new ArrayList<Integer>();
            List<Integer> strings=new ArrayList<>(), frets=new ArrayList<>();int invalid=0;
            boolean anyDigit = false;
            for (int stringIndex = 0; stringIndex < 4; stringIndex++) {
                String body = byString[stringIndex];
                if (column >= body.length() || consumed[stringIndex][column]) {
                    continue;
                }
                char c = body.charAt(column);
                if (c >= '0' && c <= '9') {
                    int fret = 0;
                    int j = column;
                    while (j < body.length() && body.charAt(j) >= '0' && body.charAt(j) <= '9') {
                        fret = Math.min(9999, fret * 10 + (body.charAt(j) - '0'));
                        if (j > column) {
                            consumed[stringIndex][j] = true; // 多位数品：后续列不再重复算一个音
                        }
                        j++;
                    }
                    anyDigit = true;
                    if (fret >= 0 && fret <= MAX_FRET) {
                        midis.add(OPEN_MIDI[stringIndex] + fret);strings.add(stringIndex);frets.add(fret);
                    } else { invalid++;
                    }
                }
            }
            if (anyDigit) {
                count++;
                columns.add(new Column(midis, strings, frets, invalid, columnMs));
            }
            column++;
        }
        return count;
    }

    private static boolean hasAllFourLabels(List<String> labels) {
        if (labels == null || labels.size() < 4) {
            return false;
        }
        boolean g = false;
        boolean c = false;
        boolean e = false;
        boolean a = false;
        for (String label : labels) {
            if ("G".equals(label)) {
                g = true;
            } else if ("C".equals(label)) {
                c = true;
            } else if ("E".equals(label)) {
                e = true;
            } else if ("A".equals(label)) {
                a = true;
            }
        }
        return g && c && e && a;
    }

    private static int indexOfString(String label) {
        if (label == null || label.isEmpty()) {
            return -1;
        }
        char c = Character.toUpperCase(label.charAt(0));
        for (int i = 0; i < GCEA.length; i++) {
            if (GCEA[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static final class LabeledLine {
        final String label;
        final String body;

        LabeledLine(String label, String body) {
            this.label = label;
            this.body = body;
        }
    }

    /**
     * 解析一行带标签的 tab。允许行首有页面 UI 前缀（真实 ukulele-tabs 正文里第一行是
     * "Font size: A- A A+A|---0--|"），所以取行内的最后一个「弦名+竖线+品格线」匹配；
     * 四行都按同一规则取 body，列对齐就与页面正文一致。
     */
    private static LabeledLine parseLabeledLine(String line) {
        if (line == null) {
            return null;
        }
        String normalized = line.replace('\t', ' ').trim();
        if (normalized.isEmpty()) {
            return null;
        }
        Matcher matcher = LABELED_LINE.matcher(normalized);
        LabeledLine found = null;
        while (matcher.find()) {
            String body = matcher.group(2);
            if (looksLikeTabBody(body)) {
                found = new LabeledLine(matcher.group(1), body);
            }
        }
        return found;
    }

    private static boolean isLabeledTabLine(String line) {
        return parseLabeledLine(line) != null;
    }

    private static boolean isBareTabLine(String line) {
        if (line == null) {
            return false;
        }
        Matcher matcher = BARE_LINE.matcher(line);
        if (!matcher.matches()) {
            return false;
        }
        String body=matcher.group(1);
        return body.length()-body.replace("-", "").length()>=2 && looksLikeTabBody(body);
    }

    private static boolean looksLikeTabBody(String body) {
        if (body == null || body.length() < 3) {
            return false;
        }
        int dashes = 0;
        int digits = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '-') {
                dashes++;
            } else if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits>=1 || dashes>=2 || body.indexOf('x')>=0 || body.indexOf('X')>=0;
    }

    private static double findBpm(String text) {
        Matcher matcher = BPM_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 20 && value <= 400) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_BPM;
    }

    private static ParseResult fail(String error) {
        return new ParseResult(new ArrayList<AbcParser.ParsedNote>(), 0, 0, error);
    }
}
