package com.casttoyourtv.receiver.airplay;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 镜像会话单例：MirrorReceiver（服务线程）推帧，MirrorActivity（UI 线程）取帧解码。
 */
public final class MirrorSession {

    private static final MirrorSession INSTANCE = new MirrorSession();

    public static MirrorSession get() {
        return INSTANCE;
    }

    /** 一帧 Annex-B H.264 数据 */
    public static final class Frame {
        public final byte[] data;

        Frame(byte[] data) {
            this.data = data;
        }
    }

    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger width = new AtomicInteger();
    private final AtomicInteger height = new AtomicInteger();
    private volatile boolean streaming;
    private volatile long lastFrameAt;

    private MirrorSession() {
    }

    public void onConfig(int w, int h) {
        width.set(w);
        height.set(h);
    }

    public void onFrame(byte[] annexb) {
        streaming = true;
        lastFrameAt = System.currentTimeMillis();
        // 队列堆积超过 30 帧（约 0.5s）就丢旧帧，镜像优先保实时性
        while (queue.size() > 30) queue.poll();
        queue.offer(new Frame(annexb));
    }

    public void onStreamStopped() {
        streaming = false;
        queue.clear();
    }

    public Frame pollFrame(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int getWidth() {
        return width.get();
    }

    public int getHeight() {
        return height.get();
    }

    public boolean isStreaming() {
        return streaming && System.currentTimeMillis() - lastFrameAt < 3000;
    }

    public void reset() {
        queue.clear();
        streaming = false;
    }
}
