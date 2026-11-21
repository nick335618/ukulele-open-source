package com.ukulele.monitor.ai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bundled Mandarin ASR fallback. Audio stays on the phone; model and decoding never run on UI. */
final class OfflineVoiceRecognizer {
    interface Callback {
        void preparing(String message);
        void ready();
        void partial(String text);
        void result(String text);
        void error(String message);
    }
    private static final int RATE = 16000;
    private static final long MAX_MS = 25000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile boolean released;
    private volatile boolean finishing;
    private volatile AudioRecord recording;
    private Model model; // accessed only by worker

    OfflineVoiceRecognizer(Context context) { this.context = context.getApplicationContext(); }
    static boolean available(Context context) {return VoiceModelPack.available(context);}
    void start(Callback callback) {
        cancel();
        final int id = generation;
        finishing = false;
        post(id, () -> callback.preparing("正在准备离线中文语音…"));
        worker.execute(() -> recognize(id, callback));
    }
    void finish() {
        finishing = true;
        AudioRecord audio = recording;
        if (audio != null) try { audio.stop(); } catch (RuntimeException ignored) { }
    }
    void cancel() {
        generation++;
        AudioRecord audio = recording;
        if (audio != null) try { audio.stop(); } catch (RuntimeException ignored) { }
    }
    void release() {
        released = true;
        cancel();
        worker.execute(() -> { if (model != null) { model.close(); model = null; } });
        worker.shutdown();
    }
    private boolean current(int id) { return !released && generation == id; }
    private void post(int id, Runnable action) { main.post(() -> { if (current(id)) action.run(); }); }
    private void recognize(int id, Callback callback) {
        AudioRecord audio = null;
        boolean modelReady = false;
        String stage = "unpack";
        try {
            if (!current(id)) return;
            File directory = unpackModel();
            if (!current(id)) return;
            stage = "load-native-model";
            if (model == null) model = new Model(directory.getAbsolutePath());
            modelReady = true;
            stage = "microphone";
            if (!current(id)) return;
            try (Recognizer decoder = new Recognizer(model, RATE)) {
                decoder.setEndpointerDelays(5f, .8f, 20f);
                int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IOException("unsupported microphone format");
                audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 8192));
                if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("microphone unavailable");
                recording = audio;
                if (!current(id)) return;
                if (finishing) { post(id, () -> callback.error("已停止，可重新点击麦克风")); return; }
                audio.startRecording();
                if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("microphone did not start");
                post(id, callback::ready);
                short[] buffer = new short[1600];
                long began = SystemClock.elapsedRealtime(), lastPartial = 0;
                String previous = "", result = "";
                while (current(id) && !finishing && SystemClock.elapsedRealtime() - began < MAX_MS) {
                    int count = audio.read(buffer, 0, buffer.length);
                    if (count <= 0) {
                        if (finishing || !current(id)) break;
                        throw new IOException("microphone read failed");
                    }
                    if (decoder.acceptWaveForm(buffer, count)) {
                        result = text(decoder.getResult(), "text");
                        if (!result.isEmpty()) break;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPartial >= 180) {
                        String partial = text(decoder.getPartialResult(), "partial");
                        if (!partial.isEmpty() && !partial.equals(previous)) {
                            previous = partial;
                            post(id, () -> callback.partial(partial));
                        }
                        lastPartial = now;
                    }
                }
                if (!current(id)) return;
                if (result.isEmpty()) result = text(decoder.getFinalResult(), "text");
                final String recognized = result;
                if (recognized.isEmpty()) post(id, () -> callback.error("没有听清，请靠近手机再说一次"));
                else post(id, () -> callback.result(recognized));
            }
        } catch (Throwable error) {
            android.util.Log.w("UkuleleVoice", "offline recognition failed at " + stage, error);
            final String message = !VoiceModelPack.available(context) ? "请在设置 → AI 与音乐搭子中导入离线中文语音包，或使用系统语音服务" : modelReady ? "麦克风未能启动，请检查权限与占用后重试" : "离线语音准备失败，请检查手机存储空间后重试";
            post(id, () -> callback.error(message));
        } finally {
            if (recording == audio) recording = null;
            if (audio != null) {
                try { audio.stop(); } catch (RuntimeException ignored) { }
                audio.release();
            }
        }
    }
    private static String text(String json, String field) {
        try {
            return new JSONObject(json).optString(field, "").trim()
                    .replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "");
        } catch (Exception e) { return ""; }
    }
    private static final Object MODEL_FILES_LOCK = new Object();
    private File unpackModel() throws IOException {
        synchronized (MODEL_FILES_LOCK) { return unpackModelLocked(); }
    }
    private File unpackModelLocked() throws IOException {
        if (released) throw new IOException("cancelled");
        File parent = new File(context.getNoBackupFilesDir(), "voice");
        File target = new File(parent, "vosk-model-small-cn-0.22");
        File ready = new File(target, ".ready-0.22");
        if (ready.isFile() && new File(target, "am/final.mdl").isFile()
                && new File(target, "graph/HCLr.fst").isFile()) return target;
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create model directory");
        // All paths come from a bundled, CRC-checked asset; still reject path traversal.
        String root = parent.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(VoiceModelPack.open(context))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                if (released) throw new IOException("cancelled");
                File destination = new File(parent, entry.getName());
                if (!destination.getCanonicalPath().startsWith(root)) throw new IOException("unsafe model path");
                if (entry.isDirectory()) {
                    if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("mkdir failed");
                } else {
                    File folder = destination.getParentFile();
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("mkdir failed");
                    File partial = new File(destination.getPath() + ".part");
                    try (OutputStream out = new FileOutputStream(partial)) {
                        int count;
                        while ((count = zip.read(buffer)) >= 0) {
                            if (released) throw new IOException("cancelled");
                            total += count;
                            if (total > 160L * 1024 * 1024) throw new IOException("model too large");
                            out.write(buffer, 0, count);
                        }
                    }
                    Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        if (!new File(target, "am/final.mdl").isFile() || !new File(target, "graph/HCLr.fst").isFile())
            throw new IOException("incomplete speech model");
        if (!ready.createNewFile() && !ready.isFile()) throw new IOException("model marker failed");
        return target;
    }
}
