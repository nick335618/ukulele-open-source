package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 编配器：把确定性的音高序列（ABC / 简谱解析结果）编配成 App 可导入的 UKS 事件文本。
 *
 * <p><b>为什么单独一层：</b>解析器只负责「谱面 → 音高/时值」，编配负责「音高 → 哪根弦第几品
 * + 设备事件编码」。两层都是纯函数、可单测，AI 不再参与，结果可复现。
 *
 * <p><b>事件格式（与工程导入器一致）：</b>{@code delta_ms,mask,row,level,flags}
 * <ul>
 *   <li>每音先 note-on：{@code delta, mask, fret, 210, 0}（mask 单 bit，bit0=G、bit1=C、
 *       bit2=E、bit3=A）；</li>
 *   <li>再 note-off：{@code 时值ms, 0, 0, 0, 0}；</li>
 *   <li>休止/无法编配的音并入下一个事件的 delta，保证总时长 = 各音时值之和；</li>
 *   <li>delta 超过 60000ms 时拆成多条（设备/导入器用 16 位 delta）。</li>
 * </ul>
 *
 * <p><b>选弦规则：</b>高 G 定弦 G4=67 / C4=60 / E4=64 / A4=69（物理列 G、C、E、A）；
 * 在 0..18 品内选**最低品位**，并列时按 G/C/E/A 顺序取先者。超出音域的音跳过并计数，
 * 覆盖率过低时拒绝，避免导入一首「缺一半音」的曲子。
 */
public final class ScoreArranger {

    /** 物理列顺序（与 App/GCEA 一致）：G,C,E,A 的空弦 MIDI。 */
    public static final int[] OPEN_MIDI = {67, 60, 64, 69};
    public static final int MAX_FRET = 18;
    /** 设备事件默认力度与 flags（与 assets 内置曲谱一致）。 */
    public static final int DEFAULT_LEVEL = 210;
    public static final int DEFAULT_FLAGS = 0;
    /** 单个事件 delta 上限（16 位字段，留一点余量）。 */
    public static final int MAX_DELTA_MS = 60000;
    /** UKS1 事件数上限（与工程导入器一致）。 */
    private static final int MAX_EVENTS = 10922;
    private static final int MIN_NOTES = 1;
    private static final double MIN_COVERAGE = 0.5;

    /** 编配结果；{@link #error} 非空表示失败。 */
    public static final class Arrangement {
        public final String eventsText;
        public final int totalNotes;
        public final int skipped;
        public final long durationMs;
        public final double coverage;
        public final String error;

        private Arrangement(String eventsText, int totalNotes, int skipped,
                            long durationMs, double coverage, String error) {
            this.eventsText = eventsText == null ? "" : eventsText;
            this.totalNotes = totalNotes;
            this.skipped = skipped;
            this.durationMs = durationMs;
            this.coverage = coverage;
            this.error = error;
        }

        public boolean ok() {
            return error == null && totalNotes > 0 && !eventsText.isEmpty();
        }
    }

    private ScoreArranger() {
    }

    /**
     * 编配。输入中 {@code midi < 0} 视为休止。永不抛异常。
     */
    public static Arrangement arrange(List<AbcParser.ParsedNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return failure("没有可编配的音符");
        }
        int sounding = 0;
        for (AbcParser.ParsedNote note : notes) {
            if (note != null && !note.isRest()) {
                sounding++;
            }
        }
        if (sounding == 0) {
            return failure("全部是休止符，没有可演奏的音符");
        }
        List<String> lines = new ArrayList<String>();
        long pendingDeltaMs = 0;
        long durationMs = 0;
        int arranged = 0;
        int skipped = 0;
        for (AbcParser.ParsedNote note : notes) {
            if (note == null) {
                continue;
            }
            durationMs += note.durationMs;
            if (note.isRest()) {
                pendingDeltaMs += note.durationMs;
                continue;
            }
            int[] placement = lowestPlacement(note.midi);
            if (placement == null) {
                skipped++;
                // 超音域音仍然占用时值，否则后续节奏会整体前移。
                pendingDeltaMs += note.durationMs;
                continue;
            }
            appendEvent(lines, pendingDeltaMs, placement[0], placement[1], DEFAULT_LEVEL, DEFAULT_FLAGS);
            pendingDeltaMs = 0;
            appendEvent(lines, note.durationMs, 0, 0, 0, 0);
            arranged++;
            if (lines.size() > MAX_EVENTS) {
                return failure("曲谱过长（事件数超过设备容量），请缩短或分段导入");
            }
        }
        if (pendingDeltaMs > 0) {
            // 尾部休止：补一条静音事件，让时长统计与播放时钟一致。
            appendEvent(lines, pendingDeltaMs, 0, 0, 0, 0);
        }
        if (arranged < MIN_NOTES) {
            return new Arrangement("", arranged, skipped, durationMs,
                    sounding > 0 ? arranged / (double) sounding : 0,
                    "可编配音符过少（" + arranged + " 个，至少需要 " + MIN_NOTES + " 个）");
        }
        double coverage = arranged / (double) sounding;
        if (coverage < MIN_COVERAGE) {
            return new Arrangement("", arranged, skipped, durationMs, coverage,
                    "太多音符超出高 G 定弦 0~18 品音域（覆盖率 "
                            + Math.round(coverage * 100) + "%），请换一个八度更合适的版本");
        }
        return new Arrangement(join(lines), arranged, skipped, durationMs, coverage, null);
    }

    /**
     * 为 MIDI 音高找最低品位的弦。
     *
     * @return {@code {mask, fret}}；超出 0..18 品返回 null
     */
    public static int[] lowestPlacement(int midi) {
        int bestMask = 0;
        int bestFret = Integer.MAX_VALUE;
        for (int column = 0; column < OPEN_MIDI.length; column++) {
            int fret = midi - OPEN_MIDI[column];
            if (fret < 0 || fret > MAX_FRET) {
                continue;
            }
            if (fret < bestFret) {
                bestFret = fret;
                bestMask = 1 << column;
            }
        }
        if (bestMask == 0) {
            return null;
        }
        return new int[]{bestMask, bestFret};
    }

    // ------------------------------------------------------------------ 内部

    private static void appendEvent(List<String> lines, long deltaMs, int mask, int row,
                                    int level, int flags) {
        long remaining = Math.max(0, deltaMs);
        while (remaining > MAX_DELTA_MS) {
            lines.add(MAX_DELTA_MS + ",0,0,0,0");
            remaining -= MAX_DELTA_MS;
        }
        lines.add(remaining + "," + mask + "," + row + "," + level + "," + flags);
    }

    private static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder(lines.size() * 14);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static Arrangement failure(String error) {
        return new Arrangement("", 0, 0, 0, 0, error);
    }
}
