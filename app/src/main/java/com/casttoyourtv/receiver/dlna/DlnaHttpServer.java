package com.casttoyourtv.receiver.dlna;

import android.util.Log;

import com.casttoyourtv.receiver.CastSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 微型 HTTP 服务器：
 *  GET  /device.xml       -> 设备描述（手机发现的入口）
 *  GET  /avt.xml          -> AVTransport 服务描述
 *  POST /avt/control      -> 接收 SetAVTransportURI 等 DLNA 控制指令
 */
public class DlnaHttpServer {

    private static final String TAG = "DlnaHttpServer";

    private final int requestedPort;
    private final String host;
    private final String udn;
    private final String deviceName;
    private final CastSession.Callback callback;

    private ServerSocket server;
    private volatile boolean running;
    private Thread thread;

    public DlnaHttpServer(int port, String host, String udn, String deviceName,
                          CastSession.Callback callback) {
        this.requestedPort = port;
        this.host = host;
        this.udn = udn;
        this.deviceName = deviceName;
        this.callback = callback;
    }

    public void start() throws Exception {
        server = new ServerSocket(requestedPort);
        running = true;
        thread = new Thread(this::loop, "dlna-http");
        thread.setDaemon(true);
        thread.start();
    }

    public int getPort() {
        return server != null ? server.getLocalPort() : 0;
    }

    private void loop() {
        try {
            while (running) {
                Socket client = server.accept();
                new Thread(() -> handle(client), "dlna-req").start();
            }
        } catch (Throwable e) {
            if (running) Log.e(TAG, "accept error", e);
        }
    }

    private void handle(Socket client) {
        try (Socket c = client;
             InputStream rawIn = c.getInputStream();
             OutputStream out = c.getOutputStream()) {

            String requestLine = readLine(rawIn);
            if (requestLine == null) return;
            String method = requestLine.split(" ")[0];
            String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
            Log.i(TAG, "HTTP " + method + " " + path + " from " + c.getInetAddress().getHostAddress());

            int contentLen = 0;
            boolean expectContinue = false;
            String line;
            while ((line = readLine(rawIn)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLen = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                } else if (lower.startsWith("expect:") && lower.contains("100-continue")) {
                    expectContinue = true;
                }
            }

            // 客户端带 Expect: 100-continue 时会先等确认再发 body，
            // 不回 100 它会干等约 30 秒超时，这是投屏延迟 25~30 秒的根因
            if (expectContinue && contentLen > 0) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            byte[] bodyBytes = new byte[contentLen];
            int off = 0;
            while (off < contentLen) {
                int n = rawIn.read(bodyBytes, off, contentLen - off);
                if (n < 0) break;
                off += n;
            }
            String body = new String(bodyBytes, 0, off, StandardCharsets.UTF_8);

            if (method.equalsIgnoreCase("SUBSCRIBE")) {
                // GENA 事件订阅：直接接受（不回发事件），手机拿到 SID 后就继续走投屏流程
                sendSubscribeOk(out);
            } else if (method.equalsIgnoreCase("UNSUBSCRIBE")) {
                send(out, "200 OK", "text/xml", "");
            } else if (path.contains("device.xml")) {
                sendXml(out, deviceXml());
            } else if (path.contains("avt.xml")) {
                sendXml(out, avtScpd());
            } else if (path.contains("cm.xml")) {
                sendXml(out, cmScpd());
            } else if (path.contains("rc.xml")) {
                sendXml(out, rcScpd());
            } else if (path.contains("/cm/control")) {
                // ConnectionManager: 手机询问支持的协议格式
                sendOk(out, "<u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">"
                        + "<Source>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Source>"
                        + "<Sink></Sink></u:GetProtocolInfoResponse>");
            } else if (path.contains("/rc/control")) {
                // RenderingControl: 音量查询返回当前系统音量即可（简化）
                sendOk(out, "<u:GetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">"
                        + "<CurrentVolume>50</CurrentVolume></u:GetVolumeResponse>");
            } else if (path.contains("control")) {
                handleControl(body, out);
            } else {
                send(out, "404 Not Found", "text/plain", "not found");
            }
        } catch (Throwable e) {
            Log.e(TAG, "handle error", e);
        }
    }

    // HTTP 头部按 ISO-8859-1 逐字节读一行，避免 UTF-8 中文元数据撑爆 Content-Length 计数
    private static String readLine(InputStream in) throws Exception {
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
        return buf.size() == 0 ? null : buf.toString("ISO-8859-1");
    }

    private void sendSubscribeOk(OutputStream out) throws Exception {
        String sid = "uuid:" + java.util.UUID.randomUUID();
        String head = "HTTP/1.1 200 OK\r\n"
                + "SID: " + sid + "\r\n"
                + "TIMEOUT: Second-1800\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleControl(String soap, OutputStream out) throws Exception {
        // 解析 SetAVTransportURI 里的视频地址
        if (soap.contains("SetAVTransportURI")) {
            // SOAP 里的 URL 是 XML 转义过的（& -> &amp;），必须解码后才能播放
            String uri = decodeXml(extractTag(soap, "CurrentURI"));
            String meta = decodeXml(extractTag(soap, "CurrentURIMetaData"));
            String title = extractTag(meta, "dc:title");
            Log.i(TAG, "收到投屏: " + uri);
            if (callback != null && uri != null && !uri.isEmpty()) {
                callback.onPlayVideo(uri, title != null ? title : uri);
            }
            sendOk(out, "<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:SetAVTransportURIResponse>");
        } else if (soap.contains("Stop")) {
            if (callback != null) callback.onStop();
            sendOk(out, "<u:StopResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:StopResponse>");
        } else if (soap.contains("Seek")) {
            // Seek 的 Target 形如 "00:05:30"（REL_TIME）或 "0:05:30.000"
            String target = decodeXml(extractTag(soap, "Target"));
            long ms = parseTimeMs(target);
            Log.i(TAG, "收到 Seek: " + target + " -> " + ms + "ms");
            if (callback != null && ms >= 0) callback.onSeek(ms);
            sendOk(out, "<u:SeekResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:SeekResponse>");
        } else if (soap.contains("Pause")) {
            if (callback != null) callback.onPause();
            sendOk(out, "<u:PauseResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:PauseResponse>");
        } else if (soap.contains("Play")) {
            if (callback != null) callback.onResume();
            sendOk(out, "<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:PlayResponse>");
        } else {
            sendOk(out, "<u:Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:Response>");
        }
    }

    /** 解析 "HH:MM:SS(.fff)" 或纯秒数为毫秒，解析失败返回 -1 */
    private long parseTimeMs(String target) {
        if (target == null) return -1;
        target = target.trim();
        try {
            if (target.contains(":")) {
                String[] parts = target.split(":");
                double sec = Double.parseDouble(parts[parts.length - 1]);
                long total = (long) (sec * 1000);
                if (parts.length >= 2) total += Long.parseLong(parts[parts.length - 2]) * 60000L;
                if (parts.length >= 3) total += Long.parseLong(parts[parts.length - 3]) * 3600000L;
                return total;
            }
            return (long) (Double.parseDouble(target) * 1000);
        } catch (Throwable e) {
            return -1;
        }
    }

    private String extractTag(String xml, String tag) {
        if (xml == null) return null;
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int s = xml.indexOf(open);
        int e = xml.indexOf(close);
        if (s >= 0 && e > s) {
            return xml.substring(s + open.length(), e);
        }
        // 兼容带命名空间前缀的标签
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[^>]*" + tag + "[^>]*>(.*?)</[^>]*" + tag + ">", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** 解码 XML 实体，否则 B站 CDN 会因参数错误返回 403 */
    private String decodeXml(String s) {
        if (s == null) return null;
        return s.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private void sendOk(OutputStream out, String innerBody) throws Exception {
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + innerBody + "</s:Body></s:Envelope>";
        send(out, "200 OK", "text/xml; charset=\"utf-8\"", soap);
    }

    private void sendXml(OutputStream out, String xml) throws Exception {
        send(out, "200 OK", "text/xml; charset=\"utf-8\"", xml);
    }

    private void send(OutputStream out, String status, String contentType, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private String deviceXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>" + deviceName + "</friendlyName>"
                + "<manufacturer>CastToYourTV</manufacturer>"
                + "<modelName>CastToYourTV</modelName>"
                + "<UDN>" + udn + "</UDN>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
                + "<SCPDURL>/avt.xml</SCPDURL>"
                + "<controlURL>/avt/control</controlURL>"
                + "<eventSubURL>/avt/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>"
                + "<SCPDURL>/cm.xml</SCPDURL>"
                + "<controlURL>/cm/control</controlURL>"
                + "<eventSubURL>/cm/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>"
                + "<SCPDURL>/rc.xml</SCPDURL>"
                + "<controlURL>/rc/control</controlURL>"
                + "<eventSubURL>/rc/event</eventSubURL>"
                + "</service>"
                + "</serviceList>"
                + "</device></root>";
    }

    private String avtScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>"
                + "<action><name>SetAVTransportURI</name>"
                + "<argumentList>"
                + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
                + "<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
                + "<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
                + "</argumentList></action>"
                + "<action><name>Play</name></action>"
                + "<action><name>Stop</name></action>"
                + "<action><name>Pause</name></action>"
                + "</actionList>"
                + "<serviceStateTable>"
                + "<stateVariable sendEvents=\"no\"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>"
                + "<stateVariable sendEvents=\"no\"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>"
                + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>"
                + "</serviceStateTable>"
                + "</scpd>";
    }

    private String cmScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList><action><name>GetProtocolInfo</name></action></actionList>"
                + "<serviceStateTable>"
                + "<stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>"
                + "<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>"
                + "</serviceStateTable></scpd>";
    }

    private String rcScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList><action><name>GetVolume</name></action><action><name>SetVolume</name></action></actionList>"
                + "<serviceStateTable>"
                + "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType></stateVariable>"
                + "</serviceStateTable></scpd>";
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
    }
}
