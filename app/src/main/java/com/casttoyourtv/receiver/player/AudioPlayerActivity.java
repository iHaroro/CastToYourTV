package com.casttoyourtv.receiver.player;

import android.app.Activity;
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
    private TextView timeView;
    private TextView volumeView;
    private TextView deviceView;
    private View pulseDot;

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

        byte[] art = s.getArtwork();
        if (art != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(art, 0, art.length);
            if (bmp != null) {
                artworkView.setImageBitmap(bmp);
            } else {
                artworkView.setImageResource(android.R.drawable.ic_media_play);
            }
        } else {
            artworkView.setImageResource(android.R.drawable.ic_media_play);
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
        long totalSec = ms / 1000;
        timeView.setText(String.format("已播放 %d:%02d", totalSec / 60, totalSec % 60));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        AudioSession.get().setListener(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
