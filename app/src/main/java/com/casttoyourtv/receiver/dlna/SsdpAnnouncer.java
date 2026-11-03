package com.casttoyourtv.receiver.dlna;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

/**
 * SSDP（UPnP 发现协议）：
 *  - 加入组播 239.255.255.250:1900，监听手机发来的 M-SEARCH
 *  - 用绑定 1900 的同一个 socket 应答（部分手机只认来自 1900 端口的响应）
 *  - 应答时连发 2 包 + 按 ST 返回 rootdevice/device/service 三类，防 WiFi 丢包
 *  - 每 15s 广播一次 ssdp:alive，手机无需搜索也能发现
 */
public class SsdpAnnouncer {

    private static final String TAG = "SsdpAnnouncer";
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final String SERVER = "Android/10 UPnP/1.0 CastToYourTV/0.2";

    private final InetAddress localIp;
    private final String udn;
    private final String deviceName;
    private final String location;

    private volatile boolean running;
    private MulticastSocket socket;
    private Thread listenThread;
    private Thread notifyThread;

    public SsdpAnnouncer(InetAddress localIp, String udn, String deviceName, String location) {
        this.localIp = localIp;
        this.udn = udn;
        this.deviceName = deviceName;
        this.location = location;
    }

    public void start() {
        running = true;
        listenThread = new Thread(this::listenLoop, "ssdp-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        notifyThread = new Thread(this::notifyLoop, "ssdp-notify");
        notifyThread.setDaemon(true);
        notifyThread.start();
    }

    private void listenLoop() {
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            InetAddress group = InetAddress.getByName(GROUP);
            NetworkInterface ni = NetworkInterface.getByInetAddress(localIp);
            if (ni != null) {
                socket.joinGroup(new InetSocketAddress(group, PORT), ni);
            } else {
                socket.joinGroup(group);
            }
            Log.i(TAG, "SSDP listening on 1900");

            byte[] buf = new byte[4096];
            while (running) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                String msg = new String(pkt.getData(), 0, pkt.getLength());
                if (msg.startsWith("M-SEARCH")) {
                    String st = extractSt(msg);
                    Log.i(TAG, "M-SEARCH from " + pkt.getAddress().getHostAddress()
                            + ":" + pkt.getPort() + " ST=" + st);
                    respondTo(pkt.getAddress(), pkt.getPort(), st);
                }
            }
        } catch (Throwable e) {
            if (running) Log.e(TAG, "listen error", e);
        }
    }

    /** 提取 M-SEARCH 里的 ST 头（搜索目标类型） */
    private String extractSt(String msg) {
        for (String line : msg.split("\r\n")) {
            if (line.toLowerCase().startsWith("st:")) {
                return line.substring(3).trim();
            }
        }
        return "";
    }

    /** 按搜索目标应答；ssdp:all 时回 3 类（rootdevice/device/service） */
    private void respondTo(InetAddress addr, int port, String st) {
        try {
            boolean all = st.contains("ssdp:all");
            boolean isRenderer = st.contains("MediaRenderer");
            boolean isRoot = st.contains("upnp:rootdevice");
            boolean isAvt = st.contains("AVTransport");
            boolean isCm = st.contains("ConnectionManager");
            boolean isRc = st.contains("RenderingControl");

            if (!all && !isRenderer && !isRoot && !isAvt && !isCm && !isRc) {
                return; // 与投屏无关的搜索不应答
            }

            if (all || isRoot)      sendResponse(addr, port, "upnp:rootdevice", udn + "::upnp:rootdevice");
            if (all || isRenderer)  sendResponse(addr, port, "urn:schemas-upnp-org:device:MediaRenderer:1", udn + "::urn:schemas-upnp-org:device:MediaRenderer:1");
            if (all || isAvt)       sendResponse(addr, port, "urn:schemas-upnp-org:service:AVTransport:1", udn + "::urn:schemas-upnp-org:service:AVTransport:1");
            if (all || isCm)        sendResponse(addr, port, "urn:schemas-upnp-org:service:ConnectionManager:1", udn + "::urn:schemas-upnp-org:service:ConnectionManager:1");
            if (all || isRc)        sendResponse(addr, port, "urn:schemas-upnp-org:service:RenderingControl:1", udn + "::urn:schemas-upnp-org:service:RenderingControl:1");
            Log.i(TAG, "已应答 M-SEARCH -> " + addr.getHostAddress());
        } catch (Throwable e) {
            Log.e(TAG, "respond error", e);
        }
    }

    /** 用绑定 1900 的 socket 连发 2 包应答（防 WiFi 丢包） */
    private void sendResponse(InetAddress addr, int port, String st, String usn) {
        String resp = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: " + SERVER + "\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usn + "\r\n"
                + "\r\n";
        byte[] data = resp.getBytes();
        try {
            for (int i = 0; i < 2; i++) {
                DatagramPacket out = new DatagramPacket(data, data.length, addr, port);
                socket.send(out);   // 从 1900 端口发出，手机才会认
                Thread.sleep(80);
            }
        } catch (Throwable e) {
            Log.e(TAG, "send response error", e);
        }
    }

    private void notifyLoop() {
        // 启动后 5 次连发（1.5s 间隔）让手机尽快发现；之后每 15s 一次保活
        int burst = 5;
        while (running) {
            try {
                sendNotifyAll();
                if (burst > 0) {
                    burst--;
                    Thread.sleep(1500);
                } else {
                    Thread.sleep(15000);
                }
            } catch (Throwable e) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void sendNotifyAll() {
        sendNotify("upnp:rootdevice", udn + "::upnp:rootdevice");
        sendNotify("urn:schemas-upnp-org:device:MediaRenderer:1", udn + "::urn:schemas-upnp-org:device:MediaRenderer:1");
        sendNotify("urn:schemas-upnp-org:service:AVTransport:1", udn + "::urn:schemas-upnp-org:service:AVTransport:1");
    }

    private void sendNotify(String nt, String usn) {
        try {
            String msg = "NOTIFY * HTTP/1.1\r\n"
                    + "HOST: " + GROUP + ":" + PORT + "\r\n"
                    + "CACHE-CONTROL: max-age=1800\r\n"
                    + "LOCATION: " + location + "\r\n"
                    + "NT: " + nt + "\r\n"
                    + "NTS: ssdp:alive\r\n"
                    + "SERVER: " + SERVER + "\r\n"
                    + "USN: " + usn + "\r\n"
                    + "\r\n";
            byte[] data = msg.getBytes();
            InetAddress group = InetAddress.getByName(GROUP);
            DatagramPacket pkt = new DatagramPacket(data, data.length, group, PORT);
            DatagramSocket ds = new DatagramSocket();
            ds.send(pkt);
            ds.close();
        } catch (Throwable e) {
            Log.e(TAG, "notify error", e);
        }
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
    }
}
