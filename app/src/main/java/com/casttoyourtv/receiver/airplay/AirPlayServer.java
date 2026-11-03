package com.casttoyourtv.receiver.airplay;

import android.content.Context;

import com.casttoyourtv.receiver.CastSession;

/**
 * AirPlay / RAOP 接收器。
 *  - 通过 mDNS 广播 _raop._tcp / _airplay._tcp，让 iPhone 在「屏幕镜像/隔空播放」里看到本设备
 *  - 监听 7000(RTSP 控制) 端口处理 SETUP/ANNOUNCE/PLAY
 *
 * 说明：完整的 AirPlay 视频镜像需要解密(FairPlay)与 H.264 实时解码，
 * 工作量较大。当前实现「设备可被发现 + RTSP 握手骨架」，
 * 后续接入开源解码(可参考 UxPlay/shairport 思路)补全镜像画面。
 */
public class AirPlayServer {

    public static final int RAOP_PORT = 7000;

    private final Context ctx;
    private final String deviceName;
    private final CastSession.Callback callback;

    private NsdAdvertiser advertiser;
    private RtspListener listener;

    public AirPlayServer(Context ctx, String deviceName, CastSession.Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.deviceName = deviceName;
        this.callback = callback;
    }

    public void start() {
        // 1. 广播 mDNS 服务，让 iPhone 发现
        advertiser = new NsdAdvertiser(ctx, deviceName, RAOP_PORT);
        advertiser.start();

        // 2. 起 RTSP 监听，处理 AirPlay 握手
        listener = new RtspListener(RAOP_PORT, callback, ctx, deviceName);
        listener.start();
    }

    public void stop() {
        if (advertiser != null) advertiser.stop();
        if (listener != null) listener.stop();
    }
}
