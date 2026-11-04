package com.casttoyourtv.receiver.airplay;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取 AirPlay SET_PARAMETER 的 DMAP 标签树。
 * 每个标签为 4 字节 tag + 4 字节大端长度 + payload，mlit/mlcl 等为容器。
 */
final class DmapReader {

    private static final int HEADER_SIZE = 8;
    private static final int MAX_DEPTH = 8;

    private DmapReader() {
    }

    static Map<String, byte[]> read(byte[] data) {
        Map<String, byte[]> values = new LinkedHashMap<>();
        if (data == null || data.length < HEADER_SIZE) return values;
        parseItems(data, 0, data.length, values, 0);
        return values;
    }

    static String text(Map<String, byte[]> values, String tag) {
        byte[] data = values.get(tag);
        if (data == null || data.length == 0) return null;
        String text = new String(data, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
        return text.isEmpty() ? null : text;
    }

    static long uint32(Map<String, byte[]> values, String tag) {
        byte[] data = values.get(tag);
        if (data == null || data.length < 4) return 0;
        return ((long) (data[0] & 0xFF) << 24)
                | ((long) (data[1] & 0xFF) << 16)
                | ((long) (data[2] & 0xFF) << 8)
                | (data[3] & 0xFFL);
    }

    static long uint64(Map<String, byte[]> values, String tag) {
        byte[] data = values.get(tag);
        if (data == null || data.length < 8) return 0;
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[i] & 0xFFL);
        }
        return value;
    }

    private static void parseItems(byte[] data, int start, int end,
                                   Map<String, byte[]> values, int depth) {
        if (depth > MAX_DEPTH) return;
        int pos = start;
        while (pos + HEADER_SIZE <= end) {
            String tag = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            long length = readLength(data, pos + 4);
            pos += HEADER_SIZE;
            if (length < 0 || length > end - pos) return;
            int payloadEnd = pos + (int) length;
            if (isContainer(tag)) {
                parseItems(data, pos, payloadEnd, values, depth + 1);
            } else {
                values.put(tag, Arrays.copyOfRange(data, pos, payloadEnd));
            }
            pos = payloadEnd;
        }
    }

    private static long readLength(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    private static boolean isContainer(String tag) {
        return "mlit".equals(tag) || "mlcl".equals(tag) || "mcon".equals(tag)
                || "aply".equals(tag) || "mdcl".equals(tag);
    }
}
