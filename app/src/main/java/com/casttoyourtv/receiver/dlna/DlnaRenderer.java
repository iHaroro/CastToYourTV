package com.casttoyourtv.receiver.dlna;

import android.content.Context;
import android.util.Log;

import com.casttoyourtv.receiver.CastSession;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 手写 DLNA/UPnP 渲染器：
 *  1. SSDP：加入组播 239.255.255.250:1900，周期广播 + 应答 M-SEARCH，让手机发现本设备
 *  2. 内置微型 HTTP 服务器：提供设备描述 / 服务描述 / 接收 SetAVTransportURI 控制
 * 不依赖第三方库，100% 可在 Android 运行。
 */
public class DlnaRenderer {

    private static final String TAG = "DlnaRenderer";

    private final Context ctx;
    private final String deviceName;
    private final CastSession.Callback callback;

    private final String udn = "uuid:" + UUID.randomUUID();
    private SsdpAnnouncer announcer;
    private DlnaHttpServer httpServer;
    private int httpPort = 0;

    public DlnaRenderer(Context ctx, String deviceName, CastSession.Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.deviceName = deviceName;
        this.callback = callback;
    }

    public void start() {
        new Thread(() -> {
            try {
                InetAddress localIp = NetUtil.getLocalIp(ctx);
                if (localIp == null) {
                    Log.e(TAG, "无法获取本机 IP");
                    return;
                }
                String host = localIp.getHostAddress();

                // 1. 启动 HTTP 服务器（设备描述 + AVTransport 控制）
                httpServer = new DlnaHttpServer(0, host, udn, deviceName, callback);
                httpServer.start();
                httpPort = httpServer.getPort();
                Log.i(TAG, "DLNA HTTP server on " + host + ":" + httpPort);

                // 2. 启动 SSDP 广播/应答
                String location = "http://" + host + ":" + httpPort + "/device.xml";
                announcer = new SsdpAnnouncer(localIp, udn, deviceName, location);
                announcer.start();
                Log.i(TAG, "SSDP announcer started, device discoverable");
            } catch (Throwable e) {
                Log.e(TAG, "DLNA start failed", e);
            }
        }, "dlna-start").start();
    }

    public void stop() {
        if (announcer != null) announcer.stop();
        if (httpServer != null) httpServer.stop();
    }
}
