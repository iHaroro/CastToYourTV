package ours;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * PlayFair 解密查找表（由 tools/gen_playfair_tables.py 从 UxPlay omg_hax.h 自动生成）。
 */
public final class PlayfairTables {
    public static final String ASSET = "playfair_tables.bin";

    public static final int MESSAGE_KEY_OFF = 0; // 576 entries
    public static final int MESSAGE_IV_OFF = 576; // 64 entries
    public static final int Z_KEY_OFF = 640; // 16 entries
    public static final int X_KEY_OFF = 656; // 16 entries
    public static final int T_KEY_OFF = 672; // 16 entries
    public static final int TABLE_S5_OFF = 688; // 256 entries
    public static final int TABLE_S6_OFF = 1712; // 256 entries
    public static final int TABLE_S7_OFF = 2736; // 256 entries
    public static final int TABLE_S8_OFF = 3760; // 256 entries
    public static final int TABLE_S1_OFF = 4784; // 10240 entries
    public static final int TABLE_S3_OFF = 15024; // 4096 entries
    public static final int TABLE_S4_OFF = 19120; // 36864 entries
    public static final int TABLE_S2_OFF = 55984; // 36864 entries
    public static final int TABLE_S9_OFF = 92848; // 1024 entries
    public static final int TABLE_S10_OFF = 96944; // 4096 entries

    public static final byte[] message_key = new byte[576];
    public static final byte[] message_iv = new byte[64];
    public static final byte[] z_key = new byte[16];
    public static final byte[] x_key = new byte[16];
    public static final byte[] t_key = new byte[16];
    public static final int[] table_s5 = new int[256];
    public static final int[] table_s6 = new int[256];
    public static final int[] table_s7 = new int[256];
    public static final int[] table_s8 = new int[256];
    public static final byte[] table_s1 = new byte[10240];
    public static final byte[] table_s3 = new byte[4096];
    public static final byte[] table_s4 = new byte[36864];
    public static final byte[] table_s2 = new byte[36864];
    public static final int[] table_s9 = new int[1024];
    public static final byte[] table_s10 = new byte[4096];

    private static boolean inited;

    public static synchronized void init(byte[] blob) {
        if (inited) return;
        try {
        System.arraycopy(blob, MESSAGE_KEY_OFF, message_key, 0, 576);
        System.arraycopy(blob, MESSAGE_IV_OFF, message_iv, 0, 64);
        System.arraycopy(blob, Z_KEY_OFF, z_key, 0, 16);
        System.arraycopy(blob, X_KEY_OFF, x_key, 0, 16);
        System.arraycopy(blob, T_KEY_OFF, t_key, 0, 16);
        for (int i = 0; i < 256; i++) table_s5[i] = readWord(blob, TABLE_S5_OFF + i * 4);
        for (int i = 0; i < 256; i++) table_s6[i] = readWord(blob, TABLE_S6_OFF + i * 4);
        for (int i = 0; i < 256; i++) table_s7[i] = readWord(blob, TABLE_S7_OFF + i * 4);
        for (int i = 0; i < 256; i++) table_s8[i] = readWord(blob, TABLE_S8_OFF + i * 4);
        System.arraycopy(blob, TABLE_S1_OFF, table_s1, 0, 10240);
        System.arraycopy(blob, TABLE_S3_OFF, table_s3, 0, 4096);
        System.arraycopy(blob, TABLE_S4_OFF, table_s4, 0, 36864);
        System.arraycopy(blob, TABLE_S2_OFF, table_s2, 0, 36864);
        for (int i = 0; i < 1024; i++) table_s9[i] = readWord(blob, TABLE_S9_OFF + i * 4);
        System.arraycopy(blob, TABLE_S10_OFF, table_s10, 0, 4096);
        } catch (Exception e) {
            throw new IllegalStateException("load playfair_tables failed", e);
        }
        inited = true;
    }

    private static int readWord(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private PlayfairTables() {
    }
}
