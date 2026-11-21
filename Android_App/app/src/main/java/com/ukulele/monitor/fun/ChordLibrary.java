package com.ukulele.monitor.fun;

import java.util.Random;

/**
 * 标准高音 G 调弦（GCEA）的常用和弦指法库。
 *
 * <p>纯 Java 数据类：不引用任何 Android API，方便在 JVM 上做单元测试，也避免 UI/音频层
 * 各自维护一份指法表导致不一致。
 *
 * <p>指法约定：{@code frets} 固定按 {G,C,E,A} 顺序排列，与本 App 的物理弦列一致；
 * {@code -1} 表示闷弦（不弹），{@code 0} 表示空弦，{@code n} 表示第 n 品。这里的和弦全部
 * 在 0~4 品内按完，{@code baseFret} 统一为 0，UI 画指法图时可直接按绝对品位绘制。
 *
 * <p>midi() 以标准空弦音高为基准：G4=67、C4=60、E4=64、A4=69（高音 G 定弦，四弦 G4
 * 比三弦 C4 高一个纯五度，属于重入定弦）。
 */
public final class ChordLibrary {

    /** 四根弦的空弦 MIDI 音高，顺序与 frets 一致：G4 / C4 / E4 / A4。 */
    private static final int[] OPEN_MIDI = {67, 60, 64, 69};

    /** 弦名，顺序与 frets 一致，供 UI 标注。 */
    public static final String[] STRING_NAMES = {"G", "C", "E", "A"};

    /**
     * 一个和弦的完整描述。字段全部 public final，作为只读值对象使用。
     */
    public static final class Chord {
        /** 和弦名（ASCII，如 "C"、"Am"、"G7"），用于比较与保存。 */
        public final String name;
        /** 简短中文显示名（如「C 大三和弦」），直接用于 UI 文案。 */
        public final String displayName;
        /** 四根弦的品位，顺序固定 {G,C,E,A}；-1=闷弦，0=空弦，n=品位。 */
        public final int[] frets;
        /** 起始品位；本库全部为 0（无高把位横按）。 */
        public final int baseFret;

        public Chord(String name, String displayName, int[] frets, int baseFret) {
            this.name = name == null ? "" : name;
            this.displayName = displayName == null ? this.name : displayName;
            // 复制一份，避免调用方传入的数组之后被改动而污染静态指法表
            this.frets = frets == null ? new int[]{0, 0, 0, 0} : frets.clone();
            this.baseFret = Math.max(0, baseFret);
        }
    }

    /** 18 个常用和弦，按入门难度大致排序；指法采用标准 GCEA 高音 G 定弦。 */
    private static final Chord[] CHORDS = {
            new Chord("C",   "C 大三和弦",  new int[]{0, 0, 0, 3}, 0),
            new Chord("Am",  "Am 小三和弦", new int[]{2, 0, 0, 0}, 0),
            new Chord("F",   "F 大三和弦",  new int[]{2, 0, 1, 0}, 0),
            new Chord("G",   "G 大三和弦",  new int[]{0, 2, 3, 2}, 0),
            new Chord("G7",  "G7 属七和弦", new int[]{0, 2, 1, 2}, 0),
            new Chord("Em",  "Em 小三和弦", new int[]{0, 4, 3, 2}, 0),
            new Chord("Dm",  "Dm 小三和弦", new int[]{2, 2, 1, 0}, 0),
            new Chord("D",   "D 大三和弦",  new int[]{2, 2, 2, 0}, 0),
            new Chord("A",   "A 大三和弦",  new int[]{2, 1, 0, 0}, 0),
            new Chord("E",   "E 大三和弦",  new int[]{1, 4, 0, 2}, 0),
            new Chord("Bm",  "Bm 小三和弦", new int[]{4, 2, 2, 2}, 0),
            new Chord("B",   "B 大三和弦",  new int[]{4, 3, 2, 2}, 0),
            new Chord("C7",  "C7 属七和弦", new int[]{0, 0, 0, 1}, 0),
            new Chord("A7",  "A7 属七和弦", new int[]{0, 1, 0, 0}, 0),
            new Chord("D7",  "D7 属七和弦", new int[]{2, 2, 2, 3}, 0),
            new Chord("F7",  "F7 属七和弦", new int[]{2, 3, 1, 3}, 0),
            new Chord("Gm",  "Gm 小三和弦", new int[]{0, 2, 3, 1}, 0),
            new Chord("Cm",  "Cm 小三和弦", new int[]{0, 3, 3, 3}, 0)
    };

    private ChordLibrary() {
        // 纯静态工具类，不实例化
    }

    /** 返回全部和弦（副本，调用方修改数组本身不会影响库内数据）。 */
    public static Chord[] all() {
        return CHORDS.clone();
    }

    /** 和弦总数。 */
    public static int count() {
        return CHORDS.length;
    }

    /**
     * 把指法换算成 MIDI 音高数组，顺序仍为 {G,C,E,A}；闷弦保持 -1，便于播放器跳过。
     * {@code chord} 为 null 时返回 null。
     */
    public static int[] midi(Chord chord) {
        if (chord == null) {
            return null;
        }
        int[] notes = new int[OPEN_MIDI.length];
        for (int i = 0; i < OPEN_MIDI.length; i++) {
            int fret = chord.frets.length > i ? chord.frets[i] : -1;
            notes[i] = fret < 0 ? -1 : OPEN_MIDI[i] + fret;
        }
        return notes;
    }

    /** 随机取一个和弦；{@code random} 为 null 时内部新建，保证不抛异常。 */
    public static Chord random(Random random) {
        Random source = random == null ? new Random() : random;
        return CHORDS[source.nextInt(CHORDS.length)];
    }
}
