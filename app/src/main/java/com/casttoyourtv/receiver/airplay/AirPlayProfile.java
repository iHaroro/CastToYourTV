package com.casttoyourtv.receiver.airplay;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * AirPlay 设备身份配置：mDNS TXT 与 /info 应答共用同一份数据，
 * 字段值对齐开源 UxPlay（伪装 AppleTV3,2），保证 iOS 新版可见可连。
 */
public class AirPlayProfile {

    public static final String FEATURES = "0x5A7FFEE6,0x0";
    public static final long FEATURES_LONG = 0x5A7FFEE6L;
    public static final String MODEL = "AppleTV3,2";
    public static final String SRCVERS = "220.68";
    public static final String PI = "2e388006-13ba-4041-9a67-25dd4a43d536";

    /** 优先读 wlan0 真实 MAC；读不到（Android 11+ 限制）时生成并持久化一个本地管理地址 */
    public static String deviceId(Context ctx) {
        try {
            NetworkInterface ni = NetworkInterface.getByName("wlan0");
            if (ni != null) {
                byte[] hw = ni.getHardwareAddress();
                if (hw != null && hw.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 6; i++) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02X", hw[i]));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        }
        SharedPreferences sp = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE);
        String id = sp.getString("device_id", null);
        if (id == null) {
            Random r = new Random();
            id = String.format("02:%02X:%02X:%02X:%02X:%02X",
                    r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256));
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static Map<String, String> airplayTxt(String deviceId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("deviceid", deviceId);
        m.put("features", FEATURES);
        m.put("pw", "false");
        m.put("flags", "0x4");
        m.put("model", MODEL);
        m.put("pk", AirPlayCrypto.pkHex());
        m.put("pi", PI);
        m.put("srcvers", SRCVERS);
        m.put("vv", "2");
        return m;
    }

    public static Map<String, String> raopTxt() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("txtvers", "1");
        m.put("ch", "2");
        m.put("cn", "0,1,2,3");
        m.put("da", "true");
        m.put("et", "0,3,5");
        m.put("vv", "2");
        m.put("ft", FEATURES);
        m.put("am", MODEL);
        m.put("md", "0,1,2");
        m.put("rhd", "5.6.0.0");
        m.put("pw", "false");
        m.put("sf", "0x4");
        m.put("sr", "44100");
        m.put("ss", "16");
        m.put("sv", "false");
        m.put("tp", "UDP");
        m.put("vs", SRCVERS);
        m.put("vn", "65537");
        m.put("pk", AirPlayCrypto.pkHex());
        return m;
    }

    /** DNS-SD TXT 记录二进制格式：每项为 1 长度字节 + "key=value" */
    public static byte[] txtRecordBytes(Map<String, String> txt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : txt.entrySet()) {
            byte[] kv = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.US_ASCII);
            out.write(kv.length);
            out.write(kv, 0, kv.length);
        }
        return out.toByteArray();
    }

}
