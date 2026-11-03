package com.casttoyourtv.receiver.player;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.casttoyourtv.receiver.R;

/**
 * 全屏播放页：Media3 ExoPlayer（Google 官方内核，兼容 HTTP/HLS/DASH 等）。
 */
public class PlayerActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private static final String TAG = "CastPlayer";
    private static PlayerActivity instance;

    /** 画面比例模式，遥控器上/下键循环切换 */
    private enum ResizeMode {
        FIT("原始比例"),
        FILL("拉伸全屏"),
        ZOOM("裁切填满"),
        RATIO_16_9("强制 16:9"),
        RATIO_4_3("强制 4:3");

        final String label;
        ResizeMode(String label) { this.label = label; }
    }

    private FrameLayout root;
    private SurfaceView surface;
    private TextView overlay;
    private ExoPlayer player;
    private String url;
    private String title;
    private boolean surfaceReady;
    private float videoAspect;      // 视频像素宽高比（已换算像素比与旋转），0 表示未知
    private ResizeMode resizeMode = ResizeMode.FIT;

    public static void finishInstance() {
        if (instance != null) instance.finish();
    }

    /** 快进到指定毫秒（直播流忽略） */
    public static void seekToMs(long positionMs) {
        PlayerActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(() -> a.doSeek(positionMs));
    }

    public static void pauseInstance() {
        PlayerActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.player != null) a.player.pause(); });
    }

    public static void resumeInstance() {
        PlayerActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.player != null) a.player.play(); });
    }

    private void doSeek(long positionMs) {
        if (player == null) return;
        // 直播流没有时长，禁止 seek
        if (player.isCurrentMediaItemLive()) {
            Log.i(TAG, "直播流，忽略 seek");
            showOverlay("直播流不支持快进");
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            positionMs = Math.max(0, Math.min(positionMs, duration - 500));
        }
        player.seekTo(positionMs);
        showOverlay("▶ " + formatMs(positionMs));
        Log.i(TAG, "seekTo " + positionMs);
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            showOverlay("❚❚ 已暂停");
        } else {
            player.play();
            showOverlay("▶ 播放中");
        }
    }

    private void seekRelative(long deltaMs) {
        if (player == null) return;
        if (player.isCurrentMediaItemLive()) {
            showOverlay("直播流不支持快进");
            return;
        }
        long target = player.getCurrentPosition() + deltaMs;
        doSeek(target);
    }

    /** 按目标宽高比计算 SurfaceView 尺寸并居中 */
    private void applyResizeMode() {
        if (root == null || surface == null) return;
        int cw = root.getWidth();
        int ch = root.getHeight();
        if (cw == 0 || ch == 0) return;

        int w, h;
        if (resizeMode == ResizeMode.FILL) {
            w = cw;
            h = ch;
        } else {
            float ar;
            switch (resizeMode) {
                case RATIO_16_9: ar = 16f / 9f; break;
                case RATIO_4_3:  ar = 4f / 3f;  break;
                default:         ar = videoAspect > 0 ? videoAspect : 16f / 9f; break;
            }
            boolean fit = resizeMode != ResizeMode.ZOOM;
            // fit: 完整显示（可能留黑边）；zoom: 放大铺满（裁掉超出部分）
            if ((cw / (float) ch > ar) == fit) {
                h = ch;
                w = Math.round(ch * ar);
            } else {
                w = cw;
                h = Math.round(cw / ar);
            }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        surface.setLayoutParams(lp);
        Log.i(TAG, "resizeMode=" + resizeMode + " surface=" + w + "x" + h + " videoAspect=" + videoAspect);
    }

    private void cycleResizeMode(int dir) {
        ResizeMode[] modes = ResizeMode.values();
        int next = (resizeMode.ordinal() + dir + modes.length) % modes.length;
        resizeMode = modes[next];
        applyResizeMode();
        showOverlay("画面比例：" + resizeMode.label);
    }

    private void showOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        overlay.removeCallbacks(hideOverlay);
        overlay.postDelayed(hideOverlay, 1500);
    }

    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);

    private String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                seekRelative(-10000);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                seekRelative(10000);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekRelative(30000);
                return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                seekRelative(-30000);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                cycleResizeMode(1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                cycleResizeMode(-1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_player);
        hideSystemUi();

        root = findViewById(R.id.root);
        surface = findViewById(R.id.surface);
        overlay = findViewById(R.id.tvOverlay);
        surface.getHolder().addCallback(this);

        url = getIntent().getStringExtra(EXTRA_URL);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        overlay.setText(title != null ? title : "");
        overlay.postDelayed(() -> overlay.setVisibility(View.GONE), 4000);

        Log.i(TAG, "onCreate url=" + url);
        createPlayerIfNeeded();
        if (surfaceReady) attachVideoSurface();
        playUrl();
    }

    private void createPlayerIfNeeded() {
        if (player != null) return;
        DefaultMediaSourceFactory factory =
                new DefaultMediaSourceFactory(getApplicationContext());
        player = new ExoPlayer.Builder(getApplicationContext(), factory)
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "ExoPlayer error code=" + error.errorCode
                        + " cause=" + error.getCause(), error);
                overlay.setText("播放失败: " + error.errorCode);
                overlay.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.i(TAG, "state=" + playbackState);
                if (playbackState == Player.STATE_READY) {
                    overlay.postDelayed(() -> overlay.setVisibility(View.GONE), 3000);
                } else if (playbackState == Player.STATE_ENDED) {
                    finish();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    float ar = vs.width * vs.pixelWidthHeightRatio / vs.height;
                    // 竖屏/旋转视频要交换宽高
                    if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
                        ar = 1f / ar;
                    }
                    videoAspect = ar;
                    Log.i(TAG, "video size " + vs.width + "x" + vs.height + " aspect=" + ar);
                    applyResizeMode();
                }
            }
        });
    }

    private void playUrl() {
        if (url == null || url.isEmpty() || player == null) {
            Log.e(TAG, "playUrl skipped, url=" + url);
            return;
        }
        Log.i(TAG, "ExoPlayer setMediaItem: " + url);
        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void attachVideoSurface() {
        if (player != null) {
            player.setVideoSurfaceHolder(surface.getHolder());
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        attachVideoSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        applyResizeMode();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (player != null) player.clearVideoSurface();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        url = intent.getStringExtra(EXTRA_URL);
        title = intent.getStringExtra(EXTRA_TITLE);
        overlay.setText(title != null ? title : "");
        overlay.setVisibility(View.VISIBLE);
        overlay.postDelayed(() -> overlay.setVisibility(View.GONE), 4000);
        Log.i(TAG, "onNewIntent url=" + url);
        playUrl();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
