package com.casttoyourtv.receiver.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.Map;

public class NsdAdvertiser {

    private static final String TAG = "NsdAdvertiser";

    private final Context ctx;
    private final String name;
    private final int port;
    private final NsdManager nsd;

    public NsdAdvertiser(Context ctx, String name, int port) {
        this.ctx = ctx;
        this.name = name;
        this.port = port;
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    }

    public void start() {
        AirPlayCrypto.init(ctx); // 确保配对密钥对已就绪（pk 写进 TXT）
        String deviceId = AirPlayProfile.deviceId(ctx); // "AA:BB:CC:DD:EE:FF"
        register("_airplay._tcp.", name, AirPlayProfile.airplayTxt(deviceId));
        // RAOP 服务名约定为「MAC无冒号@设备名」
        register("_raop._tcp.", deviceId.replace(":", "") + "@" + name,
                AirPlayProfile.raopTxt());
    }

    private void register(String type, String serviceName, Map<String, String> txt) {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(serviceName);
            info.setServiceType(type);
            info.setPort(port);
            for (Map.Entry<String, String> e : txt.entrySet()) {
                info.setAttribute(e.getKey(), e.getValue());
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo s) {
                    Log.i(TAG, "mDNS registered: " + s.getServiceName() + " " + type);
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo s, int e) {
                    Log.e(TAG, "mDNS register FAILED: " + serviceName + " err=" + e);
                }

                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
            });
        } catch (Exception e) {
            Log.e(TAG, "register " + type + " error", e);
        }
    }

    public void stop() {
        // NsdManager 需要逐个反注册，这里简化：进程退出自动清理
    }
}
