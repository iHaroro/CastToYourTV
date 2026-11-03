package com.casttoyourtv.receiver.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 最小二进制 plist（bplist00）写出器。
 * 支持 dict / array / string / uint / real / bool / data，
 * 足够构造 AirPlay /info 应答。对象数须 < 256，总大小须 < 2^24。
 */
public class Bplist {

    public abstract static class N { int idx = -1; }

    public static class Dict extends N {
        final Map<String, N> m = new TreeMap<>();
        final List<Str> keyNodes = new ArrayList<>(); // flatten 时按顺序填充
        final List<N> valNodes = new ArrayList<>();
        public Dict put(String k, N v) { m.put(k, v); return this; }
    }

    public static class Arr extends N {
        final List<N> l = new ArrayList<>();
        public Arr add(N v) { l.add(v); return this; }
    }

    static class Str extends N { final String s; Str(String s) { this.s = s; } }
    static class Int extends N { final long v; Int(long v) { this.v = v; } }
    static class Real extends N { final double v; Real(double v) { this.v = v; } }
    static class Bool extends N { final boolean v; Bool(boolean v) { this.v = v; } }
    static class Data extends N { final byte[] b; Data(byte[] b) { this.b = b; } }

    public static N str(String s) { return new Str(s); }
    public static N i64(long v) { return new Int(v); }
    public static N real(double v) { return new Real(v); }
    public static N bool(boolean v) { return new Bool(v); }
    public static N data(byte[] b) { return new Data(b); }

    public static byte[] write(N root) {
        List<N> objs = new ArrayList<>();
        flatten(root, objs);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = "bplist00".getBytes(StandardCharsets.US_ASCII);
        out.write(header, 0, header.length);

        List<Long> offsets = new ArrayList<>();
        for (N n : objs) {
            offsets.add((long) out.size());
            writeObject(out, n, objs);
        }

        long offsetTableOffset = out.size();
        long maxOffset = offsets.get(offsets.size() - 1);
        int offsetSize = maxOffset < 0x100 ? 1 : maxOffset < 0x10000 ? 2 : maxOffset < 0x1000000 ? 3 : 4;
        for (long off : offsets) {
            writeUint(out, off, offsetSize);
        }

        // 32 字节 trailer
        out.write(new byte[6], 0, 6);
        out.write(offsetSize);
        out.write(1); // objectRefSize：对象数 < 256，1 字节索引
        writeUint(out, objs.size(), 8);
        writeUint(out, 0, 8); // topObject 索引
        writeUint(out, offsetTableOffset, 8);
        return out.toByteArray();
    }

    /** DFS 拍平对象树，根节点索引为 0 */
    private static void flatten(N n, List<N> objs) {
        if (n.idx >= 0) return;
        n.idx = objs.size();
        objs.add(n);
        if (n instanceof Dict) {
            Dict d = (Dict) n;
            for (Map.Entry<String, N> e : d.m.entrySet()) {
                Str key = new Str(e.getKey()); // 每个 key 也是独立对象
                flatten(key, objs);
                flatten(e.getValue(), objs);
                d.keyNodes.add(key);
                d.valNodes.add(e.getValue());
            }
        } else if (n instanceof Arr) {
            for (N c : ((Arr) n).l) flatten(c, objs);
        }
    }

    private static void writeObject(ByteArrayOutputStream out, N n, List<N> objs) {
        if (n instanceof Dict) {
            Dict d = (Dict) n;
            writeMarker(out, 0xD0, d.m.size());
            // 先写全部 key 索引，再写全部 value 索引（索引取自节点自身，与嵌套无关）
            for (Str k : d.keyNodes) out.write(k.idx);
            for (N v : d.valNodes) out.write(v.idx);
        } else if (n instanceof Arr) {
            Arr a = (Arr) n;
            writeMarker(out, 0xA0, a.l.size());
            for (N c : a.l) out.write(c.idx);
        } else if (n instanceof Str) {
            String s = ((Str) n).s;
            boolean ascii = s.chars().allMatch(c -> c < 128);
            if (ascii) {
                writeMarker(out, 0x50, s.length());
                byte[] b = s.getBytes(StandardCharsets.US_ASCII);
                out.write(b, 0, b.length);
            } else {
                writeMarker(out, 0x60, s.length());
                for (char ch : s.toCharArray()) {
                    out.write((ch >> 8) & 0xFF);
                    out.write(ch & 0xFF);
                }
            }
        } else if (n instanceof Int) {
            out.write(0x13); // 8 字节整数
            writeUint(out, ((Int) n).v, 8);
        } else if (n instanceof Real) {
            out.write(0x23); // 8 字节 double
            writeUint(out, Double.doubleToLongBits(((Real) n).v), 8);
        } else if (n instanceof Bool) {
            out.write(((Bool) n).v ? 0x09 : 0x08);
        } else if (n instanceof Data) {
            byte[] b = ((Data) n).b;
            writeMarker(out, 0x40, b.length);
            out.write(b, 0, b.length);
        }
    }

    private static void writeMarker(ByteArrayOutputStream out, int type, int len) {
        if (len < 15) {
            out.write(type | len);
        } else {
            out.write(type | 0x0F);
            if (len < 0x100) {
                out.write(0x10);
                out.write(len);
            } else {
                out.write(0x11);
                writeUint(out, len, 2);
            }
        }
    }

    private static void writeUint(ByteArrayOutputStream out, long v, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) ((v >> (i * 8)) & 0xFF));
        }
    }
}
