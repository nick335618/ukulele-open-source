package com.ukulele.monitor.fun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * 趣味数据持久化：连续打卡、问答连胜、节拍器 BPM、已弹和弦、成就解锁。
 *
 * <p>存储介质固定为 {@code SharedPreferences("uk_fun")}（{@link #PREFS_NAME}）。
 * 所有读写都做了异常兜底：没有 Context、SharedPreferences 异常、坏数据一律返回安全默认值，
 * 绝不向调用方抛异常——本类服务于好玩的小功能，不能拖垮主流程。
 *
 * <p>设计约定：
 * <ul>
 *   <li>线程安全：所有公开方法内部加锁，主线程使用即可，但跨线程调用也不会互相破坏。</li>
 *   <li>打卡日键 = 本地 {@link Calendar} 的 {@code year * 1000 + dayOfYear}，只比较整日，
 *       不依赖毫秒时间戳，因此跨 DST / 时区切换不会误判；「昨天」用 Calendar 减一天求得，
 *       不用日键 -1（那样跨年会错）。</li>
 *   <li>和弦数 = 不同的和弦名集合（StringSet）。注意 SharedPreferences 返回的 Set 不允许
 *       直接修改，本类每次复制成新 {@link HashSet} 后再写回。</li>
 *   <li>未解锁/未打卡时读取返回 0 / false，不创建垃圾键。</li>
 * </ul>
 */
public final class FunStore {

    /** SharedPreferences 文件名（契约固定）。 */
    public static final String PREFS_NAME = "uk_fun";

    /** 节拍器 BPM 允许范围与默认值（与 {@code MetronomeEngine} 的 clampBpm 范围保持一致）。 */
    public static final int MIN_BPM = 30;
    public static final int MAX_BPM = 240;
    public static final int DEFAULT_BPM = 96;

    // ---------------------------------------------------------------- 存储键

    private static final String KEY_STREAK = "streak";
    private static final String KEY_MAX_STREAK = "max_streak";
    private static final String KEY_TOTAL_DAYS = "total_days";
    private static final String KEY_LAST_DAY = "last_day";
    private static final String KEY_QUIZ_STREAK = "quiz_streak";
    private static final String KEY_QUIZ_BEST = "quiz_best_streak";
    private static final String KEY_METRONOME_BPM = "metronome_bpm";
    private static final String KEY_CHORDS = "chords_played";
    private static final String KEY_UNLOCKED_PREFIX = "unlocked_";
    private static final String KEY_UNLOCK_TIME_PREFIX = "unlock_time_";

    /** 成就定义（10 个），id 是持久化键的一部分，不可随意改动。 */
    private static final Badge[] BADGES = {
            new Badge("first_connect", "初次连线", "首次成功连接尤克里里设备"),
            new Badge("first_upload", "曲谱同步", "首次把曲谱同步到设备"),
            new Badge("first_play", "登台演奏", "完成一次完整的曲目演奏"),
            new Badge("first_score", "评分完成", "完成一次演奏评分"),
            new Badge("first_led", "灯效点亮", "首次点亮 LED 灯阵"),
            new Badge("chord_explorer", "和弦探索", "弹奏 8 个不同的和弦"),
            new Badge("metronome_master", "节拍达人", "第一次启动节拍器跟练"),
            new Badge("quiz_ace", "问答高手", "连续答对 5 道乐理题"),
            new Badge("streak_3", "坚持三天", "连续打卡 3 天"),
            new Badge("egg_hunter", "彩蛋猎人", "发现一个隐藏彩蛋")
    };

    private final SharedPreferences prefs;
    private final Object lock = new Object();

    /**
     * @param context 任意 Context；为 null 或取不到 SharedPreferences 时退化为「只读默认值」
     *                的空存储（所有写入静默失败，不抛异常）
     */
    public FunStore(Context context) {
        SharedPreferences store = null;
        if (context != null) {
            try {
                Context app = context.getApplicationContext();
                Context target = app != null ? app : context;
                store = target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            } catch (Throwable ignored) {
                // 例如应用已销毁 / 权限异常：保持空存储，不影响调用方。
                store = null;
            }
        }
        this.prefs = store;
    }

    // ---------------------------------------------------------------- 打卡

    /** 当前连续打卡天数；已经断签（最后打卡既不是今天也不是昨天）时返回 0。 */
    public int streak() {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                int stored = nonNegative(prefs.getInt(KEY_STREAK, 0));
                if (stored == 0) {
                    return 0;
                }
                int[] days = dayKeys();
                int last = prefs.getInt(KEY_LAST_DAY, 0);
                return (last == days[0] || last == days[1]) ? stored : 0;
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 历史最长连续打卡天数（不会因断签清零）。 */
    public int maxStreak() {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                return Math.max(nonNegative(prefs.getInt(KEY_MAX_STREAK, 0)), streak());
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 累计打卡天数（每个自然日只计一次）。 */
    public int totalDays() {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                return nonNegative(prefs.getInt(KEY_TOTAL_DAYS, 0));
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 今天是否已经打卡。 */
    public boolean checkedInToday() {
        if (prefs == null) {
            return false;
        }
        synchronized (lock) {
            try {
                return prefs.getInt(KEY_LAST_DAY, 0) == dayKeys()[0];
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /**
     * 今天打卡。
     *
     * @return true 表示这是今天第一次打卡（连续天数已更新）；今天之前已打过或存储不可用时返回 false
     */
    public boolean checkInToday() {
        if (prefs == null) {
            return false;
        }
        synchronized (lock) {
            try {
                int[] days = dayKeys();
                int today = days[0];
                int yesterday = days[1];
                int last = prefs.getInt(KEY_LAST_DAY, 0);
                if (last == today) {
                    return false;
                }
                // 昨天打过卡才累加，否则从 1 重新开始（含跨年、时钟回拨等异常情况）。
                int current = nonNegative(prefs.getInt(KEY_STREAK, 0));
                int next = (last != 0 && last == yesterday) ? current + 1 : 1;
                int best = Math.max(nonNegative(prefs.getInt(KEY_MAX_STREAK, 0)), next);
                int total = nonNegative(prefs.getInt(KEY_TOTAL_DAYS, 0)) + 1;
                prefs.edit()
                        .putInt(KEY_LAST_DAY, today)
                        .putInt(KEY_STREAK, next)
                        .putInt(KEY_MAX_STREAK, best)
                        .putInt(KEY_TOTAL_DAYS, total)
                        .apply();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- 音乐问答

    /** 当前连续答对题数。 */
    public int quizStreak() {
        return readNonNegative(KEY_QUIZ_STREAK);
    }

    /** 历史最长连续答对题数。 */
    public int quizBestStreak() {
        return readNonNegative(KEY_QUIZ_BEST);
    }

    /** 记录一次答题结果：答对连胜 +1，答错清零；同时刷新历史最佳。 */
    public void recordQuiz(boolean correct) {
        if (prefs == null) {
            return;
        }
        synchronized (lock) {
            try {
                int streak = correct ? nonNegative(prefs.getInt(KEY_QUIZ_STREAK, 0)) + 1 : 0;
                int best = Math.max(nonNegative(prefs.getInt(KEY_QUIZ_BEST, 0)), streak);
                prefs.edit()
                        .putInt(KEY_QUIZ_STREAK, streak)
                        .putInt(KEY_QUIZ_BEST, best)
                        .apply();
            } catch (Throwable ignored) {
                // 静默失败：趣味数据不值得打断用户。
            }
        }
    }

    // ---------------------------------------------------------------- 节拍器

    /** 上次使用的节拍器 BPM（默认 {@link #DEFAULT_BPM}，读数已夹紧到 [30, 240]）。 */
    public int metronomeBpm() {
        if (prefs == null) {
            return DEFAULT_BPM;
        }
        synchronized (lock) {
            try {
                return clampBpm(prefs.getInt(KEY_METRONOME_BPM, DEFAULT_BPM));
            } catch (Throwable ignored) {
                return DEFAULT_BPM;
            }
        }
    }

    /** 记住节拍器 BPM。非法值（含越界）会夹紧到 [30, 240]。 */
    public void setMetronomeBpm(int bpm) {
        if (prefs == null) {
            return;
        }
        synchronized (lock) {
            try {
                prefs.edit().putInt(KEY_METRONOME_BPM, clampBpm(bpm)).apply();
            } catch (Throwable ignored) {
                // 静默失败。
            }
        }
    }

    // ---------------------------------------------------------------- 和弦探索

    /** 已经弹过的不同和弦数量（去重）。 */
    public int chordsPlayed() {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                return chordSet().size();
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 记录弹过的一个和弦名（null/空白忽略，重复调用只记一次）。 */
    public void recordChordPlayed(String chordName) {
        if (prefs == null || chordName == null) {
            return;
        }
        String name = chordName.trim();
        if (name.length() == 0) {
            return;
        }
        synchronized (lock) {
            try {
                Set<String> chords = chordSet();
                if (!chords.add(name)) {
                    return;
                }
                // 绝不能直接改 getStringSet 返回的集合：必须写入复制后的新集合。
                prefs.edit().putStringSet(KEY_CHORDS, chords).apply();
            } catch (Throwable ignored) {
                // 静默失败。
            }
        }
    }

    // ---------------------------------------------------------------- 成就解锁

    /**
     * 解锁成就 id（首次调用返回 true 并记录解锁时间戳）。
     *
     * @return false 表示已经解锁过或 id 非法
     */
    public boolean unlock(String id) {
        if (prefs == null || id == null || id.length() == 0) {
            return false;
        }
        synchronized (lock) {
            try {
                String key = KEY_UNLOCKED_PREFIX + id;
                if (prefs.getBoolean(key, false)) {
                    return false;
                }
                prefs.edit()
                        .putBoolean(key, true)
                        .putLong(KEY_UNLOCK_TIME_PREFIX + id, System.currentTimeMillis())
                        .apply();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** 成就是否已解锁。 */
    public boolean isUnlocked(String id) {
        if (prefs == null || id == null || id.length() == 0) {
            return false;
        }
        synchronized (lock) {
            try {
                return prefs.getBoolean(KEY_UNLOCKED_PREFIX + id, false);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** 已解锁成就数量（统计所有 unlocked_* 键，通常为 0..10）。 */
    public int unlockedCount() {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                int count = 0;
                for (String key : prefs.getAll().keySet()) {
                    if (key != null && key.startsWith(KEY_UNLOCKED_PREFIX)
                            && prefs.getBoolean(key, false)) {
                        count++;
                    }
                }
                return count;
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 成就定义。 */
    public static final class Badge {
        /** 稳定 id（持久化键的一部分，不要改）。 */
        public final String id;
        /** 中文名，例如「初次连线」。 */
        public final String title;
        /** 中文描述，例如「首次成功连接尤克里里设备」。 */
        public final String desc;

        private Badge(String id, String title, String desc) {
            this.id = id;
            this.title = title;
            this.desc = desc;
        }
    }

    /** 返回 10 个成就定义（数组副本，调用方可以随便改数组本身）。 */
    public static Badge[] badges() {
        return BADGES.clone();
    }

    /** 由成就 id 取中文名；未知 id 原样返回，null 返回空串。 */
    public static String badgeTitle(String id) {
        if (id == null) {
            return "";
        }
        for (int i = 0; i < BADGES.length; i++) {
            if (BADGES[i].id.equals(id)) {
                return BADGES[i].title;
            }
        }
        return id;
    }

    // ---------------------------------------------------------------- 内部工具

    /** 复制 getStringSet 的返回值，避免修改 SharedPreferences 内部共享集合。 */
    private Set<String> chordSet() {
        Set<String> stored = prefs.getStringSet(KEY_CHORDS, null);
        if (stored == null) {
            return new HashSet<String>();
        }
        return new HashSet<String>(stored);
    }

    private int readNonNegative(String key) {
        if (prefs == null) {
            return 0;
        }
        synchronized (lock) {
            try {
                return nonNegative(prefs.getInt(key, 0));
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    /** 本地日历的「今天 / 昨天」日键：year*1000 + dayOfYear。 */
    private static int[] dayKeys() {
        int[] keys = new int[2];
        Calendar calendar = Calendar.getInstance();
        keys[0] = dayKey(calendar);
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        keys[1] = dayKey(calendar);
        return keys;
    }

    private static int dayKey(Calendar calendar) {
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static int nonNegative(int value) {
        return value < 0 ? 0 : value;
    }

    private static int clampBpm(int bpm) {
        if (bpm < MIN_BPM) {
            return MIN_BPM;
        }
        if (bpm > MAX_BPM) {
            return MAX_BPM;
        }
        return bpm;
    }
}
