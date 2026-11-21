package com.ukulele.monitor.fun;

import java.util.Random;

/**
 * 尤克里里与基础乐理小知识题库（20 道单选题 + 练习冷知识）。
 *
 * <p>纯 Java 数据类，不引用任何 Android API：既方便 JVM 单元测试，也让“音乐问答”页面
 * 和数据完全解耦。所有题目按标准高音 G 调弦（G4-C4-E4-A4）与十二平均律编写，答案与解析
 * 均经过核对，避免给出错误乐理。
 *
 * <p>{@link #get(int)} 会按长度回绕下标、{@link #random(Random)} 允许传入 null，
 * 因此 UI 直接调用即可，不需要自己做越界判断。
 */
public final class MusicTrivia {

    /** 一道单选题。字段全部 public final，作为只读值对象使用。 */
    public static final class Question {
        /** 题干（中文）。 */
        public final String question;
        /** 4 个选项；answer 是正确选项的下标（0~3）。 */
        public final String[] options;
        /** 正确答案下标。 */
        public final int answer;
        /** 1~2 句中文解析。 */
        public final String explain;

        public Question(String question, String[] options, int answer, String explain) {
            this.question = question == null ? "" : question;
            this.options = options == null ? new String[0] : options.clone();
            this.answer = answer;
            this.explain = explain == null ? "" : explain;
        }
    }

    /** 20 道题；answer 分布均匀（0~3 各 5 题），避免玩家摸出规律。 */
    private static final Question[] QUESTIONS = {
            new Question(
                    "标准高音 G 调弦（GCEA）中，四根空弦音从四弦到一弦依次是？",
                    new String[]{"G4、C4、E4、A4", "E4、A4、D4、G4", "C4、G4、A4、E4", "A4、E4、C4、G4"},
                    0,
                    "这是尤克里里最常用的高音 G 重入定弦；四弦 G4 比三弦 C4 高一个纯五度，所以定弦并非从低到高排列。"),
            new Question(
                    "标准定弦中，三弦（C 弦）的空弦音是？",
                    new String[]{"C3", "C4", "C5", "G4"},
                    1,
                    "三弦空弦是中音 C（C4，约 261.6 Hz），常被当作调音时的基准弦。"),
            new Question(
                    "C 大三和弦在标准 GCEA 上的按法是？",
                    new String[]{"四根弦全部空弦", "G 弦 2 品、其余空弦",
                            "G、C、E 弦空弦，A 弦 3 品", "G 弦 2 品、C 弦 2 品、E 弦 1 品、A 弦空弦"},
                    2,
                    "只按一弦（A 弦）3 品即可弹出 C 大三和弦，是初学者最先掌握的和弦之一。"),
            new Question(
                    "Am 和弦在标准 GCEA 上的按法是？",
                    new String[]{"G 弦 2 品、C 弦 2 品、E 弦 1 品", "四根弦都按 2 品",
                            "A 弦 3 品，其余空弦", "G 弦 2 品，C、E、A 弦空弦"},
                    3,
                    "Am 由 A、C、E 三个音组成，G 弦 2 品正好是 A 音，其余三弦空弦即可。"),
            new Question(
                    "大三和弦由哪三个音构成？",
                    new String[]{"根音、大三度、纯五度", "根音、小三度、纯五度",
                            "根音、大三度、小七度", "根音、纯四度、大六度"},
                    0,
                    "大三和弦是根音上方叠加大三度和小三度，例如 C 和弦由 C、E、G 组成。"),
            new Question(
                    "小三和弦与大三和弦的主要区别是？",
                    new String[]{"五音升高一个半音", "三音降低一个半音",
                            "多加一个七音", "根音降低一个八度"},
                    1,
                    "小三和弦把大三度换成小三度，也就是三音降半音，听感通常更柔和、忧郁。"),
            new Question(
                    "一个八度包含多少个半音？",
                    new String[]{"7 个", "10 个", "12 个", "15 个"},
                    2,
                    "十二平均律把一个八度均分为 12 个半音，吉他类指板上相邻品格正好相差半音。"),
            new Question(
                    "国际通用的标准音 A4 频率是多少？",
                    new String[]{"220 Hz", "523 Hz", "880 Hz", "440 Hz"},
                    3,
                    "A4 = 440 Hz 是国际标准音，A5 是它的两倍 880 Hz。"),
            new Question(
                    "节拍器显示 60 BPM，表示每分钟多少拍？",
                    new String[]{"60 拍", "30 拍", "120 拍", "240 拍"},
                    0,
                    "BPM 是每分钟拍数（Beats Per Minute），60 BPM 相当于每拍正好一秒。"),
            new Question(
                    "4/4 拍子每小节有几拍？",
                    new String[]{"2 拍", "4 拍", "3 拍", "6 拍"},
                    1,
                    "4/4 表示以四分音符为一拍、每小节四拍，是最常见的流行音乐拍号。"),
            new Question(
                    "在 4/4 拍中，通常最强的是第几拍？",
                    new String[]{"第 4 拍", "第 3 拍", "第 1 拍", "第 2 拍"},
                    2,
                    "4/4 拍中第 1 拍最强、第 3 拍次强，重音帮助耳朵听出小节循环。"),
            new Question(
                    "调音时发现某根弦音高偏高，应该怎么做？",
                    new String[]{"继续拧紧琴弦", "换一根新弦", "用力拨弦", "放松琴弦"},
                    3,
                    "琴弦越紧音越高，音偏高时应缓慢放松弦钮，再微调到标准音。"),
            new Question(
                    "尤克里里指板上相邻两个品格之间相差多少音程？",
                    new String[]{"一个半音", "一个全音", "一个纯五度", "一个八度"},
                    0,
                    "每升高一格品格就升高一个半音，12 格正好是一个八度。"),
            new Question(
                    "高音 G 弦（四弦）与三弦 C 的音高关系是？",
                    new String[]{"比三弦 C 低一个八度", "比三弦 C 高一个纯五度",
                            "与三弦 C 完全相同", "比三弦 C 高一个八度"},
                    1,
                    "高音 G 定弦的 G4 比 C4 高一个纯五度，四弦不是最低音弦，这种定弦方式称为重入定弦。"),
            new Question(
                    "现代尤克里里通常使用哪种琴弦？",
                    new String[]{"钢丝弦", "金属合金弦", "尼龙或碳素弦", "铜管弦"},
                    2,
                    "现代尤克里里多用尼龙或碳素弦，张力小、手感软，音色清亮。"),
            new Question(
                    "F 和弦在标准 GCEA 上，E 弦需要按第几品？",
                    new String[]{"空弦", "第 3 品", "第 2 品", "第 1 品"},
                    3,
                    "F 和弦的按法是 G 弦 2 品、E 弦 1 品，C 弦和 A 弦空弦。"),
            new Question(
                    "G7 和弦由哪四个音组成？",
                    new String[]{"G、B、D、F", "G、B、D、E", "G、C、E、G", "G、A、D、F"},
                    0,
                    "G7 以 G 大三和弦（G、B、D）为基础，再叠加上一个小七度音 F。"),
            new Question(
                    "23 英寸的尤克里里通常被称为什么尺寸？",
                    new String[]{"女高音（Soprano）", "音乐会（Concert）", "次中音（Tenor）", "中音（Baritone）"},
                    1,
                    "常见尺寸里 21 英寸是女高音、23 英寸是音乐会、26 英寸是次中音。"),
            new Question(
                    "尤克里里最早源自哪个国家？",
                    new String[]{"美国", "西班牙", "葡萄牙", "巴西"},
                    2,
                    "尤克里里由葡萄牙移民带到夏威夷，前身是马德拉群岛的小型拨弦琴。"),
            new Question(
                    "右手拨弦位置越靠近琴桥，音色通常？",
                    new String[]{"更温暖柔和", "完全没有变化", "音高升高一个八度", "更明亮清脆"},
                    3,
                    "靠近琴桥拨弦时高频更突出、音色更亮；靠近琴颈拨弦则更温暖柔和。")
    };

    /** 练习小贴士/冷知识，每条 15~45 字，UI 可随机展示一条。 */
    private static final String[] TIPS = {
            "每天练十分钟按和弦，比周末突击一小时更容易形成肌肉记忆。",
            "按弦时手指要立起来，用指尖靠近指甲的位置，声音会更干净。",
            "换和弦前先松开手指再移动，杂音更少，初学阶段慢练比快练有效。",
            "扫弦先用节拍器六十的速度练稳，再逐步提速，节奏比速度更重要。",
            "高音 G 弦比三弦 C 高一个纯五度，所以尤克里里的定弦并非从低到高排列。",
            "调音时先把三弦 C 校准，再依次调 E、G、A，能减少反复微调的次数。",
            "琴弦用久会失去弹性、音准变差，一般三到六个月可以考虑换弦。",
            "弹唱时先把歌词按节奏念顺，再配上和弦，会比直接跟原曲更容易合拍。"
    };

    private MusicTrivia() {
        // 纯静态工具类，不实例化
    }

    /** 题目总数。 */
    public static int count() {
        return QUESTIONS.length;
    }

    /** 按下标取题；越界时自动回绕（负下标也安全），UI 无需额外边界处理。 */
    public static Question get(int index) {
        int length = QUESTIONS.length;
        int safe = ((index % length) + length) % length;
        return QUESTIONS[safe];
    }

    /** 随机取一题；{@code random} 为 null 时内部新建，保证不抛异常。 */
    public static Question random(Random random) {
        Random source = random == null ? new Random() : random;
        return QUESTIONS[source.nextInt(QUESTIONS.length)];
    }

    /** 返回全部练习小贴士（副本，调用方修改不影响题库）。 */
    public static String[] tips() {
        return TIPS.clone();
    }

    /** 随机取一条小贴士；{@code random} 为 null 时内部新建。 */
    public static String randomTip(Random random) {
        Random source = random == null ? new Random() : random;
        return TIPS[source.nextInt(TIPS.length)];
    }
}
