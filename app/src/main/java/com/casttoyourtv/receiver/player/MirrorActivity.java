package com.casttoyourtv.receiver.player;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.casttoyourtv.receiver.CastService;
import com.casttoyourtv.receiver.airplay.MirrorSession;

import java.nio.ByteBuffer;

/**
 * AirPlay 屏幕镜像渲染页：SurfaceView + MediaCodec 硬解 H.264（Annex-B 直接喂）。
 * 数据来自 MirrorSession（MirrorReceiver 推送）。
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "MirrorActivity";
    private static volatile MirrorActivity instance;
    /** 1080p 关键帧可能到几 MB，不设上限会被 MediaCodec 默认缓冲区丢弃。 */
    private static final int MAX_INPUT_SIZE = 4 * 1024 * 1024;
    /** 已在解码但没有输出帧超过该时长，重建解码器。 */
    private static final long DECODE_STALL_MS = 4000;

    private SurfaceView surfaceView;
    private TextView statusView;
    private volatile boolean running;
    /** 画面 Surface 是否存在；Home 后为 false，解码线程继续跑但不渲染 */
    private volatile boolean rendering;
    /** 回台后待切换的新 Surface，由解码线程消费 */
    private volatile Surface pendingSurface;
    /** 解码器当前绑定的 Surface */
    private volatile Surface renderSurface;
    private Thread decodeThread;
    private MediaCodec codec;
    private android.app.Dialog controlPanel;

    public static void finishInstance() {
        MirrorActivity a = instance;
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
        Surface surface = holder.getSurface();
        rendering = true;
        if (decodeThread == null || !decodeThread.isAlive()) {
            renderSurface = surface;
            pendingSurface = null;
            running = true;
            decodeThread = new Thread(() -> decodeLoop(), "mirror-decode");
            decodeThread.start();
        } else {
            pendingSurface = surface;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 关键：只停靠渲染，不释放解码器。iPhone 镜像流只在开头发 IDR，
        // 解码器一旦重建就会因缺少参考帧而黑屏，必须保持全程存活
        rendering = false;
        Log.i(TAG, "surface destroyed, decoder parked");
    }

    private void decodeLoop() {
        int restarts = 0;
        while (running) {
            boolean restart = decodeOnce();
            if (!running) break;
            if (restart) {
                restarts++;
                Log.w(TAG, "decoder stalled, restart #" + restarts);
                // 重建后必须从 IDR 开始，否则一路解 P 帧还是黑屏
                MirrorSession.get().dropUntilKeyframe();
            }
        }
    }

    /** @return true 表示解码器卡住，需要重建 */
    private boolean decodeOnce() {
        MediaCodec decoder = null;
        try {
            Surface incoming = pendingSurface;
            if (incoming != null) {
                pendingSurface = null;
                renderSurface = incoming;
            }
            Surface surface = renderSurface;
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "decodeOnce without valid surface, waiting");
                SystemClock.sleep(200);
                return false;
            }
            decoder = MediaCodec.createDecoderByType("video/avc");
            codec = decoder;
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);
            // 低延迟模式（支持的设备上减少排队延迟）
            try {
                fmt.setInteger("low-latency", 1);
            } catch (Exception ignored) {
            }
            decoder.configure(fmt, surface, null, 0);
            decoder.start();
            Log.i(TAG, "decoder started: " + decoder.getName()
                    + " surfaceValid=" + surface.isValid());

            int appliedW = -1;
            int appliedH = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int queued = 0;
            int decoded = 0;
            int rendered = 0;
            int inputMiss = 0;
            long startedAt = SystemClock.elapsedRealtime();
            long lastOutputAt = startedAt;
            while (running) {
                Surface next = pendingSurface;
                if (next != null) {
                    pendingSurface = null;
                    renderSurface = next;
                    try {
                        decoder.setOutputSurface(next);
                        Log.i(TAG, "output surface switched on resume");
                    } catch (Exception e) {
                        Log.w(TAG, "setOutputSurface failed, recreate decoder", e);
                        return true;
                    }
                }
                MirrorSession.Frame frame = MirrorSession.get().pollFrame(200);
                if (frame == null) {
                    if (!MirrorSession.get().isStreaming()) {
                        // 流已停，退出页面
                        CastService.notifyMirrorEnded();
                        runOnUiThread(this::finish);
                        break;
                    }
                    if (queued > 5 && SystemClock.elapsedRealtime() - lastOutputAt > DECODE_STALL_MS) {
                        return true;
                    }
                    continue;
                }
                int vw = MirrorSession.get().getWidth();
                int vh = MirrorSession.get().getHeight();
                if (vw > 0 && vh > 0 && (vw != appliedW || vh != appliedH)) {
                    appliedW = vw;
                    appliedH = vh;
                    runOnUiThread(() -> {
                        applyAspect(vw, vh);
                        statusView.setVisibility(View.GONE);
                    });
                }
                int idx = -1;
                for (int attempt = 0; attempt < 3 && idx < 0 && running; attempt++) {
                    idx = decoder.dequeueInputBuffer(10000);
                }
                if (idx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(idx);
                    if (buf != null && buf.capacity() >= frame.data.length) {
                        buf.clear();
                        buf.put(frame.data);
                        decoder.queueInputBuffer(idx, 0, frame.data.length,
                                System.nanoTime() / 1000, 0);
                        queued++;
                        if (queued == 1) {
                            Log.i(TAG, "first frame queued size=" + frame.data.length
                                    + " inputCap=" + buf.capacity());
                        }
                    } else {
                        Log.w(TAG, "input buffer too small: "
                                + (buf == null ? -1 : buf.capacity()) + " < " + frame.data.length);
                        decoder.queueInputBuffer(idx, 0, 0, 0, 0);
                    }
                } else if (inputMiss < 3) {
                    inputMiss++;
                    Log.w(TAG, "dequeueInputBuffer returned " + idx);
                }
                int outIdx = decoder.dequeueOutputBuffer(info, 0);
                while (outIdx >= 0) {
                    decoded++;
                    lastOutputAt = SystemClock.elapsedRealtime();
                    // pendingSurface 非空说明正处于回台切换窗口：
                    // 旧 Surface 已被 SurfaceView 废弃，此时渲染会让解码器进入错误态
                    boolean canRender = rendering && renderSurface != null
                            && pendingSurface == null && renderSurface.isValid();
                    decoder.releaseOutputBuffer(outIdx, canRender);
                    if (canRender) {
                        rendered++;
                    }
                    outIdx = decoder.dequeueOutputBuffer(info, 0);
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "output format changed: " + decoder.getOutputFormat());
                }
                if (rendered == 1) {
                    Log.i(TAG, "first frame rendered");
                } else if (decoded > 0 && decoded % 120 == 0) {
                    Log.i(TAG, "frames decoded=" + decoded
                            + " rendered=" + rendered + " queued=" + queued);
                }
                if (decoded == 0 && queued > 5
                        && SystemClock.elapsedRealtime() - startedAt > DECODE_STALL_MS) {
                    Log.w(TAG, "no output frame after " + queued + " frames, restarting decoder");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "decode loop failed", e);
            runOnUiThread(() -> statusView.setText("镜像解码失败: " + e.getMessage()));
        } finally {
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception ignored) {
            }
            codec = null;
        }
        return false;
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
        stopDecoding();
        // 不在这里清空 MirrorSession：旧页面销毁可能晚于新会话开始，会把新会话的帧清掉
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
