package com.ukulele.monitor.fun;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机端和弦扫弦合成播放器：在本地合成拨弦音色，不依赖任何音频资源文件。
 *
 * <p>合成方式为 Karplus-Strong 拨弦模型（噪声激励 + 延迟线 + 平均滤波），每个样本只需
 * 常数次运算，因此 4 根弦、约 1.2 秒的单声道 PCM 可以在播放前快速生成。四根弦按
 * G→C→E→A 顺序错开约 30ms 起音，模拟一次下扫；叠加后统一做峰值归一化，防止多弦同时
 * 发声时削波。
 *
 * <p>线程模型：{@link #play} 只做参数校验与入队，真正的 PCM 合成、AudioTrack 创建、
 * 写入和清理都在单线程执行器里完成，不阻塞主线程；所有平台异常都 catch Throwable 后
 * 静默降级（返回 false 或只是没有声音）。{@link #stop}、{@link #release} 可在任意线程
 * 调用，重复调用安全；release 之后再次 play 返回 false，不会崩溃。并发 play 时会先停掉
 * 上一次扫弦。
 *
 * <p>音频焦点：使用带 {@link Context} 的构造器时会申请
 * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}，并在播放结束、stop 或 release
 * 时归还；无参构造器保留给拿不到 Context 的纯播放场景，此时跳过节拍/焦点协商，只降级
 * 为“能响就响”，因此正式页面应优先使用 {@code new ChordPlayer(context)}。
 */
public final class ChordPlayer {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /** 扫弦总时长约 1.2 秒：最后一根弦仍有约 1.1 秒的余音。 */
    private static final int TOTAL_MS = 1200;
    /** 相邻弦起音间隔 30ms（需求 25~35ms 区间），四弦扫完约 90ms，听感接近一次快速下扫。 */
    private static final int STRUM_MS = 30;

    /** 最多同时合成 6 个音；和弦最多 4 个，多出的用于 playMidi 的通用场景。 */
    private static final int MAX_NOTES = 6;

    private final Object lock = new Object();
    private final AudioManager audioManager; // 无 Context 时为 null，只是不协商焦点
    private final ExecutorService worker;

    private volatile Session current;
    private volatile boolean playing;

    private boolean released;      // 受 lock 保护
    private AudioFocusRequest focus; // 受 lock 保护

    /** 一次扫弦会话；播放线程据 current 是否还是自己来判断是否已被 stop 取代。 */
    private static final class Session {
        final int[] midiNotes;
        volatile AudioTrack track;

        Session(int[] midiNotes) {
            this.midiNotes = midiNotes;
        }
    }

    /** 纯播放场景：不持有 Context，因此跳过音频焦点协商（见类注释）。 */
    public ChordPlayer() {
        this(null);
    }

    /**
     * @param context 用于获取 AudioManager 申请临时音频焦点；允许为 null（等同无参构造）
     */
    public ChordPlayer(Context context) {
        AudioManager manager = null;
        if (context != null) {
            try {
                Object service = context.getApplicationContext()
                        .getSystemService(Context.AUDIO_SERVICE);
                if (service instanceof AudioManager) {
                    manager = (AudioManager) service;
                }
            } catch (Throwable ignored) {
                // 某些定制系统上取服务可能失败：没有焦点协商也能发声
            }
        }
        this.audioManager = manager;
        ExecutorService executor = null;
        try {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ukulele-chord-player");
                thread.setDaemon(true); // 不阻止进程退出
                return thread;
            });
        } catch (Throwable ignored) {
            // 线程池创建失败：play 将直接返回 false
        }
        this.worker = executor;
    }

    /** 播放一个和弦：按 {@link ChordLibrary#midi} 换算成音高后交给 {@link #playMidi}。 */
    public boolean play(ChordLibrary.Chord chord) {
        if (chord == null) {
            return false;
        }
        return playMidi(ChordLibrary.midi(chord));
    }

    /**
     * 播放一组 MIDI 音高，顺序按 G→C→E→A 依次错开扫弦；-1（闷弦）与非法音高会被跳过。
     *
     * @return 已接受并开始合成播放返回 true；已 release、音高全无效或线程池不可用返回 false
     */
    public boolean playMidi(int[] midiNotes) {
        final int[] notes = sanitize(midiNotes);
        if (notes.length == 0) {
            return false;
        }
        synchronized (lock) {
            if (released || worker == null) {
                return false;
            }
            stopLocked(); // 先停掉上一次扫弦，避免两段声音叠在一起
            final Session session = new Session(notes);
            current = session;
            playing = true;
            requestFocusLocked();
            try {
                worker.execute(() -> playSession(session));
            } catch (Throwable rejected) {
                current = null;
                playing = false;
                releaseFocusLocked();
                return false;
            }
            return true;
        }
    }

    /** 立即停止当前扫弦并归还音频焦点。幂等，可在任意线程调用。 */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /** 是否正在播放（从 play 返回到播放结束/stop 之间为 true）。 */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * 释放播放器：停止当前扫弦、关停执行器并归还音频焦点。幂等；release 之后不可再 play。
     * 典型调用位置是 Activity.onDestroy()。
     */
    public void release() {
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            stopLocked();
        }
        ExecutorService executor = worker;
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {
                // 已关闭
            }
        }
    }

    // ------------------------------------------------------------ 播放线程

    /** 在单线程执行器里合成并播放一次扫弦；无论成功失败都会释放音轨并复位状态。 */
    private void playSession(Session session) {
        AudioTrack track = null;
        try {
            if (current != session) {
                return; // 还没开始就被新的播放/stop 取代
            }
            final short[] pcm = synthesize(session.midiNotes);
            if (current != session) {
                return;
            }
            int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            if (minimum <= 0) {
                return; // 设备不支持该 PCM 格式
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_MASK)
                            .setEncoding(ENCODING)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // 整段 PCM 一次放进缓冲：写完后播放头自然推进，避免边合成边写的抖动
                    .setBufferSizeInBytes(Math.max(minimum, pcm.length * 2))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return;
            }
            session.track = track;
            if (current != session) {
                return;
            }
            track.setVolume(0.95f);
            track.play();
            int offset = 0;
            while (offset < pcm.length && current == session) {
                int written = track.write(pcm, offset, pcm.length - offset,
                        AudioTrack.WRITE_BLOCKING);
                if (written <= 0) {
                    break;
                }
                offset += written;
            }
            // 等播放头走完再释放，避免把余音截断；若播放头异常则最多再等 2 秒
            final long deadline = System.nanoTime() + 2_000_000_000L;
            while (current == session) {
                long played = track.getPlaybackHeadPosition() & 0xffffffffL;
                if (played >= pcm.length || System.nanoTime() > deadline) {
                    break;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 音频输出不可用（被占用/设备切换/系统回收）：静默降级，仅表现为没有声音
        } finally {
            releaseTrackQuietly(track);
            synchronized (lock) {
                if (current == session) {
                    current = null;
                    playing = false;
                    releaseFocusLocked();
                }
            }
        }
    }

    // ------------------------------------------------------------ 合成

    /**
     * 把一组 MIDI 音高合成为约 1.2 秒的单声道 16bit PCM。
     * 先叠加各弦波形，再按峰值归一化，最后做结尾淡出。
     */
    private static short[] synthesize(int[] midiNotes) {
        final int totalFrames = SAMPLE_RATE * TOTAL_MS / 1000;
        final int strumFrames = SAMPLE_RATE * STRUM_MS / 1000;
        final float[] mix = new float[totalFrames];
        for (int i = 0; i < midiNotes.length; i++) {
            int start = i * strumFrames;
            if (start >= totalFrames) {
                break;
            }
            double frequency = 440.0 * Math.pow(2.0, (midiNotes[i] - 69) / 12.0);
            pluck(mix, start, totalFrames, frequency);
        }

        // 峰值归一化：多根弦同时起振时峰值可能超过满量程，统一压到 0.85 防削波，
        // 同时让不同和弦的响度保持一致。
        float peak = 0f;
        for (float sample : mix) {
            float value = Math.abs(sample);
            if (value > peak) {
                peak = value;
            }
        }
        if (peak > 0.0001f) {
            float scale = 0.85f / peak;
            for (int i = 0; i < mix.length; i++) {
                mix[i] *= scale;
            }
        }

        final int fadeFrames = SAMPLE_RATE * 30 / 1000; // 结尾 30ms 线性淡出，避免“咔”声
        short[] pcm = new short[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            float value = mix[i];
            int remain = totalFrames - i;
            if (remain < fadeFrames) {
                value *= remain / (float) fadeFrames;
            }
            if (value > 1f) {
                value = 1f;
            } else if (value < -1f) {
                value = -1f;
            }
            pcm[i] = (short) Math.round(value * Short.MAX_VALUE);
        }
        return pcm;
    }

    /**
     * Karplus-Strong 拨弦：噪声激励填入延迟线，之后每读出一个样本就做“当前与下一个样本的
     * 平均再乘衰减”写回，形成持续衰减的弦振动。每个样本 O(1)，适合播放前实时生成。
     *
     * @param mix         输出混音缓冲（float，范围约 [-1,1]）
     * @param startFrame  本弦起音位置（实现扫弦错开）
     * @param totalFrames 总线长
     * @param frequency   目标音高频率
     */
    private static void pluck(float[] mix, int startFrame, int totalFrames, double frequency) {
        if (frequency <= 0) {
            return;
        }
        // 两样本平均滤波每个循环会额外引入约 0.5 个样本的延迟，因此用 (RATE/f - 0.5)
        // 反推整数延迟长度，让实际音高尽量贴近目标音（误差通常小于 5 音分）。
        int delay = (int) Math.round(SAMPLE_RATE / frequency - 0.5);
        if (delay < 2) {
            delay = 2;
        }
        final float[] line = new float[delay];
        final Random noise = new Random(0x5EEDL + Math.round(frequency));
        float previous = 0f;
        for (int i = 0; i < delay; i++) {
            float white = noise.nextFloat() * 2f - 1f;
            previous = 0.5f * (white + previous); // 轻低通，去掉过刺的高频噪声
            line[i] = previous;
        }
        final float damping = 0.998f; // 每圈衰减，决定余音长度（约 1 秒仍有可闻尾音）
        final float gain = 0.30f;     // 单弦增益，叠加后由峰值归一化统一响度
        int index = 0;
        for (int frame = startFrame; frame < totalFrames; frame++) {
            float current = line[index];
            int next = index + 1 == delay ? 0 : index + 1;
            float filtered = 0.5f * (current + line[next]);
            line[index] = filtered * damping;
            index = next;
            mix[frame] += current * gain;
        }
    }

    // ------------------------------------------------------------ 音频焦点

    private static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void requestFocusLocked() {
        final AudioManager manager = audioManager;
        if (manager == null || focus != null) {
            return;
        }
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes())
                    .setOnAudioFocusChangeListener(change -> {
                        // 被其他应用长期/暂时抢占时主动收尾；短暂允许压低音量（CAN_DUCK 为负值）
                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            stop();
                        }
                    })
                    .build();
            // 短暂音效即使未获批准也继续播放：抢不到焦点只是不参与系统避让，不应静音
            manager.requestAudioFocus(focus);
        } catch (Throwable ignored) {
            focus = null;
        }
    }

    private void releaseFocusLocked() {
        final AudioManager manager = audioManager;
        final AudioFocusRequest request = focus;
        focus = null;
        if (manager != null && request != null) {
            try {
                manager.abandonAudioFocusRequest(request);
            } catch (Throwable ignored) {
                // 系统已回收焦点
            }
        }
    }

    // ------------------------------------------------------------ 内部工具

    private void stopLocked() {
        final Session session = current;
        current = null;
        playing = false;
        releaseFocusLocked();
        if (session != null) {
            final AudioTrack track = session.track;
            if (track != null) {
                // 只暂停/停止，真正的 release 由播放线程的 finally 负责，避免二次释放
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (Throwable ignored) {
                    // 打断阻塞中的 write，让播放线程尽快走到退出分支
                }
            }
        }
    }

    /** 过滤 -1（闷弦）与越界音高，并按 MAX_NOTES 截断；返回新数组便于跨线程使用。 */
    private static int[] sanitize(int[] midiNotes) {
        if (midiNotes == null || midiNotes.length == 0) {
            return new int[0];
        }
        int[] buffer = new int[Math.min(MAX_NOTES, midiNotes.length)];
        int count = 0;
        for (int note : midiNotes) {
            if (note < 0 || note > 127) {
                continue;
            }
            buffer[count++] = note;
            if (count == buffer.length) {
                break;
            }
        }
        int[] result = new int[count];
        System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    private static void releaseTrackQuietly(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.pause();
        } catch (Throwable ignored) {
            // 未初始化/已释放
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
