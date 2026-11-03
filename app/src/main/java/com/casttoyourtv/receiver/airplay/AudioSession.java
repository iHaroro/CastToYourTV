package com.casttoyourtv.receiver.airplay;

import android.os.Handler;
import android.os.Looper;

/**
 * 音频播放元数据单例：RtspListener 更新曲名/艺人/封面/音量/时长，
 * AudioPlayerActivity 注册回调实时刷新界面。
 */
public final class AudioSession {

    public interface Listener {
        void onChanged();
    }

    private static final AudioSession INSTANCE = new AudioSession();

    public static AudioSession get() {
        return INSTANCE;
    }

    private String title;
    private String artist;
    private String album;
    private byte[] artwork; // JPEG/PNG 原始字节
    private String clientName;  // 连接设备名，如「涛的 iPhone」
    private String clientModel; // 设备型号，如 iPhone14,3
    private String clientIp;
    private float volumeDb = -20f;
    private long startedAtMs; // RECORD 时打点，用于「已播放时长」
    private boolean playing;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioSession() {
    }

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    public synchronized void onStart() {
        if (playing) return; // 重复 SETUP 不重置，避免封面/曲名闪没
        playing = true;
        startedAtMs = System.currentTimeMillis();
        title = null;
        artist = null;
        album = null;
        artwork = null;
        clientName = null;
        clientModel = null;
        clientIp = null;
        notifyChanged();
    }

    public synchronized void onStop() {
        playing = false;
        notifyChanged();
    }

    public synchronized void setTrack(String title, String artist, String album) {
        // 同一首歌重复推送时不触发刷新，避免界面闪烁
        if (java.util.Objects.equals(title, this.title)
                && java.util.Objects.equals(artist, this.artist)
                && java.util.Objects.equals(album, this.album)) {
            return;
        }
        if (title != null) this.title = title;
        if (artist != null) this.artist = artist;
        if (album != null) this.album = album;
        notifyChanged();
    }

    public synchronized void setArtwork(byte[] data) {
        if (artwork != null && java.util.Arrays.equals(artwork, data)) return;
        artwork = data;
        notifyChanged();
    }

    public synchronized void setVolume(float db) {
        volumeDb = db;
        notifyChanged();
    }

    public synchronized void setClient(String name, String model, String ip) {
        if (java.util.Objects.equals(name, clientName)
                && java.util.Objects.equals(model, clientModel)
                && java.util.Objects.equals(ip, clientIp)) {
            return;
        }
        if (name != null) clientName = name;
        if (model != null) clientModel = model;
        if (ip != null) clientIp = ip;
        notifyChanged();
    }

    public synchronized boolean isPlaying() { return playing; }
    public synchronized String getTitle() { return title; }
    public synchronized String getArtist() { return artist; }
    public synchronized String getAlbum() { return album; }
    public synchronized byte[] getArtwork() { return artwork; }
    public synchronized float getVolumeDb() { return volumeDb; }
    public synchronized String getClientName() { return clientName; }
    public synchronized String getClientModel() { return clientModel; }
    public synchronized String getClientIp() { return clientIp; }

    public synchronized long getElapsedMs() {
        return playing ? System.currentTimeMillis() - startedAtMs : 0;
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) main.post(l::onChanged);
    }
}
