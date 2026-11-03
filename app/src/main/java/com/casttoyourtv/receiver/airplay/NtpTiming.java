package com.casttoyourtv.receiver.airplay;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * AirPlay NTP 对时（对齐 UxPlay raop_ntp.c 的最小实现）：
 *  - 绑定本地 UDP 端口（SETUP1 应答里告诉客户端）
 *  - 每 2 秒向客户端 timingPort 发 32 字节 0x80 0xd2 请求（带本机 NTP 时间戳）
 *  - 收到客户端的 0xd2 请求时回 0x80 0xd3（填上收发时间戳）
 * 时钟偏移计算省略：镜像渲染按到达即播，不做音视频对齐。
 */
public class NtpTiming {

    private static final String TAG = "NtpTiming";
    private static final long NTP_EPOCH_OFFSET = 2208988800L; // 1900->1970 秒数

    private final String clientIp;
    private final int clientPort;
    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    public NtpTiming(String clientIp, int clientPort) {
        this.clientIp = clientIp;
        this.clientPort = clientPort;
    }

    public int start() throws Exception {
        socket = new DatagramSocket(0);
        socket.setSoTimeout(500);
        running = true;
        thread = new Thread(this::loop, "ntp-timing");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "timing port " + socket.getLocalPort() + " -> client " + clientIp + ":" + clientPort);
        return socket.getLocalPort();
    }

    private void loop() {
        long nextSend = 0;
        byte[] buf = new byte[64];
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextSend && clientPort > 0) {
                    nextSend = now + 2000;
                    byte[] req = new byte[32];
                    req[0] = (byte) 0x80;
                    req[1] = (byte) 0xd2;
                    req[2] = 0x00;
                    req[3] = 0x07;
                    putNtpTimestamp(req, 24, now);
                    socket.send(new DatagramPacket(req, 32,
                            InetAddress.getByName(clientIp), clientPort));
                }
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(pkt);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (pkt.getLength() >= 32 && buf[1] == (byte) 0xd2) {
                    // 客户端的定时请求：回 0xd3，[8..15]=原样返回客户端时间戳，[16..31]=本机收发时间
                    long t = System.currentTimeMillis();
                    byte[] resp = new byte[32];
                    resp[0] = (byte) 0x80;
                    resp[1] = (byte) 0xd3;
                    resp[2] = buf[2];
                    resp[3] = buf[3];
                    System.arraycopy(buf, 24, resp, 8, 8);
                    putNtpTimestamp(resp, 16, t);
                    putNtpTimestamp(resp, 24, System.currentTimeMillis());
                    socket.send(new DatagramPacket(resp, 32, pkt.getAddress(), pkt.getPort()));
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "timing loop: " + e.getMessage());
            }
        }
    }

    /** NTP 时间戳：BE 秒（1900 起）+ BE 小数 */
    private static void putNtpTimestamp(byte[] b, int off, long epochMillis) {
        long secs = epochMillis / 1000 + NTP_EPOCH_OFFSET;
        long frac = ((epochMillis % 1000) << 32) / 1000;
        b[off] = (byte) (secs >> 24);
        b[off + 1] = (byte) (secs >> 16);
        b[off + 2] = (byte) (secs >> 8);
        b[off + 3] = (byte) secs;
        b[off + 4] = (byte) (frac >> 24);
        b[off + 5] = (byte) (frac >> 16);
        b[off + 6] = (byte) (frac >> 8);
        b[off + 7] = (byte) frac;
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }
}
