package com.ltm.geoduel.client.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tự tổng hợp nhạc nền và hiệu ứng của game thành file WAV (44,1 kHz, 16-bit, mono)
 * — không cần tải nhạc ngoài, không vướng bản quyền. Muốn dùng nhạc riêng chỉ cần
 * chép file .wav cùng tên đè vào assets/audio/.
 *
 *  - ambient.wav : vòng hợp âm pad êm (32 s, lặp) — lúc quan sát/suy nghĩ
 *  - tension.wav : mạch đập dồn + tiếng tích tắc (12 s, lặp) — khi một bên đã ghim
 *  - pin.wav     : đặt ghim lên bản đồ
 *  - lock.wav    : khoá dự đoán (đã gửi)
 *  - alert.wav   : đối thủ đã gửi
 *  - ding.wav    : kết quả lượt
 *  - win.wav / lose.wav : kết trận
 */
public final class MusicSynth {
    private static final float RATE = 44_100f;

    private MusicSynth() {}

    /** Sinh mọi file còn thiếu trong {@code dir}. */
    public static void ensureAll(Path dir) throws IOException {
        Files.createDirectories(dir);
        writeIfMissing(dir.resolve("ambient.wav"), ambient());
        writeIfMissing(dir.resolve("tension.wav"), tension());
        writeIfMissing(dir.resolve("pin.wav"), blip(880, 640, 0.09));
        writeIfMissing(dir.resolve("lock.wav"), confirm());
        writeIfMissing(dir.resolve("alert.wav"), alert());
        writeIfMissing(dir.resolve("ding.wav"), ding());
        writeIfMissing(dir.resolve("win.wav"), arpeggio(new double[]{261.63, 329.63, 392.0, 523.25}, 0.16, 1.0));
        writeIfMissing(dir.resolve("lose.wav"), arpeggio(new double[]{329.63, 261.63, 196.0}, 0.22, 0.9));
    }

    // ================= các bản nhạc =================

    /** Pad 4 hợp âm × 8 s: Cmaj7 → Am7 → Fmaj7 → G6, chuyển mềm, lặp liền mạch. */
    private static double[] ambient() {
        double[][] chords = {
                {261.63, 329.63, 392.00, 493.88}, // Cmaj7
                {220.00, 261.63, 329.63, 392.00}, // Am7
                {174.61, 220.00, 261.63, 329.63}, // Fmaj7
                {196.00, 246.94, 293.66, 329.63}, // G6
        };
        double chordSec = 8, fadeSec = 2.5;
        int total = (int) (RATE * chordSec * chords.length);
        double[] out = new double[total];
        for (int c = 0; c < chords.length; c++) {
            int start = (int) (c * chordSec * RATE);
            for (int i = 0; i < chordSec * RATE; i++) {
                double t = i / RATE;
                // đường bao: vào/ra mềm để hoà lẫn hợp âm kế (kể cả vòng về đầu)
                double env = Math.min(1, Math.min(t / fadeSec, (chordSec - t) / fadeSec));
                env = env * env * (3 - 2 * env); // smoothstep
                double s = 0;
                for (double f : chords[c]) {
                    s += Math.sin(2 * Math.PI * f * t);
                    s += 0.35 * Math.sin(2 * Math.PI * (f * 1.003) * t); // detune ấm
                    s += 0.10 * Math.sin(2 * Math.PI * (f * 2) * t);
                }
                double shimmer = 0.05 * Math.sin(2 * Math.PI * chords[c][3] * 4 * t)
                        * (0.5 + 0.5 * Math.sin(2 * Math.PI * 0.13 * t));
                int idx = start + i;
                if (idx < total) out[idx] += (s / (chords[c].length * 1.45) + shimmer) * env * 0.5;
            }
        }
        return out;
    }

    /** 12 s ở 120 BPM: trống trầm nhịp đôi + tích tắc + nền rung thấp. Lặp liền mạch. */
    private static double[] tension() {
        double sec = 12;
        int total = (int) (RATE * sec);
        double[] out = new double[total];
        double beat = 0.5; // 120 BPM
        for (int i = 0; i < total; i++) {
            double t = i / RATE;
            double inBeat = t % beat;
            // trống trầm: xung 110 Hz tắt nhanh, mạnh hơn ở đầu mỗi ô nhịp (2 s)
            double kickEnv = Math.exp(-inBeat * 14);
            double accent = (t % 2.0) < beat ? 1.0 : 0.62;
            double s = 0.55 * accent * kickEnv * Math.sin(2 * Math.PI * 110 * t * (1 - inBeat * 0.12));
            // tích tắc ở phách lệch
            double inOff = (t + beat / 2) % beat;
            if (inOff < 0.02) {
                s += 0.18 * (Math.random() * 2 - 1) * (1 - inOff / 0.02);
            }
            // nền rung thấp dâng dần trong mỗi vòng 12 s
            s += 0.10 * Math.sin(2 * Math.PI * 55 * t) * (0.4 + 0.6 * (t / sec));
            out[i] = s * 0.8;
        }
        return out;
    }

    // ================= hiệu ứng =================

    private static double[] blip(double f1, double f2, double sec) {
        int n = (int) (RATE * sec);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double f = f1 + (f2 - f1) * (t / sec);
            out[i] = 0.5 * Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 26);
        }
        return out;
    }

    private static double[] confirm() {
        double[] a = blip(660, 660, 0.07), b = blip(990, 990, 0.11);
        double[] out = new double[(int) (RATE * 0.2)];
        System.arraycopy(a, 0, out, 0, a.length);
        for (int i = 0; i < b.length && i + (int) (RATE * 0.08) < out.length; i++) {
            out[i + (int) (RATE * 0.08)] += b[i];
        }
        return out;
    }

    private static double[] alert() {
        double[] out = new double[(int) (RATE * 0.3)];
        for (int rep = 0; rep < 2; rep++) {
            int off = (int) (rep * 0.13 * RATE);
            for (int i = 0; i < RATE * 0.09 && off + i < out.length; i++) {
                double t = i / RATE;
                out[off + i] += 0.42 * Math.sin(2 * Math.PI * 1180 * t) * Math.exp(-t * 30);
            }
        }
        return out;
    }

    private static double[] ding() {
        int n = (int) (RATE * 0.9);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double env = Math.exp(-t * 4.5);
            out[i] = env * (0.4 * Math.sin(2 * Math.PI * 659.26 * t)
                          + 0.3 * Math.sin(2 * Math.PI * 987.77 * t)
                          + 0.1 * Math.sin(2 * Math.PI * 1975.5 * t));
        }
        return out;
    }

    private static double[] arpeggio(double[] freqs, double noteSec, double gain) {
        int n = (int) (RATE * (freqs.length * noteSec + 0.6));
        double[] out = new double[n];
        for (int k = 0; k < freqs.length; k++) {
            int off = (int) (k * noteSec * RATE);
            for (int i = 0; i < RATE * 0.7 && off + i < n; i++) {
                double t = i / RATE;
                out[off + i] += gain * 0.35 * Math.sin(2 * Math.PI * freqs[k] * t) * Math.exp(-t * 5);
            }
        }
        return out;
    }

    // ================= ghi WAV =================

    private static void writeIfMissing(Path file, double[] samples) throws IOException {
        if (Files.exists(file)) return;
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double v = Math.max(-1, Math.min(1, samples[i]));
            int s = (int) (v * 32767);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcm), fmt, samples.length)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
