package com.casttoyourtv.receiver.airplay;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * AirPlay 音频解码播放：ALAC（无损，音乐投放默认）用内置解码器，
 * AAC-LC/AAC-ELD 走 MediaCodec，PCM 直接换字节序。解码后经 AudioTrack 出声。
 */
public class AudioRenderer {

    private static final String TAG = "AudioRenderer";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final int queueLimit;
    private Thread thread;
    private volatile boolean running;
    private volatile float volume = 1.0f;

    private final int compressionType; // 1=PCM 2=ALAC 4=AAC-LC 8=AAC-ELD
    private final int samplesPerFrame;

    private AudioTrack track;
    private MediaCodec codec;
    private AlacDecoder alac;
    private short[] alacOut;
    private float appliedVolume = -1;
    private long framesPlayed;
    private long framesDropped;

    public AudioRenderer(int compressionType, int samplesPerFrame, boolean lowLatency) {
        this.compressionType = compressionType;
        this.samplesPerFrame = samplesPerFrame;
        int frameMs = Math.max(1, (samplesPerFrame > 0 ? samplesPerFrame : 352) * 1000 / SAMPLE_RATE);
        // 音乐容忍深缓冲，镜像伴音要低延迟
        this.queueLimit = lowLatency ? Math.max(4, 150 / frameMs) : Math.max(64, 4000 / frameMs);
    }

    public synchronized void start() {
        running = true;
        queue.clear();
        thread = new Thread(this::loop, "airplay-audio-play");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "audio renderer start ct=" + compressionType + " spf=" + samplesPerFrame);
    }

    public void onFrame(byte[] frame) {
        if (!running) return;
        while (queue.size() >= queueLimit) {
            if (queue.poll() != null) framesDropped++;
        }
        queue.offer(frame);
    }

    /** AirPlay 音量：0dB 满格，-30dB 最低档，-144dB 静音 */
    public void setVolumeDb(float db) {
        volume = db <= -144f ? 0f : (float) Math.pow(10.0, Math.max(db, -30f) / 20.0);
    }

    private void loop() {
        try {
            openOutput();
            openDecoder();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[16384];
            while (running) {
                byte[] frame = queue.poll();
                if (frame == null) {
                    drainCodec(info, pcm, 0);
                    continue;
                }
                try {
                    applyVolume();
                    decode(frame, info, pcm);
                    drainCodec(info, pcm, 0);
                } catch (Exception e) {
                    framesDropped++;
                    if (framesDropped <= 5 || framesDropped % 100 == 0) {
                        Log.w(TAG, "decode error #" + framesDropped + ": " + e.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "playback thread failed", t);
        } finally {
            release();
            Log.i(TAG, "audio played=" + framesPlayed + " dropped=" + framesDropped);
        }
    }

    private void openOutput() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 3 / 2, 8192);
        size -= size % 4;
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                size, AudioTrack.MODE_STREAM);
        track.play();
        Log.i(TAG, "AudioTrack buffer " + size + " bytes (min " + min + ")");
    }

    private void openDecoder() throws Exception {
        switch (compressionType) {
            case 2: // ALAC
                alac = AlacDecoder.forAirPlay(samplesPerFrame);
                alacOut = new short[alac.maximumOutputSamples()];
                break;
            case 8: // AAC-ELD
                openAac(new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00},
                        MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                break;
            case 4: // AAC-LC
                openAac(new byte[]{0x12, 0x10}, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                break;
            case 1: // PCM
                break;
            default:
                Log.w(TAG, "unsupported compression type " + compressionType);
        }
    }

    private void openAac(byte[] asc, int profile) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile);
        format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        try {
            codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
        } catch (Exception e) {
            codec = MediaCodec.createByCodecName("OMX.google.aac.decoder");
        }
        codec.configure(format, null, null, 0);
        codec.start();
        Log.i(TAG, "AAC decoder: " + codec.getName());
    }

    private void decode(byte[] frame, MediaCodec.BufferInfo info, byte[] pcm) throws Exception {
        if (codec != null) {
            int idx = codec.dequeueInputBuffer(20000);
            if (idx < 0) { framesDropped++; return; }
            ByteBuffer in = codec.getInputBuffer(idx);
            in.clear();
            in.put(frame);
            codec.queueInputBuffer(idx, 0, frame.length, 0, 0);
            drainCodec(info, pcm, 5000);
        } else if (alac != null) {
            int frames = alac.decode(frame, 0, frame.length, alacOut);
            writeShorts(alacOut, frames * CHANNELS);
        } else if (compressionType == 1) {
            // AirPlay PCM 是大端，AudioTrack 要小端
            for (int i = 0; i + 1 < frame.length; i += 2) {
                byte t = frame[i];
                frame[i] = frame[i + 1];
                frame[i + 1] = t;
            }
            writeBytes(frame, frame.length - (frame.length & 1));
        }
    }

    private void drainCodec(MediaCodec.BufferInfo info, byte[] pcm, long timeoutUs) {
        while (codec != null) {
            int idx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (idx >= 0) {
                if (info.size > 0) {
                    ByteBuffer out = codec.getOutputBuffer(idx);
                    if (pcm.length < info.size) pcm = new byte[info.size];
                    out.position(info.offset);
                    out.get(pcm, 0, info.size);
                    codec.releaseOutputBuffer(idx, false);
                    writeBytes(pcm, info.size);
                } else {
                    codec.releaseOutputBuffer(idx, false);
                }
                timeoutUs = 0;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "codec output format: " + codec.getOutputFormat());
            } else {
                return;
            }
        }
    }

    private void writeBytes(byte[] data, int len) {
        int written = 0;
        while (written < len && running) {
            int r = track.write(data, written, len - written);
            if (r <= 0) break;
            written += r;
        }
        framesPlayed++;
    }

    private void writeShorts(short[] data, int len) {
        int written = 0;
        while (written < len && running) {
            int r = track.write(data, written, len - written);
            if (r <= 0) break;
            written += r;
        }
        framesPlayed++;
    }

    private void applyVolume() {
        float v = volume;
        if (v != appliedVolume && track != null) {
            track.setVolume(v);
            appliedVolume = v;
        }
    }

    private void release() {
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            codec = null;
        }
        alac = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException ignored) { }
            thread = null;
        }
        queue.clear();
    }
}
