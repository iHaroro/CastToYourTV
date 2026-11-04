package com.casttoyourtv.receiver.airplay;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 镜像会话单例：MirrorReceiver（服务线程）推帧，MirrorActivity（UI 线程）取帧解码。
 *
 * 每次 SETUP2 都算一个新会话（带自增 id）。旧连接迟到的帧/停止回调不会清掉新会话，
 * 避免“刚重连就被上一次会话的收尾动作清空”导致的黑屏。
 */
public final class MirrorSession {

    private static final MirrorSession INSTANCE = new MirrorSession();
    /** 队列上限；超过后优先保留最近的 IDR，避免丢掉关键帧后整段变黑。 */
    private static final int MAX_QUEUE = 60;

    public static MirrorSession get() {
        return INSTANCE;
    }

    /** 一帧 Annex-B H.264 数据 */
    public static final class Frame {
        public final byte[] data;
        public final boolean keyframe;

        Frame(byte[] data, boolean keyframe) {
            this.data = data;
            this.keyframe = keyframe;
        }
    }

    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger width = new AtomicInteger();
    private final AtomicInteger height = new AtomicInteger();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private volatile int currentSession;
    private volatile boolean streaming;
    private volatile long lastFrameAt;

    private MirrorSession() {
    }

    /** 新会话开始，返回会话 id。 */
    public int beginSession() {
        int id = sessionSeq.incrementAndGet();
        currentSession = id;
        synchronized (queue) {
            queue.clear();
        }
        width.set(0);
        height.set(0);
        streaming = false;
        lastFrameAt = 0;
        return id;
    }

    public boolean isCurrent(int id) {
        return id == currentSession;
    }

    public void onConfig(int id, int w, int h) {
        if (!isCurrent(id)) return;
        width.set(w);
        height.set(h);
    }

    public void onFrame(int id, byte[] annexb) {
        if (!isCurrent(id)) return;
        boolean key = containsIdr(annexb);
        streaming = true;
        lastFrameAt = System.currentTimeMillis();
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE) {
                // 丢弃最近一个 IDR 之前的帧，保证解码始终从关键帧开始
                Frame lastKey = null;
                for (Frame f : queue) {
                    if (f.keyframe) lastKey = f;
                }
                while (!queue.isEmpty() && queue.peek() != lastKey) queue.poll();
                while (queue.size() >= MAX_QUEUE) queue.poll();
            }
            queue.offer(new Frame(annexb, key));
        }
    }

    public void onStreamStopped(int id) {
        if (!isCurrent(id)) return;
        streaming = false;
        synchronized (queue) {
            queue.clear();
        }
    }

    public Frame pollFrame(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 解码器重启时用：丢掉关键帧之前的残留帧，避免从 P 帧开始解出黑屏。 */
    public void dropUntilKeyframe() {
        synchronized (queue) {
            Frame f;
            while ((f = queue.peek()) != null && !f.keyframe) queue.poll();
        }
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
        synchronized (queue) {
            queue.clear();
        }
        streaming = false;
    }

    /** 用户主动结束投屏：作废当前会话并清空队列。 */
    public void endSession() {
        currentSession = sessionSeq.incrementAndGet();
        streaming = false;
        synchronized (queue) {
            queue.clear();
        }
    }

    /** Annex-B 里是否含 IDR（nal_unit_type=5） */
    private static boolean containsIdr(byte[] annexb) {
        int i = 0;
        while (i + 4 <= annexb.length) {
            if (annexb[i] == 0 && annexb[i + 1] == 0
                    && (annexb[i + 2] == 1 || (annexb[i + 2] == 0 && annexb[i + 3] == 1))) {
                int sc = annexb[i + 2] == 1 ? 3 : 4;
                int hdr = i + sc;
                if (hdr < annexb.length && (annexb[hdr] & 0x1F) == 5) return true;
                i = hdr + 1;
            } else {
                i++;
            }
        }
        return false;
    }
}
