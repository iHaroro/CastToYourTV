package com.casttoyourtv.receiver.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.casttoyourtv.receiver.CastService;
import com.casttoyourtv.receiver.airplay.AudioSession;

/**
 * AirPlay 音乐播放页：封面大图 + 曲名/艺人 + 已播时长 + 音量。
 * 有元数据显示元数据，没有就显示「正在播放」占位（冗余 UI）。
 */
public class AudioPlayerActivity extends Activity implements AudioSession.Listener {

    private static volatile AudioPlayerActivity instance;

    private ImageView artworkView;
    private TextView titleView;
    private TextView artistView;
    private TextView lyricsView;
    private TextView timeView;
    private TextView volumeView;
    private TextView deviceView;
    private View pulseDot;
    private android.app.Dialog controlPanel;

    // 封面解码放到单线程里，且按 byte[] 引用做缓存：音量/曲名变化时不再重新解码导致闪图
    private final java.util.concurrent.ExecutorService artworkLoader =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "artwork-decode");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean destroyed;
    private byte[] shownArtwork;
    private int artworkGeneration;
    private boolean placeholderShown = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateTime();
            handler.postDelayed(this, 1000);
        }
    };

    public static void finishInstance() {
        AudioPlayerActivity a = instance;
        if (a != null) a.finish();
    }

    public static boolean isAlive() {
        return instance != null;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildLayout();
        AudioSession.get().setListener(this);
        refresh();
        handler.post(ticker);
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = dp(48);
        column.setPadding(pad, pad, pad, pad);

        // 封面：有图显示图，没图显示音符占位
        artworkView = new ImageView(this);
        int artSize = dp(320);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(artSize, artSize);
        artworkView.setLayoutParams(artLp);
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF1A1A2E);
        placeholder.setCornerRadius(dp(16));
        artworkView.setBackground(placeholder);
        column.addView(artworkView);

        // 曲名
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(32);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(32);
        titleView.setLayoutParams(titleLp);
        column.addView(titleView);

        // 艺人 · 专辑
        artistView = new TextView(this);
        artistView.setTextColor(0xFFAAAAAA);
        artistView.setTextSize(20);
        artistView.setGravity(Gravity.CENTER);
        artistView.setMaxLines(1);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(12);
        artistView.setLayoutParams(artistLp);
        column.addView(artistView);

        // 歌词（仅当源端确实下发 asly/lyrics 时显示）
        lyricsView = new TextView(this);
        lyricsView.setTextColor(0xFFCCCCCC);
        lyricsView.setTextSize(17);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setMaxLines(4);
        lyricsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lyricsView.setVisibility(View.GONE);
        LinearLayout.LayoutParams lyricsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lyricsLp.topMargin = dp(16);
        lyricsView.setLayoutParams(lyricsLp);
        column.addView(lyricsView);

        // 已播时长（协议拿不到总时长，只显示已播放多久）
        timeView = new TextView(this);
        timeView.setTextColor(0xFF888888);
        timeView.setTextSize(16);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(24);
        timeView.setLayoutParams(timeLp);
        column.addView(timeView);

        // 音量
        volumeView = new TextView(this);
        volumeView.setTextColor(0xFF666666);
        volumeView.setTextSize(14);
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        volLp.topMargin = dp(8);
        volumeView.setLayoutParams(volLp);
        column.addView(volumeView);

        // 当前连接设备
        deviceView = new TextView(this);
        deviceView.setTextColor(0xFF555566);
        deviceView.setTextSize(13);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        devLp.topMargin = dp(16);
        deviceView.setLayoutParams(devLp);
        column.addView(deviceView);

        // 底部「正在播放」指示点（呼吸动画占位）
        pulseDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF4CAF50);
        pulseDot.setBackground(dot);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(10), dp(10));
        dotLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dotLp.bottomMargin = dp(24);
        pulseDot.setLayoutParams(dotLp);

        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(pulseDot);
        setContentView(root);
        startPulse();
    }

    private void startPulse() {
        pulseDot.animate().alpha(0.2f).setDuration(800)
                .withEndAction(() -> pulseDot.animate().alpha(1f).setDuration(800)
                        .withEndAction(this::startPulse).start()).start();
    }

    @Override
    public void onChanged() {
        refresh();
    }

    private void refresh() {
        AudioSession s = AudioSession.get();

        String title = s.getTitle();
        titleView.setText(title != null ? title : "正在播放");

        String artist = s.getArtist();
        String album = s.getAlbum();
        StringBuilder sub = new StringBuilder();
        if (artist != null) sub.append(artist);
        if (album != null) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(album);
        }
        artistView.setText(sub.length() > 0 ? sub.toString() : "AirPlay 音乐");

        String lyrics = s.getLyrics();
        if (lyrics != null && !lyrics.isEmpty()) {
            lyricsView.setText(lyrics);
            lyricsView.setVisibility(View.VISIBLE);
        } else {
            lyricsView.setText("");
            lyricsView.setVisibility(View.GONE);
        }

        byte[] art = s.getArtwork();
        if (art != shownArtwork) {
            shownArtwork = art;
            final int generation = ++artworkGeneration;
            if (art == null) {
                if (!placeholderShown) {
                    artworkView.setImageResource(android.R.drawable.ic_media_play);
                    placeholderShown = true;
                }
            } else {
                final byte[] data = art;
                artworkLoader.execute(() -> {
                    Bitmap bmp = null;
                    try {
                        bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    } catch (Throwable ignored) {
                    }
                    final Bitmap decoded = bmp;
                    runOnUiThread(() -> {
                        if (destroyed || generation != artworkGeneration || decoded == null) return;
                        artworkView.setImageBitmap(decoded);
                        placeholderShown = false;
                    });
                });
            }
        }

        float db = s.getVolumeDb();
        volumeView.setText(db <= -144f ? "音量：静音"
                : String.format("音量：%.0f dB", db));

        String cn = s.getClientName();
        String cm = s.getClientModel();
        String ci = s.getClientIp();
        StringBuilder dev = new StringBuilder("连接设备：");
        if (cn != null) {
            dev.append(cn);
        } else {
            dev.append("AirPlay 设备");
        }
        if (cm != null) dev.append("（").append(cm).append("）");
        if (ci != null) dev.append(" · ").append(ci);
        deviceView.setText(dev.toString());

        updateTime();
    }

    private void updateTime() {
        long ms = AudioSession.get().getElapsedMs();
        long totalMs = AudioSession.get().getDurationMs();
        long totalSec = ms / 1000;
        if (totalMs > 0) {
            long durationSec = totalMs / 1000;
            timeView.setText(String.format("已播放 %d:%02d / %d:%02d",
                    totalSec / 60, totalSec % 60,
                    durationSec / 60, durationSec % 60));
        } else {
            timeView.setText(String.format("已播放 %d:%02d", totalSec / 60, totalSec % 60));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        showControlPanel();
    }

    private void showControlPanel() {
        if (controlPanel != null && controlPanel.isShowing()) return;
        controlPanel = PlayerControlPanel.show(this, this::endCast);
    }

    private void endCast() {
        CastService.stopActiveCast(this);
        Intent i = new Intent(this, com.casttoyourtv.receiver.MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        artworkLoader.shutdownNow();
        handler.removeCallbacks(ticker);
        AudioSession.get().setListener(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
