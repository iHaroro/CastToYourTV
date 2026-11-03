package com.casttoyourtv.receiver.airplay;

import android.util.Log;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay 镜像数据通道（对齐 UxPlay raop_rtp_mirror.c + mirror_buffer.c）：
 *  - iPhone 通过 TCP 连接本端口，连续发 128 字节头 + payload 的包
 *  - 包类型 packet[4]：0x00 加密视频帧 / 0x01 明文 SPS+PPS / 0x02 保活 / 0x05 报告
 *  - 视频帧用 AES-128-CTR 解密（密钥流跨包连续，UxPlay 的 nextDecryptCount 语义）
 *  - 解密后把 [4字节大端长度][NAL] 重组为 Annex-B（00 00 00 01 起始码），交给 MirrorSession
 */
public class MirrorReceiver {

    private static final String TAG = "MirrorReceiver";
    private static final byte[] NAL_START = {0, 0, 0, 1};

    private volatile boolean running;
    private ServerSocket server;
    private Thread thread;
    private int port = -1;

    private byte[] videoKey;
    private byte[] videoIv;

    /**
     * @param aeskey SETUP1 解出的 16 字节 AES 密钥
     * @param scid   streamConnectionID（SETUP2 streams 里给出）
     */
    public MirrorReceiver(byte[] aeskey, long scid) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] keyMaterial = ("AirPlayStreamKey" + scid).getBytes("US-ASCII");
        sha.update(keyMaterial);
        sha.update(aeskey);
        videoKey = Arrays.copyOf(sha.digest(), 16);
        sha.reset();
        byte[] ivMaterial = ("AirPlayStreamIV" + scid).getBytes("US-ASCII");
        sha.update(ivMaterial);
        sha.update(aeskey);
        videoIv = Arrays.copyOf(sha.digest(), 16);
    }

    public void start() throws Exception {
        server = new ServerSocket(0); // 系统分配空闲端口
        port = server.getLocalPort();
        running = true;
        thread = new Thread(this::loop, "mirror-recv");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "mirror data listening on port " + port);
    }

    public int getPort() {
        return port;
    }

    private void loop() {
        try {
            while (running) {
                Socket client = server.accept();
                Log.i(TAG, "mirror client connected: " + client.getInetAddress().getHostAddress());
                try {
                    handleClient(client);
                } catch (Exception e) {
                    Log.w(TAG, "mirror client ended: " + e.getMessage());
                }
                try {
                    client.close();
                } catch (Exception ignored) {
                }
                MirrorSession.get().onStreamStopped();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "accept error", e);
        }
    }

    private void handleClient(Socket client) throws Exception {
        InputStream in = client.getInputStream();
        AesCtr ctr = new AesCtr(videoKey, videoIv);
        byte[] header = new byte[128];
        byte[] spsPps = null; // 待前置的 SPS/PPS（Annex-B）

        while (running) {
            if (!readFully(in, header, 0, 128)) break;
            int payloadSize = leInt(header, 0);
            int type = header[4] & 0xFF;
            int option = header[6] & 0xFF;
            byte[] payload = new byte[payloadSize];
            if (!readFully(in, payload, 0, payloadSize)) break;

            if (type == 0x00) {
                // 加密视频帧：解密 + NAL 重组
                byte[] dec = ctr.crypt(payload);
                byte[] annexb = toAnnexB(dec);
                if (annexb == null) {
                    Log.w(TAG, "invalid NAL payload size=" + payloadSize + "，解密可能失败");
                    continue;
                }
                if (spsPps != null) {
                    byte[] merged = new byte[spsPps.length + annexb.length];
                    System.arraycopy(spsPps, 0, merged, 0, spsPps.length);
                    System.arraycopy(annexb, 0, merged, spsPps.length, annexb.length);
                    annexb = merged;
                    spsPps = null;
                }
                MirrorSession.get().onFrame(annexb);
            } else if (type == 0x01) {
                // 明文 SPS+PPS 包：宽高在头的 16/20（源）与 56/60（缩放后），LE float
                int w = (int) leFloat(header, 56);
                int h = (int) leFloat(header, 60);
                if (w <= 0 || h <= 0) {
                    w = (int) leFloat(header, 16);
                    h = (int) leFloat(header, 20);
                }
                if (w > 0 && h > 0) MirrorSession.get().onConfig(w, h);
                if (option == 0x56 || option == 0x5e) {
                    Log.i(TAG, "stream suspend packet");
                    continue;
                }
                spsPps = parseSpsPps(payload);
                Log.i(TAG, "SPS/PPS packet: " + w + "x" + h + " spsPpsLen="
                        + (spsPps == null ? 0 : spsPps.length));
            }
            // 0x02 保活 / 0x05 报告：忽略
        }
    }

    /** payload: 4字节0 + 2字节BE SPS长度 + SPS + 1字节 + 2字节BE PPS长度 + PPS（UxPlay 布局） */
    private byte[] parseSpsPps(byte[] payload) {
        try {
            if (payload.length < 11) return null;
            int spsLen = beShort(payload, 6);
            int ppsLenOff = spsLen + 9;
            if (ppsLenOff + 2 > payload.length) return null;
            int ppsLen = beShort(payload, ppsLenOff);
            int ppsOff = spsLen + 11;
            if (ppsOff + ppsLen > payload.length) return null;
            byte[] out = new byte[8 + spsLen + ppsLen];
            System.arraycopy(NAL_START, 0, out, 0, 4);
            System.arraycopy(payload, 8, out, 4, spsLen);
            System.arraycopy(NAL_START, 0, out, 4 + spsLen, 4);
            System.arraycopy(payload, ppsOff, out, 8 + spsLen, ppsLen);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "parseSpsPps failed", e);
            return null;
        }
    }

    /** [4B BE 长度][NAL]... → 起始码替换；校验失败返回 null */
    private byte[] toAnnexB(byte[] dec) {
        int pos = 0;
        // 先校验整条链合法（长度可读、NAL forbidden_zero_bit=0）
        while (pos < dec.length) {
            if (pos + 4 > dec.length) return null;
            int len = beInt(dec, pos);
            if (len < 0 || pos + 4 + len > dec.length) return null;
            if (len > 0 && (dec[pos + 4] & 0x80) != 0) return null;
            pos += 4 + len;
        }
        if (pos != dec.length) return null;
        byte[] out = dec.clone();
        pos = 0;
        while (pos < out.length) {
            int len = beInt(out, pos);
            System.arraycopy(NAL_START, 0, out, pos, 4);
            pos += 4 + len;
        }
        return out;
    }

    // ===== AES-128-CTR：计数器大端自增，密钥流跨包连续（等价 UxPlay nextDecryptCount 逻辑）=====
    private static class AesCtr {
        private final Cipher aes;
        private final byte[] counter;
        private byte[] ks = new byte[16];
        private int ksPos = 16;

        AesCtr(byte[] key, byte[] iv) throws Exception {
            aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            counter = iv.clone();
        }

        byte[] crypt(byte[] in) throws Exception {
            byte[] out = new byte[in.length];
            for (int i = 0; i < in.length; i++) {
                if (ksPos == 16) {
                    ks = aes.doFinal(counter);
                    incrCounter();
                    ksPos = 0;
                }
                out[i] = (byte) (in[i] ^ ks[ksPos++]);
            }
            return out;
        }

        private void incrCounter() {
            for (int i = 15; i >= 0; i--) {
                if (++counter[i] != 0) break;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len) throws Exception {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, off + n, len - n);
            if (r < 0) return false;
            n += r;
        }
        return true;
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int beInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16
                | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    private static int beShort(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    private static float leFloat(byte[] b, int off) {
        return Float.intBitsToFloat(leInt(b, off));
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }
}
