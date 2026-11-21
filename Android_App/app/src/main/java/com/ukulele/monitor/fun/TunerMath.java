package com.ukulele.monitor.fun;

/**
 * 调音计算：目标频率、音分偏差、最近弦判定与 FFT 峰值频率插值。
 *
 * <p><b>纯 Java，禁止 import android.*</b>：本类必须能在普通 JVM 上做单元测试。
 *
 * <p>物理列顺序固定为 G / C / E / A（与 App 的四列琴弦、{@code AudioAnalysisFrame} 的
 * bins 语义一致），标准高音 G 调弦的空弦频率 G4=392.00 Hz、C4=261.626 Hz、
 * E4=329.628 Hz、A4=440.00 Hz。
 *
 * <p>所有方法对非法输入（null、NaN、无穷大、非正数）都返回安全值而不抛异常，
 * 因为调用方是实时音频界面，不能让一次坏数据把 UI 打崩。
 */
public final class TunerMath {

    /** 空弦目标频率（Hz），物理列序 G / C / E / A。 */
    public static final double[] TARGET_HZ = {392.00, 261.626, 329.628, 440.00};

    /** 弦名，与 {@link #TARGET_HZ} 一一对应。 */
    public static final String[] STRING_NAMES = {"G", "C", "E", "A"};

    /** 指针满偏对应的音分绝对值：-50..+50 映射到 -1..+1。 */
    public static final double NEEDLE_RANGE_CENTS = 50.0;

    /** |音分偏差| 不超过该值即判定为「准」。 */
    public static final double IN_TUNE_CENTS = 10.0;

    private static final double CENTS_PER_OCTAVE = 1200.0;
    private static final double LN_2 = Math.log(2.0);
    /** bins 是 0..192 的 dBFS 编码：value 0 表示 -96 dBFS，192 表示 0 dBFS。 */
    private static final double BIN_DB_FLOOR = -96.0;
    private static final double BIN_DB_SCALE = 0.5;

    private TunerMath() {
        // 工具类，不实例化。
    }

    /**
     * 返回最接近 {@code hz} 的目标弦下标（0=G，1=C，2=E，3=A），按音分距离比较。
     *
     * @return -1 表示 {@code hz} 非法（null 语义、NaN、无穷或非正数）
     */
    public static int nearestString(double hz) {
        if (!isFinitePositive(hz)) {
            return -1;
        }
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < TARGET_HZ.length; i++) {
            double distance = Math.abs(Math.log(hz / TARGET_HZ[i]));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * 相对目标频率的音分偏差：{@code 1200 * log2(hz / target)}，正数表示偏高。
     *
     * <p>{@code hz <= 0}、{@code target <= 0}、NaN 或 +Inf 时返回 0。
     */
    public static double cents(double hz, double target) {
        if (!isFinitePositive(hz) || !isFinitePositive(target)) {
            return 0.0;
        }
        return CENTS_PER_OCTAVE * Math.log(hz / target) / LN_2;
    }

    /**
     * 把音分偏差线性映射为指针比例：-50..+50 → -1..+1，并夹紧到 [-1, 1]。
     * NaN 返回 0（指针回中）。
     */
    public static double needleFraction(double cents) {
        if (Double.isNaN(cents)) {
            return 0.0;
        }
        double fraction = cents / NEEDLE_RANGE_CENTS;
        if (fraction < -1.0) {
            return -1.0;
        }
        if (fraction > 1.0) {
            return 1.0;
        }
        return fraction;
    }

    /** 中文状态文案：|音分|<=10 为「准」，负为「偏低」，正为「偏高」（NaN 视为「准」）。 */
    public static String statusLabel(double cents) {
        if (Double.isNaN(cents)) {
            return "准";
        }
        if (Math.abs(cents) <= IN_TUNE_CENTS) {
            return "准";
        }
        return cents < 0.0 ? "偏低" : "偏高";
    }

    /**
     * 由 FPGA 的 4096 点 FFT 峰值 bin 估算真实频率。
     *
     * <p>对峰值 bin 与左右相邻 bin 的 dB 值先转成线性幅度再做抛物线插值（三个采样点中
     * 顶点相对中间点的偏移），偏移夹紧到 ±0.5 bin，因此结果一定落在
     * {@code (peakBin - 0.5) .. (peakBin + 0.5)} 对应的频率区间内。
     *
     * <p>越界（null、peakBin 贴边、fftSize<=0、sampleRate<=0）或分母接近 0
     * （平坦/静音）时回退为 {@code peakBin * sampleRate / fftSize}。
     *
     * @param bins       dB 编码的频谱（0..192，0 表示 -96 dBFS），通常为 193 个有效点
     * @param peakBin    峰值下标（0..192）
     * @param sampleRate 采样率（本项目为 48828.125 Hz）
     * @param fftSize    FFT 点数（本项目为 4096）
     */
    public static double interpolatedHz(int[] bins, int peakBin, double sampleRate, int fftSize) {
        double fallback = (isFinitePositive(sampleRate) && fftSize > 0 && peakBin > 0)
                ? peakBin * sampleRate / fftSize
                : 0.0;
        if (bins == null || !isFinitePositive(sampleRate) || fftSize <= 0
                || peakBin < 1 || peakBin >= bins.length - 1) {
            return fallback;
        }
        double left = linearAmplitude(bins[peakBin - 1]);
        double center = linearAmplitude(bins[peakBin]);
        double right = linearAmplitude(bins[peakBin + 1]);
        double denominator = left - 2.0 * center + right;
        if (Math.abs(denominator) < 1e-12) {
            return fallback;
        }
        double offset = 0.5 * (left - right) / denominator;
        if (offset < -0.5) {
            offset = -0.5;
        } else if (offset > 0.5) {
            offset = 0.5;
        }
        double hz = (peakBin + offset) * sampleRate / fftSize;
        if (!isFinitePositive(hz)) {
            return fallback;
        }
        return hz;
    }

    /** 把 dBFS 编码值（0..192）转换成线性幅度，超出范围的输入先夹紧，保证不溢出。 */
    private static double linearAmplitude(int bin) {
        int clamped = bin < 0 ? 0 : (bin > 192 ? 192 : bin);
        double dbfs = clamped * BIN_DB_SCALE + BIN_DB_FLOOR;
        return Math.pow(10.0, dbfs / 20.0);
    }

    private static boolean isFinitePositive(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
