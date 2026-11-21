package com.ukulele.monitor;

import java.io.*;

/** Small immutable HOG/linear-SVM classifier; no network or native runtime is needed. */
final class JianpuDigitModel {
    static final int WIDTH = 16, HEIGHT = 24, FEATURES = 308, CLASSES = 11;
    private final float[][] weights;
    private final float[] biases;

    private JianpuDigitModel(float[][] weights, float[] biases) {
        this.weights = weights;
        this.biases = biases;
    }

    static JianpuDigitModel read(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "US-ASCII"))) {
            if (!"JIANPU-SVM-2 308 11".equals(reader.readLine()))
                throw new IOException("简谱数字模型版本或维度错误");
            float[][] weights = new float[CLASSES][FEATURES];
            float[] biases = new float[CLASSES];
            for (int digit = 0; digit < CLASSES; digit++) {
                String line = reader.readLine();
                if (line == null || line.length() > 16000) throw new IOException("简谱模型截断");
                String[] fields = line.trim().split("\\s+");
                if (fields.length != FEATURES + 1) throw new IOException("简谱模型长度错误");
                for (int i = 0; i < fields.length; i++) {
                    float value;
                    try { value = Float.parseFloat(fields[i]); }
                    catch (NumberFormatException error) { throw new IOException("简谱模型参数错误", error); }
                    if (Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > 10000)
                        throw new IOException("简谱模型参数超限");
                    if (i == 0) biases[digit] = value;
                    else weights[digit][i - 1] = value;
                }
            }
            if (reader.readLine() != null) throw new IOException("简谱模型存在额外数据");
            return new JianpuDigitModel(weights, biases);
        }
    }

    static final class Prediction {
        final int digit;
        final float score, gap;
        Prediction(int digit, float score, float gap) {
            this.digit = digit; this.score = score; this.gap = gap;
        }
        boolean accepted() { return score >= 0.15f && gap >= 0.5f; }
    }

    Prediction predict(boolean[] pixels) {
        float[] features = features(pixels);
        int digit = -1;
        float first = -Float.MAX_VALUE, second = -Float.MAX_VALUE;
        for (int d = 0; d < CLASSES; d++) {
            float score = biases[d];
            for (int i = 0; i < FEATURES; i++) score += weights[d][i] * features[i];
            if (score > first) { second = first; first = score; digit = d; }
            else if (score > second) second = score;
        }
        return new Prediction(digit, first, first - second);
    }

    static float[] features(boolean[] pixels) {
        if (pixels.length != WIDTH * HEIGHT) throw new IllegalArgumentException("数字图像维度错误");
        float[] features = new float[FEATURES];
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int dx = x == 0 || x == WIDTH - 1 ? 0 : bit(pixels, x + 1, y) - bit(pixels, x - 1, y);
            int dy = y == 0 || y == HEIGHT - 1 ? 0 : bit(pixels, x, y + 1) - bit(pixels, x, y - 1);
            int bin = dx == 0 ? 4 : dy == 0 ? 0 : dx == dy ? 2 : 6;
            features[((y / 4) * 4 + x / 4) * 8 + bin] += (float)Math.sqrt(dx * dx + dy * dy);
            if (pixels[y * WIDTH + x]) {
                features[192 + (y / 2) * 8 + x / 2] += 0.25f;
                features[288 + y / 2] += 1f / 32;
                features[300 + x / 2] += 1f / 48;
            }
        }
        for (int cell = 0; cell < 24; cell++) {
            float squared = 0.01f;
            for (int bin = 0; bin < 8; bin++) squared += features[cell * 8 + bin] * features[cell * 8 + bin];
            float norm = (float)Math.sqrt(squared);
            for (int bin = 0; bin < 8; bin++) features[cell * 8 + bin] /= norm;
        }
        return features;
    }

    private static int bit(boolean[] pixels, int x, int y) { return pixels[y * WIDTH + x] ? 1 : 0; }
}
