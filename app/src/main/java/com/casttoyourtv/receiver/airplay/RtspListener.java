package com.casttoyourtv.receiver.airplay;

import android.content.Context;
import android.util.Log;

import com.casttoyourtv.receiver.CastSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPlay RTSP/HTTP 监听（7000 端口）。
 * iPhone 连上后会在同一条 TCP 连接上连续发多个请求（keep-alive），
 * 已按字节解析（body 是二进制 plist），目前实现：
 *  - GET/POST /info   -> 返回设备信息二进制 plist（iOS 连接的第一步）
 *  - 其他请求          -> 200 OK 占位（配对/镜像逐步补齐）
 */
public class RtspListener {

    private static final String TAG = "RtspListener";

    // FairPlay fp-setup 的 4 条固定应答（来自 UxPlay fairplay_playfair.c，公开逆向结果）
    private static final String[] FP_REPLIES = {
            "46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba",
            "46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a",
            "46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce",
            "46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359"
    };
    private static final byte[] FP_HEADER = AirPlayCrypto.hexToBytes("46504c590301040000000014");

    private final int port;
    private final CastSession.Callback callback;
    private final Context ctx;
    private final String deviceName;
    private volatile boolean running;
    private ServerSocket server;
    private Thread thread;
    private final Set<Socket> activeClients =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    public RtspListener(int port, CastSession.Callback callback, Context ctx, String deviceName) {
        this.port = port;
        this.callback = callback;
        this.ctx = ctx;
        this.deviceName = deviceName;
        AirPlayCrypto.init(ctx);
        PlayfairTables.init(ctx);
    }

    /** 每条 TCP 连接的配对会话状态 */
    private static class PairingState {
        boolean paired;
        AirPlayCrypto.X25519Session x25519;
        byte[] theirX25519;
        byte[] theirEd25519;
        byte[] sharedSecret;
        byte[] fpKeymsg;      // fp-setup 第二段的 164 字节 keymsg（Playfair 解密要用）
        byte[] aeskey;        // SETUP1 解出的 16 字节 AES 密钥
        byte[] eiv;           // SETUP1 的 16 字节音频 AES-CBC IV
        NtpTiming ntp;
        MirrorReceiver mirror;
        AudioReceiver audio;
        AudioRenderer audioRenderer;
        int audioCt = -1;      // 当前音频流参数，用于识别重复 SETUP
        int audioSpf = -1;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "raop-rtsp");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        try {
            server = new ServerSocket(port);
            while (running) {
                Socket client = server.accept();
                activeClients.add(client);
                new Thread(() -> handle(client), "raop-client").start();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "accept error", e);
        }
    }

    private void handle(Socket client) {
        PairingState pairing = new PairingState();
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            // AirPlay 复用同一条连接发多个请求，循环处理直到对端断开
            while (true) {
                String requestLine = readAsciiLine(in);
                if (requestLine == null) break;
                if (requestLine.isEmpty()) continue;

                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                                line.substring(idx + 1).trim());
                    }
                }

                int contentLen = 0;
                if (headers.containsKey("content-length")) {
                    contentLen = Integer.parseInt(headers.get("content-length"));
                }
                byte[] body = new byte[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = in.read(body, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";
                Log.i(TAG, "req: " + method + " " + path + " bodyLen=" + contentLen
                        + " from " + c.getInetAddress().getHostAddress());

                byte[] respBody = new byte[0];
                String contentType = null;
                String extraHeaders = null;
                if (path.startsWith("/info")) {
                    respBody = handleInfo(body);
                    contentType = "application/x-apple-binary-plist";
                } else if (path.startsWith("/pair-setup")) {
                    // 免 PIN 配对：回我们的 Ed25519 长期公钥
                    respBody = AirPlayCrypto.edPublicKey();
                    contentType = "application/octet-stream";
                    Log.i(TAG, "pair-setup done");
                } else if (path.startsWith("/pair-verify")) {
                    respBody = handlePairVerify(body, pairing);
                    contentType = "application/octet-stream";
                } else if (path.startsWith("/fp-setup")) {
                    respBody = handleFpSetup(body, pairing);
                    contentType = "application/octet-stream";
                } else if ("SETUP".equals(method)) {
                    respBody = handleSetup(body, pairing, c.getInetAddress().getHostAddress());
                    contentType = "application/x-apple-binary-plist";
                } else if ("RECORD".equals(method)) {
                    // 只补响应头：iPhone 音乐投放是「先 RECORD 后 SETUP 音频流」，
                    // 这里还分不清音频/镜像，界面拉起挪到 SETUP 成功时
                    extraHeaders = "Audio-Latency: 11025\r\nAudio-Jack-Status: connected; type=analog\r\n";
                    Log.i(TAG, "RECORD ok");
                } else if ("TEARDOWN".equals(method)) {
                    AudioSession.get().onStop();
                    callback.onMirrorStop();
                    callback.onAudioStop();
                    Log.i(TAG, "TEARDOWN: mirror session stopped");
                } else if ("SET_PARAMETER".equals(method)) {
                    handleSetParameter(headers, body, pairing);
                }

                StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\n");
                if (headers.containsKey("cseq")) {
                    sb.append("CSeq: ").append(headers.get("cseq")).append("\r\n");
                }
                sb.append("Server: AirTunes/").append(AirPlayProfile.SRCVERS).append("\r\n");
                if (extraHeaders != null) sb.append(extraHeaders);
                if (contentType != null) {
                    sb.append("Content-Type: ").append(contentType).append("\r\n");
                }
                sb.append("Content-Length: ").append(respBody.length).append("\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(respBody);
                out.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "handle error", e);
        }
        // 连接断开：释放本连接的对时/镜像资源
        if (pairing.ntp != null) pairing.ntp.stop();
        if (pairing.mirror != null) pairing.mirror.stop();
        if (pairing.audio != null) pairing.audio.stop();
        if (pairing.audioRenderer != null) pairing.audioRenderer.stop();
        AudioSession.get().onStop();
        callback.onMirrorStop();
        callback.onAudioStop();
        activeClients.remove(client);
    }

    /**
     * SETUP 两段（对齐 UxPlay raop_handlers.h）：
     *  SETUP1（带 ekey/eiv）：Playfair 解 ekey 得 AES 密钥，再与配对共享密钥 SHA-512 混合；
     *      起 NTP 对时端口，应答 {timingPort, eventPort}
     *  SETUP2（streams type=110 镜像）：按 streamConnectionID 派生视频密钥/IV，
     *      起镜像数据 TCP 端口，应答 {streams:[{type, dataPort}]}
     */
    private byte[] handleSetup(byte[] body, PairingState p, String clientIp) {
        Map<String, Object> root;
        try {
            root = BplistReader.readDict(body);
        } catch (Exception e) {
            Log.e(TAG, "SETUP bplist parse failed", e);
            return new byte[0];
        }
        Bplist.Dict resp = new Bplist.Dict();

        Object ekeyObj = root.get("ekey");
        if (ekeyObj instanceof byte[] && ((byte[]) ekeyObj).length == 72) {
            try {
                if (p.fpKeymsg == null) {
                    Log.e(TAG, "SETUP1 without fp-setup keymsg");
                    return new byte[0];
                }
                byte[] fpKey = Playfair.decrypt(p.fpKeymsg, (byte[]) ekeyObj);
                if (p.sharedSecret != null) {
                    // 有配对共享密钥：aeskey = SHA512(fpKey || sharedSecret)[0:16]
                    byte[] material = new byte[48];
                    System.arraycopy(fpKey, 0, material, 0, 16);
                    System.arraycopy(p.sharedSecret, 0, material, 16, 32);
                    byte[] hash = java.security.MessageDigest.getInstance("SHA-512").digest(material);
                    p.aeskey = java.util.Arrays.copyOf(hash, 16);
                } else {
                    // 未建立配对（客户端没走 legacy pairing）：直接用 Playfair 输出的密钥
                    p.aeskey = fpKey;
                }
                Log.i(TAG, "SETUP1: aeskey derived");

                // iPhone 在 SETUP1 带 name/model，展示到播放页
                Object nameObj = root.get("name");
                Object modelObj = root.get("model");
                if (nameObj instanceof String || modelObj instanceof String) {
                    AudioSession.get().setClient(strVal(nameObj), strVal(modelObj), clientIp);
                }

                Object eivObj = root.get("eiv");
                if (eivObj instanceof byte[]) {
                    p.eiv = (byte[]) eivObj;
                    Log.i(TAG, "SETUP1: eiv len=" + p.eiv.length);
                }

                Object tp = root.get("timingPort");
                int clientTimingPort = tp instanceof Number ? ((Number) tp).intValue() : 0;
                if (p.ntp != null) p.ntp.stop();
                p.ntp = new NtpTiming(clientIp, clientTimingPort);
                int timingPort = p.ntp.start();
                resp.put("timingPort", Bplist.i64(timingPort));
                resp.put("eventPort", Bplist.i64(0));
            } catch (Exception e) {
                Log.e(TAG, "SETUP1 failed", e);
                return new byte[0];
            }
        }

        Object streamsObj = root.get("streams");
        if (streamsObj instanceof java.util.List) {
            Bplist.Arr respStreams = new Bplist.Arr();
            for (Object item : (java.util.List<?>) streamsObj) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> stream = (Map<?, ?>) item;
                Object typeObj = stream.get("type");
                long type = typeObj instanceof Number ? ((Number) typeObj).longValue() : -1;
                if (type == 110) {
                    try {
                        Object scidObj = stream.get("streamConnectionID");
                        long scid = scidObj instanceof Number ? ((Number) scidObj).longValue() : 0;
                        if (p.aeskey == null) {
                            Log.e(TAG, "SETUP2 without aeskey");
                            continue;
                        }
                        if (p.mirror != null) p.mirror.stop();
                        p.mirror = new MirrorReceiver(p.aeskey, scid);
                        p.mirror.start();
                        Bplist.Dict s = new Bplist.Dict()
                                .put("type", Bplist.i64(110))
                                .put("dataPort", Bplist.i64(p.mirror.getPort()));
                        respStreams.add(s);
                        Log.i(TAG, "SETUP2: mirror dataPort=" + p.mirror.getPort()
                                + " scid=" + Long.toUnsignedString(scid));
                        callback.onMirrorStart();
                    } catch (Exception e) {
                        Log.e(TAG, "SETUP2 failed", e);
                    }
                } else if (type == 96) {
                    try {
                        Object ctObj = stream.get("ct");
                        Object spfObj = stream.get("spf");
                        Object usingScreenObj = stream.get("usingScreen");
                        int ct = ctObj instanceof Number ? ((Number) ctObj).intValue() : 0;
                        int spf = spfObj instanceof Number ? ((Number) spfObj).intValue() : 0;
                        boolean usingScreen = Boolean.TRUE.equals(usingScreenObj);
                        if (p.aeskey == null || p.eiv == null) {
                            Log.e(TAG, "SETUP audio without aeskey/eiv");
                            continue;
                        }
                        if (p.audio != null && p.audioCt == ct && p.audioSpf == spf) {
                            // iPhone 会反复重发相同 SETUP：直接复用现有流，
                            // 避免重建导致的爆音和封面/曲名被清空
                            Bplist.Dict s = new Bplist.Dict()
                                    .put("type", Bplist.i64(96))
                                    .put("dataPort", Bplist.i64(p.audio.getDataPort()))
                                    .put("controlPort", Bplist.i64(p.audio.getControlPort()));
                            respStreams.add(s);
                            Log.i(TAG, "SETUP audio: reuse existing stream ct=" + ct);
                            continue;
                        }
                        // 镜像伴音(ct=8 AAC-ELD)要低延迟；音乐(ALAC/AAC-LC)可以深缓冲
                        boolean lowLatency = usingScreen || ct == 8;
                        if (p.audio != null) p.audio.stop();
                        if (p.audioRenderer != null) p.audioRenderer.stop();
                        p.audioRenderer = new AudioRenderer(ct, spf, lowLatency);
                        p.audioRenderer.start();
                        p.audio = new AudioReceiver(p.aeskey, p.eiv, p.audioRenderer);
                        p.audio.start();
                        p.audioCt = ct;
                        p.audioSpf = spf;
                        Bplist.Dict s = new Bplist.Dict()
                                .put("type", Bplist.i64(96))
                                .put("dataPort", Bplist.i64(p.audio.getDataPort()))
                                .put("controlPort", Bplist.i64(p.audio.getControlPort()));
                        respStreams.add(s);
                        Log.i(TAG, "SETUP audio: ct=" + ct + " spf=" + spf
                                + " dataPort=" + p.audio.getDataPort()
                                + " controlPort=" + p.audio.getControlPort());
                        AudioSession.get().onStart();
                        callback.onAudioStart();
                    } catch (Exception e) {
                        Log.e(TAG, "SETUP audio failed", e);
                    }
                }
            }
            resp.put("streams", respStreams);
        }
        return Bplist.write(resp);
    }

    /** 构造 /info 应答：探测请求只回 TXT，正式请求回完整设备信息（对齐 UxPlay） */
    private byte[] handleInfo(byte[] body) {
        String deviceId = AirPlayProfile.deviceId(ctx);
        if (body.length > 0) {
            String probe = new String(body, StandardCharsets.ISO_8859_1);
            Bplist.Dict d = new Bplist.Dict();
            if (probe.contains("txtAirPlay")) {
                d.put("txtAirPlay",
                        Bplist.data(AirPlayProfile.txtRecordBytes(AirPlayProfile.airplayTxt(deviceId))));
                return Bplist.write(d);
            }
            if (probe.contains("txtRAOP")) {
                d.put("txtRAOP",
                        Bplist.data(AirPlayProfile.txtRecordBytes(AirPlayProfile.raopTxt())));
                return Bplist.write(d);
            }
        }

        Bplist.Dict latency0 = new Bplist.Dict()
                .put("type", Bplist.i64(100))
                .put("inputLatencyMicros", Bplist.i64(0))
                .put("audioType", Bplist.str("default"))
                .put("outputLatencyMicros", Bplist.i64(0));
        Bplist.Dict latency1 = new Bplist.Dict()
                .put("type", Bplist.i64(101))
                .put("inputLatencyMicros", Bplist.i64(0))
                .put("audioType", Bplist.str("default"))
                .put("outputLatencyMicros", Bplist.i64(0));
        Bplist.Arr audioLatencies = new Bplist.Arr().add(latency0).add(latency1);

        Bplist.Dict format0 = new Bplist.Dict()
                .put("type", Bplist.i64(100))
                .put("audioInputFormats", Bplist.i64(0x3fffffc))
                .put("audioOutputFormats", Bplist.i64(0x3fffffc));
        Bplist.Dict format1 = new Bplist.Dict()
                .put("type", Bplist.i64(101))
                .put("audioInputFormats", Bplist.i64(0x3fffffc))
                .put("audioOutputFormats", Bplist.i64(0x3fffffc));
        Bplist.Arr audioFormats = new Bplist.Arr().add(format0).add(format1);

        Bplist.Dict display = new Bplist.Dict()
                .put("uuid", Bplist.str("e0ff8a27-6738-3d56-8a16-cc53aacee925"))
                .put("widthPhysical", Bplist.i64(0))
                .put("heightPhysical", Bplist.i64(0))
                .put("width", Bplist.i64(1920))
                .put("height", Bplist.i64(1080))
                .put("widthPixels", Bplist.i64(1920))
                .put("heightPixels", Bplist.i64(1080))
                .put("rotation", Bplist.bool(false))
                .put("refreshRate", Bplist.real(1.0 / 60.0))
                .put("maxFPS", Bplist.i64(60))
                .put("overscanned", Bplist.bool(false))
                .put("features", Bplist.i64(14));
        Bplist.Arr displays = new Bplist.Arr().add(display);

        Bplist.Dict root = new Bplist.Dict()
                .put("deviceID", Bplist.str(deviceId))
                .put("macAddress", Bplist.str(deviceId))
                .put("pk", Bplist.data(AirPlayCrypto.edPublicKey()))
                .put("features", Bplist.i64(AirPlayProfile.FEATURES_LONG))
                .put("name", Bplist.str(deviceName))
                .put("pi", Bplist.str(AirPlayProfile.PI))
                .put("vv", Bplist.i64(2))
                .put("statusFlags", Bplist.i64(68))
                .put("keepAliveLowPower", Bplist.bool(true))
                .put("sourceVersion", Bplist.str(AirPlayProfile.SRCVERS))
                .put("keepAliveSendStatsAsBody", Bplist.bool(true))
                .put("model", Bplist.str(AirPlayProfile.MODEL))
                .put("initialVolume", Bplist.real(-20.0))
                .put("audioLatencies", audioLatencies)
                .put("audioFormats", audioFormats)
                .put("displays", displays);
        return Bplist.write(root);
    }

    /**
     * pair-verify（对齐 UxPlay pairing.c）：
     *  第 1 步 body = 04头 + 客户端X25519公钥(32) + 客户端Ed25519公钥(32)
     *      回复 = 我方X25519公钥(32) + AES-128-CTR加密后的Ed25519签名(64)
     *  第 2 步 body = 04头 + 客户端签名(64)，解密并验签
     */
    private byte[] handlePairVerify(byte[] body, PairingState p) throws Exception {
        if (body.length < 4) return new byte[0];

        if (body[0] == 1 && body.length >= 68) {
            p.theirX25519 = java.util.Arrays.copyOfRange(body, 4, 36);
            p.theirEd25519 = java.util.Arrays.copyOfRange(body, 36, 68);
            p.x25519 = new AirPlayCrypto.X25519Session();
            p.sharedSecret = p.x25519.agree(p.theirX25519);

            byte[] key = AirPlayCrypto.deriveKey("Pair-Verify-AES-Key", p.sharedSecret, 16);
            byte[] iv = AirPlayCrypto.deriveKey("Pair-Verify-AES-IV", p.sharedSecret, 16);

            // 签名内容 = 我方X25519公钥 || 对方X25519公钥
            byte[] ourX = p.x25519.publicKey();
            byte[] sigMsg = new byte[64];
            System.arraycopy(ourX, 0, sigMsg, 0, 32);
            System.arraycopy(p.theirX25519, 0, sigMsg, 32, 32);
            byte[] sig = AirPlayCrypto.edSign(sigMsg);
            byte[] encSig = AirPlayCrypto.aes128Ctr(key, iv, sig, 0, sig.length);

            byte[] resp = new byte[96];
            System.arraycopy(ourX, 0, resp, 0, 32);
            System.arraycopy(encSig, 0, resp, 32, 64);
            Log.i(TAG, "pair-verify step1 done");
            return resp;
        }

        if (body[0] == 0 && body.length >= 68) {
            if (p.sharedSecret == null) return new byte[0];
            byte[] key = AirPlayCrypto.deriveKey("Pair-Verify-AES-Key", p.sharedSecret, 16);
            byte[] iv = AirPlayCrypto.deriveKey("Pair-Verify-AES-IV", p.sharedSecret, 16);
            // 客户端的 CTR 流先用掉了 64 字节，解密前补一个空轮（UxPlay 实测结论）
            byte[] encSig = java.util.Arrays.copyOfRange(body, 4, 68);
            CipherHolder holder = new CipherHolder(key, iv);
            holder.crypt(new byte[64]); // 客户端流的前 64 字节
            byte[] sig = holder.crypt(encSig);

            byte[] sigMsg = new byte[64];
            System.arraycopy(p.theirX25519, 0, sigMsg, 0, 32);
            System.arraycopy(p.x25519.publicKey(), 0, sigMsg, 32, 32);
            boolean ok = AirPlayCrypto.edVerify(sig, sigMsg, p.theirEd25519);
            Log.i(TAG, "pair-verify step2 signature valid=" + ok);
            p.paired = true; // 家用场景宽松处理：验签失败也放行
            return new byte[0];
        }
        return new byte[0];
    }

    /** SET_PARAMETER：音量（text）+ 曲目信息/封面（DMAP、plist 或图片），转发给 AudioSession */
    private void handleSetParameter(Map<String, String> headers, byte[] body, PairingState p) {
        String ct = headers.get("content-type");
        if (ct == null) return;
        String contentType = ct.toLowerCase(Locale.US);

        if (contentType.startsWith("text/parameters")) {
            String text = new String(body, StandardCharsets.ISO_8859_1).trim();
            if (text.startsWith("volume:")) {
                try {
                    float db = Float.parseFloat(text.substring(7).trim());
                    if (p.audioRenderer != null) p.audioRenderer.setVolumeDb(db);
                    AudioSession.get().setVolume(db);
                    Log.i(TAG, "volume set to " + db + " dB");
                } catch (NumberFormatException e) {
                    Log.w(TAG, "bad volume: " + text);
                }
            }
            return;
        }

        // Apple Music / iOS 音乐投放使用 DMAP 标签树，而不是 binary plist
        if (contentType.startsWith("application/x-dmap-tagged")) {
            try {
                Map<String, byte[]> tags = DmapReader.read(body);
                String title = DmapReader.text(tags, "minm");
                String artist = DmapReader.text(tags, "asar");
                String album = DmapReader.text(tags, "asal");
                String lyrics = DmapReader.text(tags, "asly");
                long durationMs = DmapReader.uint32(tags, "astm");
                long trackId = DmapReader.uint64(tags, "mper");
                if (title != null || artist != null || album != null
                        || lyrics != null || durationMs > 0) {
                    AudioSession.get().setTrackInfo(
                            title, artist, album, lyrics, durationMs, trackId);
                    Log.i(TAG, "dmap track: " + title + " / " + artist + " / " + album
                            + " duration=" + durationMs + " tags=" + tags.keySet());
                } else {
                    Log.i(TAG, "dmap metadata without supported text tags: " + tags.keySet());
                }
            } catch (Exception e) {
                Log.w(TAG, "dmap parse failed: " + e.getMessage());
            }
            return;
        }

        // 曲目信息：iPhone 音乐投放时发 application/x-apple-binary-plist
        if (contentType.contains("binary-plist")) {
            try {
                Map<String, Object> params = BplistReader.readDict(body);
                String title = firstString(params, "itemName", "title",
                        "kMRMediaRemoteNowPlayingInfoTitle");
                String artist = firstString(params, "artist", "artistName",
                        "kMRMediaRemoteNowPlayingInfoArtist");
                String album = firstString(params, "album", "albumName",
                        "kMRMediaRemoteNowPlayingInfoAlbum");
                String lyrics = firstString(params, "lyrics", "lyric",
                        "kMRMediaRemoteNowPlayingInfoLyrics");
                long durationMs = firstLong(params, "duration", "durationMs",
                        "kMRMediaRemoteNowPlayingInfoDuration");
                if (durationMs > 0 && durationMs < 10000) durationMs *= 1000;
                if (title != null || artist != null || album != null
                        || lyrics != null || durationMs > 0) {
                    AudioSession.get().setTrackInfo(
                            title, artist, album, lyrics, durationMs, 0);
                    Log.i(TAG, "track: " + title + " / " + artist + " / " + album);
                }
            } catch (Exception e) {
                Log.w(TAG, "set_parameter plist parse failed: " + e.getMessage());
            }
            return;
        }

        // 专辑封面：image/jpeg 或 image/png
        if (contentType.startsWith("image/")) {
            AudioSession.get().setArtwork(body);
            Log.i(TAG, "artwork: " + body.length + " bytes (" + ct + ")");
        }
    }

    private static String strVal(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String firstString(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = strVal(values.get(key));
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static long firstLong(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number) {
                long number = ((Number) value).longValue();
                if (number > 0) return number;
            }
        }
        return 0;
    }

    /** AES-CTR 流式包装：CTR 是流密码，多次 crypt 要保持同一 Cipher 实例才有连续流 */
    private static class CipherHolder {
        private final javax.crypto.Cipher cipher;

        CipherHolder(byte[] key, byte[] iv) throws Exception {
            cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv));
        }

        byte[] crypt(byte[] data) throws Exception {
            return cipher.update(data);
        }
    }

    /** fp-setup 两段式应答（UxPlay 逆向的固定载荷）；第二段的 164B keymsg 存下给 Playfair 用 */
    private byte[] handleFpSetup(byte[] body, PairingState p) {
        if (body.length == 16 && body[4] == 0x03) {
            int mode = body[14] & 0xFF;
            if (mode < FP_REPLIES.length) {
                Log.i(TAG, "fp-setup phase1 mode=" + mode);
                return AirPlayCrypto.hexToBytes(FP_REPLIES[mode]);
            }
        } else if (body.length == 164 && body[4] == 0x03) {
            p.fpKeymsg = body.clone();
            byte[] resp = new byte[32];
            System.arraycopy(FP_HEADER, 0, resp, 0, 12);
            System.arraycopy(body, 144, resp, 12, 20);
            Log.i(TAG, "fp-setup phase2 done");
            return resp;
        }
        Log.w(TAG, "fp-setup unsupported len=" + body.length);
        return new byte[0];
    }

    /** HTTP/RTSP 头部按 ISO-8859-1 逐字节读一行，避免污染二进制 body */
    private static String readAsciiLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1, cur;
        while ((cur = in.read()) >= 0) {
            if (cur == '\n' && prev == '\r') {
                byte[] b = buf.toByteArray();
                return new String(b, 0, b.length - 1, StandardCharsets.ISO_8859_1);
            }
            buf.write(cur);
            prev = cur;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    public void stop() {
        running = false;
        closeActiveClients();
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    /** 结束当前 AirPlay 会话，但不停止 RTSP 监听，接收服务仍可被重新连接。 */
    public void closeActiveClients() {
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        activeClients.clear();
    }
}
