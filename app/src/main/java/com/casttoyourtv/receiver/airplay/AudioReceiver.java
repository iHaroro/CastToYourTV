package com.casttoyourtv.receiver.airplay;

import android.util.Log;
import android.os.Process;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay 音频数据通道（对齐 UxPlay raop_rtp.c + serezhka/exgota 参考实现）：
 *  - iPhone 通过 UDP 发 RTP 音频包（type 0x60），控制通道收重传请求
 *  - 每包 AES-128-CBC 解密（IV 固定，尾部不足 16 字节明文传输）
 *  - 按序列号去重后交给 AudioRenderer 解码播放
 */
public class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    private final byte[] sessionKey;
    private final byte[] iv;
    private final AudioRenderer renderer;

    private DatagramSocket dataSocket;
    private DatagramSocket controlSocket;
    private Thread dataThread;
    private Thread controlThread;
    private volatile boolean running;

    private volatile int lastSequence = -1;
    private long packetsIn;
    private long packetsDropped;

    public AudioReceiver(byte[] sessionKey, byte[] iv, AudioRenderer renderer) {
        this.sessionKey = sessionKey;
        this.iv = iv;
        this.renderer = renderer;
    }

    public void start() throws Exception {
        dataSocket = new DatagramSocket(0);
        dataSocket.setSoTimeout(1000);
        controlSocket = new DatagramSocket(0);
        controlSocket.setSoTimeout(1000);
        running = true;
        dataThread = new Thread(() -> loop(dataSocket, false), "raop-audio-data");
        controlThread = new Thread(() -> loop(controlSocket, true), "raop-audio-ctrl");
        dataThread.setDaemon(true);
        controlThread.setDaemon(true);
        dataThread.start();
        controlThread.start();
        Log.i(TAG, "audio data port=" + dataSocket.getLocalPort()
                + " control port=" + controlSocket.getLocalPort());
    }

    public int getDataPort() {
        return dataSocket != null ? dataSocket.getLocalPort() : 0;
    }

    public int getControlPort() {
        return controlSocket != null ? controlSocket.getLocalPort() : 0;
    }

    private void loop(DatagramSocket socket, boolean control) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (Exception e) {
            Log.e(TAG, "cipher init failed", e);
            return;
        }
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (running) Log.w(TAG, "receive error: " + e.getMessage());
                continue;
            }
            int len = packet.getLength();
            if (len < 12) continue;
            int type = buf[1] & 0x7F;
            if (control) {
                // 0x56 = 重传的音频包：剥掉 4 字节头按数据包处理
                if (type == 0x56 && len >= 16) {
                    handleAudio(cipher, buf, 4, len - 4);
                }
                continue;
            }
            if (type != 0x60) continue;
            handleAudio(cipher, buf, 0, len);
        }
    }

    private synchronized void handleAudio(Cipher cipher, byte[] packet, int offset, int length) {
        int sequence = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
        // RTP 环形序号：只接受比上一包新的序号，重复与迟到的重传包都丢弃，
        // 否则乱序包会插进播放序列造成爆音
        if (lastSequence != -1) {
            int diff = (sequence - lastSequence) & 0xFFFF;
            if (diff == 0 || diff > 0x7FFF) return;
        }
        lastSequence = sequence;

        byte[] payload = new byte[length - 12];
        System.arraycopy(packet, offset + 12, payload, 0, payload.length);

        // AAC-ELD 开头的 4 字节空标记帧
        if (payload.length == 4 && payload[0] == 0x00 && payload[1] == 0x68
                && payload[2] == 0x34 && payload[3] == 0x00) {
            return;
        }

        int encLen = payload.length / 16 * 16;
        if (encLen > 0) {
            try {
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(sessionKey, "AES"), new IvParameterSpec(iv));
                byte[] plain = cipher.doFinal(payload, 0, encLen);
                System.arraycopy(plain, 0, payload, 0, encLen);
            } catch (Exception e) {
                packetsDropped++;
                if (packetsDropped <= 3 || packetsDropped % 100 == 0) {
                    Log.w(TAG, "audio decrypt failed #" + packetsDropped + ": " + e.getMessage());
                }
                return;
            }
        }
        packetsIn++;
        if (packetsIn == 1 || packetsIn % 500 == 0) {
            Log.i(TAG, "audio packets=" + packetsIn + " payload=" + payload.length
                    + " head=" + Integer.toHexString(payload[0] & 0xFF));
        }
        renderer.onFrame(payload);
    }

    public void stop() {
        running = false;
        try { if (dataSocket != null) dataSocket.close(); } catch (Exception ignored) { }
        try { if (controlSocket != null) controlSocket.close(); } catch (Exception ignored) { }
        Log.i(TAG, "audio stopped: in=" + packetsIn + " dropped=" + packetsDropped);
    }
}
