package com.ltm.geoduel.client.audio;

import com.ltm.geoduel.common.Log;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Âm thanh của game: nhạc nền hai trạng thái (êm khi suy nghĩ, dồn dập khi một
 * bên đã ghim) + hiệu ứng ngắn. Mọi lỗi âm thanh chỉ log, không được ảnh hưởng game.
 * File lấy từ assets/audio/ (tự tổng hợp bằng MusicSynth nếu thiếu).
 */
public final class SoundManager {
    private static final float MUSIC_GAIN_DB = -14f;
    private static final float SFX_GAIN_DB = -8f;

    private static final Map<String, Clip> clips = new HashMap<>();
    private static volatile boolean ready = false;
    private static volatile boolean muted = false;
    private static volatile String currentMusic = null; // "ambient" | "tension" | null

    private SoundManager() {}

    /** Gọi một lần lúc khởi động client (chạy trên thread nền — tổng hợp nhạc hơi lâu). */
    public static void init(Path audioDir) {
        Thread t = new Thread(() -> {
            try {
                MusicSynth.ensureAll(audioDir);
                for (String name : new String[]{"ambient", "tension", "pin", "lock", "alert", "ding", "win", "lose"}) {
                    Path f = audioDir.resolve(name + ".wav");
                    try (AudioInputStream in = AudioSystem.getAudioInputStream(f.toFile())) {
                        Clip clip = AudioSystem.getClip();
                        clip.open(in);
                        setGain(clip, name.equals("ambient") || name.equals("tension")
                                ? MUSIC_GAIN_DB : SFX_GAIN_DB);
                        clips.put(name, clip);
                    }
                }
                ready = true;
                Log.info("Sound", "Am thanh san sang (" + clips.size() + " file)");
            } catch (Exception ex) {
                Log.warn("Sound", "Khong khoi tao duoc am thanh, game chay khong tieng: " + ex.getMessage());
            }
        }, "sound-init");
        t.setDaemon(true);
        t.start();
    }

    private static void setGain(Clip clip, float db) {
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            gain.setValue(Math.max(gain.getMinimum(), db));
        } catch (Exception ignored) {}
    }

    // ================= nhạc nền =================

    public static synchronized void startAmbient() { startMusic("ambient"); }
    public static synchronized void startTension() { startMusic("tension"); }

    private static void startMusic(String name) {
        if (name.equals(currentMusic)) return;
        currentMusic = name;
        if (!ready || muted) return;
        stopClip("ambient");
        stopClip("tension");
        Clip clip = clips.get(name);
        if (clip != null) {
            clip.setFramePosition(0);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
        }
    }

    public static synchronized void stopMusic() {
        currentMusic = null;
        stopClip("ambient");
        stopClip("tension");
    }

    private static void stopClip(String name) {
        Clip clip = clips.get(name);
        if (clip != null && clip.isRunning()) clip.stop();
    }

    // ================= hiệu ứng =================

    public static void sfx(String name) {
        if (!ready || muted) return;
        Clip clip = clips.get(name);
        if (clip == null) return;
        try {
            if (clip.isRunning()) clip.stop();
            clip.setFramePosition(0);
            clip.start();
        } catch (Exception ignored) {}
    }

    // ================= tắt/bật tiếng =================

    public static boolean isMuted() { return muted; }

    public static synchronized void setMuted(boolean m) {
        muted = m;
        if (m) {
            stopClip("ambient");
            stopClip("tension");
        } else if (currentMusic != null && ready) {
            Clip clip = clips.get(currentMusic);
            if (clip != null) {
                clip.setFramePosition(0);
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            }
        }
    }

    public static boolean toggleMuted() {
        setMuted(!muted);
        return muted;
    }
}
