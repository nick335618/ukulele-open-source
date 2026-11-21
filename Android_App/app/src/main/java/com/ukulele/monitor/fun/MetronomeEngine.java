package com.ukulele.monitor.fun;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 手机端节拍器引擎：用一条连续的 44.1kHz 单声道 16bit PCM 流充当“音频时钟”。
 *
 * <p>工作线程按<strong>样本计数</strong>安排节拍：预先合成的点击声在精确的样本位置混入
 * 输出流，因此节拍落点只取决于音频采样时钟，不受主线程卡顿、UI 绘制或 BLE 回调影响。
 * 首拍（beatIndex == 0）是 1200Hz 重音，其余拍是 800Hz 弱拍；两者都是 25~30ms 的
 * 指数衰减正弦短音，听感接近机械节拍器的“嗒”。点击声在构造时生成，运行期不再分配。
 *
 * <p>线程模型：{@link #start} 在调用线程创建 {@link AudioTrack}（失败立即返回 false），
 * 再启动独立写线程；{@link #setBpm}、{@link #setBeatsPerBar}、{@link #stop}、
 * {@link #release} 可在任意线程调用。Listener 回调经主线程 Handler 派发，且回调前后都
 * 校验会话是否仍是当前会话，因此 Activity 销毁后再收到挂起回调不会崩溃。
 *
 * <p>本类不依赖 App 的 Feedback 或 SettingsStore，也不申请音频焦点：节拍器是持续性的
 * 练习工具，由使用方决定何时停止/调音量即可。
 */
public final class MetronomeEngine {

    /** 节拍回调；运行在主线程，回调里可以安全操作 UI。 */
    public interface Listener {
        /**
         * @param beatIndex   本拍在小节内的序号，0 为首拍（重音）
         * @param beatsPerBar 当前每小节拍数
         */
        void onBeat(int beatIndex, int beatsPerBar);
    }

    public static final int MIN_BPM = 30;
    public static final int MAX_BPM = 240;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /** 每次写入 512 帧（约 11.6ms）：足够小以便及时响应停止/变速，又不会过度频繁地唤醒线程。 */
    private static final int BLOCK_FRAMES = 512;

    /** 点击音时长，均落在需求的 25~35ms 区间内。 */
    private static final int ACCENT_MS = 30;
    private static final int WEAK_MS = 26;

    private static final int DEFAULT_BPM = 96;
    private static final int DEFAULT_BEATS = 4;
    private static final int MIN_BEATS = 1;
    private static final int MAX_BEATS = 12;

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 预先合成的重音/弱拍点击声，避免写线程在节拍点附近做合成计算。 */
    private final short[] accentClick = click(1200.0, 0.55, ACCENT_MS);
    private final short[] weakClick = click(800.0, 0.30, WEAK_MS);

    /** 当前播放会话；stop() 置 null 后写线程在下一个检查点退出。 */
    private volatile Session current;
    private volatile int bpm = DEFAULT_BPM;
    private volatile int beatsPerBar = DEFAULT_BEATS;
    private volatile boolean released;

    private Thread worker; // 受 lock 保护

    /** 一次 start() 产生的播放会话；旧会话被替换后只负责退出并释放自己的音轨。 */
    private static final class Session {
        final AudioTrack track;
        final Listener listener;

        Session(AudioTrack track, Listener listener) {
            this.track = track;
            this.listener = listener;
        }
    }

    public MetronomeEngine() {
    }

    /** 把 BPM 夹紧到 30~240；UI 拖动条或保存值越界时直接使用，避免非法间隔。 */
    public static int clampBpm(int value) {
        if (value < MIN_BPM) {
            return MIN_BPM;
        }
        return value > MAX_BPM ? MAX_BPM : value;
    }

    /**
     * 开始节拍。已在运行时等价于“重启”（旧音轨先停）。
     *
     * @return 音轨创建成功并已开始输出返回 true；音频不可用或已 release 返回 false（不抛异常）
     */
    public boolean start(int bpm, int beatsPerBar, Listener listener) {
        final int safeBpm = clampBpm(bpm);
        final int safeBeats = clampBeats(beatsPerBar);
        synchronized (lock) {
            if (released) {
                return false;
            }
            stopLocked(); // 重复 start 时先彻底停止旧会话，避免两条音轨叠加
            AudioTrack created = null;
            try {
                int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
                if (minimum <= 0) {
                    return false; // 设备不支持该 PCM 格式
                }
                int bufferBytes = Math.max(minimum * 2, BLOCK_FRAMES * 2 * 4);
                created = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_MASK)
                                .setEncoding(ENCODING)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
                if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                    releaseQuietly(created);
                    return false;
                }
                created.setVolume(1f);
            } catch (Throwable error) {
                // 某些设备上构造 AudioTrack 会抛 RuntimeException；静默降级由 UI 提示
                releaseQuietly(created);
                return false;
            }

            this.bpm = safeBpm;
            this.beatsPerBar = safeBeats;
            final Session session = new Session(created, listener);
            final Thread thread = new Thread(() -> runLoop(session), "ukulele-metronome");
            thread.setDaemon(true);
            try {
                worker = thread;
                current = session; // 先置状态再启动，避免写线程一启动就看到 null 而空转退出
                thread.start();
            } catch (Throwable error) {
                current = null;
                worker = null;
                releaseQuietly(created);
                return false;
            }
            return true;
        }
    }

    /**
     * 运行中改速度：只写入目标值，写线程在下一个输出块开头以“上一拍”为锚点重算下一拍，
     * 因此拖动 BPM 不会丢拍，也不会因主线程等待而卡顿。
     */
    public void setBpm(int value) {
        bpm = clampBpm(value);
    }

    /** 运行中改拍号；新的拍数从下一拍开始生效，首拍重音仍落在 beatIndex == 0。 */
    public void setBeatsPerBar(int value) {
        beatsPerBar = clampBeats(value);
    }

    /** 停止节拍。幂等，可在任意线程调用；音轨由写线程退出时释放。 */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /** 是否正在运行。stop() 返回后立即为 false。 */
    public boolean isRunning() {
        return current != null && !released;
    }

    /**
     * 释放引擎：停止节拍并清空主线程队列里尚未派发的节拍回调，之后不可再 start。
     * 幂等；典型调用位置是 Activity.onDestroy()。
     */
    public void release() {
        synchronized (lock) {
            if (released) {
                stopLocked();
                return;
            }
            released = true;
            stopLocked();
        }
        // 丢弃已经排进主线程队列的节拍回调，Activity 销毁后不再触发 UI
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------ 写线程

    /** 工作线程主循环：连续生成 PCM，样本号即时间轴。 */
    private void runLoop(Session session) {
        final AudioTrack track = session.track;
        final short[] block = new short[BLOCK_FRAMES];
        long frame = 0;              // 已生成的样本总数
        long lastBeatFrame = 0;
        long nextBeatFrame = 0;      // 第 0 帧立即响第一拍（首拍重音）
        int beatIndex = 0;
        int scheduledBpm = bpm;
        short[] activeClick = null;  // 正在播放的点击声（可能横跨两个输出块）
        int clickPos = 0;
        try {
            track.play();
            while (current == session) {
                final int targetBpm = bpm;
                final int targetBeats = beatsPerBar;
                if (targetBpm != scheduledBpm) {
                    // 变速后以“上一拍”为锚点按新间隔重算下一拍。
                    // 若新间隔已经越过当前写入位置（大幅减速后拖得过久），就立即补一拍。
                    scheduledBpm = targetBpm;
                    nextBeatFrame = lastBeatFrame + framesPerBeat(targetBpm);
                    if (nextBeatFrame < frame) {
                        nextBeatFrame = frame;
                    }
                }
                if (beatIndex >= targetBeats) {
                    beatIndex = 0; // 拍号从小节中间调小后，旧索引越界保护
                }

                for (int i = 0; i < block.length; i++) {
                    if (frame >= nextBeatFrame) {
                        activeClick = beatIndex == 0 ? accentClick : weakClick;
                        clickPos = 0;
                        postBeat(session, beatIndex, targetBeats);
                        lastBeatFrame = frame;
                        beatIndex = (beatIndex + 1) % targetBeats;
                        nextBeatFrame = lastBeatFrame + framesPerBeat(targetBpm);
                    }
                    short value = 0;
                    if (activeClick != null) {
                        value = activeClick[clickPos];
                        if (++clickPos >= activeClick.length) {
                            activeClick = null;
                        }
                    }
                    block[i] = value;
                    frame++;
                }

                int offset = 0;
                while (offset < block.length && current == session) {
                    int written = track.write(block, offset, block.length - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) {
                        if (current != session) {
                            break; // stop() 打断阻塞写属于正常退出路径
                        }
                        throw new IllegalStateException("AudioTrack write failed: " + written);
                    }
                    offset += written;
                }
            }
        } catch (Throwable ignored) {
            // 音频输出不可用（拔耳机、设备被系统回收等）：静默停止，只在 isRunning 上体现
        } finally {
            synchronized (lock) {
                if (current == session) {
                    current = null; // 异常退出也要让 isRunning() 恢复 false
                }
                if (worker == Thread.currentThread()) {
                    worker = null;
                }
            }
            releaseQuietly(track);
        }
    }

    /** 通过主线程 Handler 派发节拍；运行前/派发前都检查会话，避免 Activity 销毁后回调。 */
    private void postBeat(final Session session, final int beatIndex, final int barLength) {
        final Listener target = session.listener;
        if (target == null) {
            return;
        }
        main.post(() -> {
            if (current != session) {
                return; // 已停止/被新会话取代：丢弃挂起回调
            }
            try {
                target.onBeat(beatIndex, barLength);
            } catch (Throwable ignored) {
                // 使用方回调异常不能影响节拍线程
            }
        });
    }

    // ------------------------------------------------------------ 内部工具

    private void stopLocked() {
        final Session session = current;
        current = null;
        if (session == null) {
            return;
        }
        final AudioTrack track = session.track;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Throwable ignored) {
                // 打断阻塞中的 write，让写线程尽快走到退出分支
            }
        }
        final Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 一拍对应的整数样本数；四舍五入误差小于 0.5 样本，长时间运行也不会累积偏移。 */
    private static long framesPerBeat(int bpm) {
        return Math.max(1L, Math.round(SAMPLE_RATE * 60.0 / bpm));
    }

    private static int clampBeats(int value) {
        if (value < MIN_BEATS) {
            return MIN_BEATS;
        }
        return value > MAX_BEATS ? MAX_BEATS : value;
    }

    /**
     * 合成一个指数衰减的正弦“嗒”声。
     *
     * @param frequency 点击音基频
     * @param peak      峰值音量（0~1），重音比弱拍响
     * @param durationMs 时长
     */
    private static short[] click(double frequency, double peak, int durationMs) {
        int frames = SAMPLE_RATE * durationMs / 1000;
        short[] samples = new short[frames];
        double decay = 1.0 / (0.008 * SAMPLE_RATE); // 8ms 时间常数，听起来干脆
        double attack = 0.0015 * SAMPLE_RATE;       // 1.5ms 起音，消除起始边沿的爆音
        for (int i = 0; i < frames; i++) {
            double envelope = Math.exp(-i * decay) * Math.min(1.0, i / attack);
            double wave = Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE);
            samples[i] = (short) Math.round(wave * envelope * peak * Short.MAX_VALUE);
        }
        return samples;
    }

    private static void releaseQuietly(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.pause();
        } catch (Throwable ignored) {
            // 未初始化/已释放：无需处理
        }
        try {
            track.flush();
        } catch (Throwable ignored) {
            // 非 STREAM 模式会拒绝 flush
        }
        try {
            track.stop();
        } catch (Throwable ignored) {
            // 已停止
        }
        try {
            track.release();
        } catch (Throwable ignored) {
            // 已释放
        }
    }
}
