package com.casttoyourtv.receiver.airplay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二进制 plist 读取器（解析 AirPlay SETUP 请求体）。
 * 返回值：Map / List / String / Long / Double / Boolean / byte[]。
 * UID 也映射成 Long（streamConnectionID 就是 UID 编码）。
 */
public final class BplistReader {

    private byte[] data;
    private int offsetSize;
    private int refSize;
    private long numObjects;
    private long topObject;
    private long offsetTableOffset;
    private long[] offsets;

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readDict(byte[] data) {
        Object o = new BplistReader().parse(data);
        return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
    }

    private Object parse(byte[] d) {
        this.data = d;
        if (d.length < 40) throw new IllegalArgumentException("bplist too short");
        int t = d.length - 32; // trailer
        offsetSize = d[t + 6] & 0xFF;
        refSize = d[t + 7] & 0xFF;
        numObjects = readBeInt(t + 8, 8);
        topObject = readBeInt(t + 16, 8);
        offsetTableOffset = readBeInt(t + 24, 8);
        offsets = new long[(int) numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = readBeInt((int) (offsetTableOffset + (long) i * offsetSize), offsetSize);
        }
        return readObject((int) topObject);
    }

    private Object readObject(int ref) {
        int off = (int) offsets[ref];
        int marker = data[off] & 0xFF;
        int type = marker >> 4;
        int info = marker & 0x0F;
        switch (type) {
            case 0x0:
                if (marker == 0x08) return Boolean.FALSE;
                if (marker == 0x09) return Boolean.TRUE;
                return null;
            case 0x1: {
                int len = 1 << info;
                return readBeInt(off + 1, len);
            }
            case 0x2: {
                int len = 1 << info;
                if (len == 4) {
                    return (double) Float.intBitsToFloat((int) readBeInt(off + 1, 4));
                }
                return Double.longBitsToDouble(readBeInt(off + 1, 8));
            }
            case 0x4: { // data
                long[] lenAndNext = readLength(off + 1, info);
                int len = (int) lenAndNext[0];
                byte[] out = new byte[len];
                System.arraycopy(data, (int) lenAndNext[1], out, 0, len);
                return out;
            }
            case 0x5: { // ASCII
                long[] lenAndNext = readLength(off + 1, info);
                return new String(data, (int) lenAndNext[1], (int) lenAndNext[0], StandardCharsets.ISO_8859_1);
            }
            case 0x6: { // UTF-16BE
                long[] lenAndNext = readLength(off + 1, info);
                return new String(data, (int) lenAndNext[1], (int) lenAndNext[0] * 2, StandardCharsets.UTF_16BE);
            }
            case 0x8: { // UID -> Long
                return readBeInt(off + 1, info + 1);
            }
            case 0xA: { // array
                long[] lenAndNext = readLength(off + 1, info);
                int len = (int) lenAndNext[0];
                int p = (int) lenAndNext[1];
                List<Object> list = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readObject((int) readBeInt(p + i * refSize, refSize)));
                }
                return list;
            }
            case 0xD: { // dict
                long[] lenAndNext = readLength(off + 1, info);
                int len = (int) lenAndNext[0];
                int p = (int) lenAndNext[1];
                Map<String, Object> map = new HashMap<>();
                for (int i = 0; i < len; i++) {
                    Object k = readObject((int) readBeInt(p + i * refSize, refSize));
                    Object v = readObject((int) readBeInt(p + (len + i) * refSize, refSize));
                    map.put(String.valueOf(k), v);
                }
                return map;
            }
            default:
                throw new IllegalArgumentException("unsupported bplist type " + type + " at " + off);
        }
    }

    /** info==0xF 时长度是一个内嵌 int 对象；返回 [长度, 内容起始偏移] */
    private long[] readLength(int off, int info) {
        if (info != 0x0F) {
            return new long[]{info, off};
        } else {
            int marker = data[off] & 0xFF;
            int intLen = 1 << (marker & 0x0F);
            return new long[]{readBeInt(off + 1, intLen), off + 1 + intLen};
        }
    }

    private long readBeInt(int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (data[off + i] & 0xFF);
        return v;
    }
}
