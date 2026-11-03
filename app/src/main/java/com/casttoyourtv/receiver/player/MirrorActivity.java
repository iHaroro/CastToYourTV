package com.casttoyourtv.receiver.player;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.casttoyourtv.receiver.airplay.MirrorSession;

import java.nio.ByteBuffer;

/**
 * AirPlay 屏幕镜像渲染页：SurfaceView + MediaCodec 硬解 H.264（Annex-B 直接喂）。
 * 数据来自 MirrorSession（MirrorReceiver 推送）。
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "MirrorActivity";
    private static volatile MirrorActivity instance;

    private SurfaceView surfaceView;
    private TextView statusView;
    private volatile boolean running;
    private Thread decodeThread;
    private MediaCodec codec;

    public static void finishInstance() {
        MirrorActivity a = instance;
        if (a != null) a.finish();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(16);
        statusView.setText("镜像连接中…");
        statusView.setPadding(48, 48, 48, 48);
        root.addView(statusView);
        setContentView(root);

        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startDecoding(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopDecoding();
    }

    private void startDecoding(SurfaceHolder holder) {
        stopDecoding();
        running = true;
        decodeThread = new Thread(() -> decodeLoop(holder), "mirror-decode");
        decodeThread.start();
    }

    private void decodeLoop(SurfaceHolder holder) {
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            // 低延迟模式（支持的设备上减少排队延迟）
            try {
                fmt.setInteger("low-latency", 1);
            } catch (Exception ignored) {
            }
            codec.configure(fmt, holder.getSurface(), null, 0);
            codec.start();

            boolean sizeApplied = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                MirrorSession.Frame frame = MirrorSession.get().pollFrame(500);
                if (frame == null) {
                    if (!MirrorSession.get().isStreaming()) {
                        // 流已停，退出页面
                        runOnUiThread(this::finish);
                        break;
                    }
                    continue;
                }
                if (!sizeApplied && MirrorSession.get().getWidth() > 0) {
                    sizeApplied = true;
                    runOnUiThread(() -> {
                        applyAspect(MirrorSession.get().getWidth(), MirrorSession.get().getHeight());
                        statusView.setVisibility(View.GONE);
                    });
                }
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(idx);
                    if (buf == null) continue;
                    buf.clear();
                    if (buf.remaining() < frame.data.length) {
                        Log.w(TAG, "input buffer too small: " + buf.remaining() + " < " + frame.data.length);
                        continue;
                    }
                    buf.put(frame.data);
                    codec.queueInputBuffer(idx, 0, frame.data.length,
                            System.nanoTime() / 1000, 0);
                }
                int outIdx = codec.dequeueOutputBuffer(info, 0);
                while (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true);
                    outIdx = codec.dequeueOutputBuffer(info, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "decode loop failed", e);
            runOnUiThread(() -> statusView.setText("镜像解码失败: " + e.getMessage()));
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    /** 按视频宽高比把 SurfaceView 缩放适配屏幕（居中等比，黑边） */
    private void applyAspect(int vw, int vh) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float scale = Math.min((float) screenW / vw, (float) screenH / vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        lp.width = Math.round(vw * scale);
        lp.height = Math.round(vh * scale);
        lp.gravity = android.view.Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
    }

    private void stopDecoding() {
        running = false;
        if (decodeThread != null) {
            try {
                decodeThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            decodeThread = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopDecoding();
        MirrorSession.get().reset();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
