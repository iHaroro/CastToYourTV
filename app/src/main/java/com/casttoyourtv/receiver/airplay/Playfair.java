package com.casttoyourtv.receiver.airplay;

/**
 * AirPlay FairPlay ekey 解密（移植自 UxPlay lib/playfair/，dsafa22 的公开逆向实现）。
 * 用途：fp-setup 握手后，从 SETUP 请求里的 72 字节 ekey 解出 16 字节视频流 AES 密钥。
 */
public final class Playfair {

    private Playfair() {
    }

    // ===== omg_hax.c 中的固定常量 =====
    private static final byte[] INDEX_MANGLE = {1, 2, 4, 8, 0x10, 0x20, 0x40, (byte) 0x80, 0x1b, 0x36, 0x6c};
    private static final byte[] INITIAL_SESSION_KEY = {
            (byte) 0xdc, (byte) 0xdc, (byte) 0xf3, (byte) 0xb9, 0x0b, 0x74, (byte) 0xdc, (byte) 0xfb,
            (byte) 0x86, 0x7f, (byte) 0xf7, 0x60, 0x16, 0x72, (byte) 0x90, 0x51};
    private static final byte[] STATIC_SOURCE_1 = {
            (byte) 0xfa, (byte) 0x9c, (byte) 0xad, 0x4d, 0x4b, 0x68, 0x26, (byte) 0x8c,
            0x7f, (byte) 0xf3, (byte) 0x88, (byte) 0x99, (byte) 0xde, (byte) 0x92, 0x2e, (byte) 0x95, 0x1e};
    private static final byte[] STATIC_SOURCE_2 = {
            (byte) 0xec, 0x4e, 0x27, 0x5e, (byte) 0xfd, (byte) 0xf2, (byte) 0xe8, 0x30,
            (byte) 0x97, (byte) 0xae, 0x70, (byte) 0xfb, (byte) 0xe0, 0x00, 0x3f, 0x1c,
            0x39, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10,
            0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    private static final byte[] DEFAULT_SAP = {
            0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
            0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
            0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
            0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
            0x79, 0x79, 0x79, 0x79, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x02, 0x53,
            0x00, 0x01, (byte) 0xcc, 0x34, 0x2a, 0x5e, 0x5b, 0x1a, 0x67, 0x73, (byte) 0xc2, 0x0e, 0x21, (byte) 0xb8, 0x22, 0x4d,
            (byte) 0xf8, 0x62, 0x48, 0x18, 0x64, (byte) 0xef, (byte) 0x81, 0x0a, (byte) 0xae, 0x2e, 0x37, 0x03, (byte) 0xc8, (byte) 0x81, (byte) 0x9c, 0x23,
            0x53, (byte) 0x9d, (byte) 0xe5, (byte) 0xf5, (byte) 0xd7, 0x49, (byte) 0xbc, 0x5b, 0x7a, 0x26, 0x6c, 0x49, 0x62, (byte) 0x83, (byte) 0xce, 0x7f,
            0x03, (byte) 0x93, 0x7a, (byte) 0xe1, (byte) 0xf6, 0x16, (byte) 0xde, 0x0c, 0x15, (byte) 0xff, 0x33, (byte) 0x8c, (byte) 0xca, (byte) 0xff, (byte) 0xb0, (byte) 0x9e,
            (byte) 0xaa, (byte) 0xbb, (byte) 0xe4, 0x0f, 0x5d, 0x5f, 0x55, (byte) 0x8f, (byte) 0xb9, 0x7f, 0x17, 0x31, (byte) 0xf8, (byte) 0xf7, (byte) 0xda, 0x60,
            (byte) 0xa0, (byte) 0xec, 0x65, 0x79, (byte) 0xc3, 0x3e, (byte) 0xa9, (byte) 0x83, 0x12, (byte) 0xc3, (byte) 0xb6, 0x71, 0x35, (byte) 0xa6, 0x69, 0x4f,
            (byte) 0xf8, 0x23, 0x05, (byte) 0xd9, (byte) 0xba, 0x5c, 0x61, 0x5f, (byte) 0xa2, 0x54, (byte) 0xd2, (byte) 0xb1, (byte) 0x83, 0x45, (byte) 0x83, (byte) 0xce,
            (byte) 0xe4, 0x2d, 0x44, 0x26, (byte) 0xc8, 0x35, (byte) 0xa7, (byte) 0xa5, (byte) 0xf6, (byte) 0xc8, 0x42, 0x1c, 0x0d, (byte) 0xa3, (byte) 0xf1, (byte) 0xc7,
            0x00, 0x50, (byte) 0xf2, (byte) 0xe5, 0x17, (byte) 0xf8, (byte) 0xd0, (byte) 0xfa, 0x77, (byte) 0x8d, (byte) 0xfb, (byte) 0x82, (byte) 0x8d, 0x40, (byte) 0xc7, (byte) 0x8e,
            (byte) 0x94, 0x1e, 0x1e, 0x1e};

    private static final int[] SAP_I0_INDEX = {18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30};

    /** playfair_decrypt 入口：message3 = fp-setup 第二步的 164 字节 keymsg，cipherText = SETUP 的 72 字节 ekey */
    public static byte[] decrypt(byte[] message3, byte[] ekey) {
        byte[] sapKey = generateSessionKey(DEFAULT_SAP, message3);
        int[][] ks = generateKeySchedule(sapKey);

        byte[] chunk1 = java.util.Arrays.copyOfRange(ekey, 16, 32);
        byte[] chunk2 = java.util.Arrays.copyOfRange(ekey, 56, 72);

        byte[] blockIn = zXor(chunk2, 1);
        cycle(blockIn, ks);
        byte[] keyOut = new byte[16];
        for (int i = 0; i < 16; i++) keyOut[i] = (byte) ((blockIn[i] & 0xFF) ^ (chunk1[i] & 0xFF));
        keyOut = xXor(keyOut, 1);
        keyOut = zXor(keyOut, 1);
        return keyOut;
    }

    // ===== byte helpers（全部把值规整到 0..255，与 C 的 unsigned char 语义一致）=====
    private static int b(byte[] a, int i) { return a[i] & 0xFF; }
    private static int b(int[] a, int i) { return a[i] & 0xFF; }

    private static int rol8(int input, int count) {
        input &= 0xff;
        return ((input << count) & 0xff) | ((input & 0xff) >> (8 - count));
    }

    private static int rol8x(int input, int count) {
        input &= 0xff; // C 参数是 uint8_t，先截断
        return (input << count) | (input >> (8 - count));
    }

    private static int weirdRor8(int input, int count) {
        if (count == 0) return 0;
        input &= 0xff;
        return ((input >> count) & 0xff) | ((input & 0xff) << (8 - count));
    }

    private static int weirdRol8(int input, int count) {
        if (count == 0) return 0;
        input &= 0xff;
        return ((input << count) & 0xff) | ((input & 0xff) >> (8 - count));
    }

    private static int weirdRol32(int input, int count) {
        if (count == 0) return 0;
        input &= 0xff;
        return (input << count) ^ (input >> (8 - count));
    }

    private static int leWord(byte[] a, int off) {
        return (a[off] & 0xFF) | (a[off + 1] & 0xFF) << 8 | (a[off + 2] & 0xFF) << 16 | (a[off + 3] & 0xFF) << 24;
    }

    private static void setLeWord(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >> 8);
        a[off + 2] = (byte) (v >> 16);
        a[off + 3] = (byte) (v >> 24);
    }

    private static int leWord(int[] a, int off) {
        return (a[off] & 0xFF) | (a[off + 1] & 0xFF) << 8 | (a[off + 2] & 0xFF) << 16 | (a[off + 3] & 0xFF) << 24;
    }

    private static void setLeWord(int[] a, int off, int v) {
        a[off] = v & 0xFF;
        a[off + 1] = (v >> 8) & 0xFF;
        a[off + 2] = (v >> 16) & 0xFF;
        a[off + 3] = (v >> 24) & 0xFF;
    }

    private static byte[] zXor(byte[] in, int blocks) {
        byte[] out = new byte[blocks * 16];
        for (int j = 0; j < blocks; j++)
            for (int i = 0; i < 16; i++)
                out[j * 16 + i] = (byte) ((in[j * 16 + i] & 0xFF) ^ (PlayfairTables.z_key[i] & 0xFF));
        return out;
    }

    private static byte[] xXor(byte[] in, int blocks) {
        byte[] out = new byte[blocks * 16];
        for (int j = 0; j < blocks; j++)
            for (int i = 0; i < 16; i++)
                out[j * 16 + i] = (byte) ((in[j * 16 + i] & 0xFF) ^ (PlayfairTables.x_key[i] & 0xFF));
        return out;
    }

    // ===== omg_hax.c: generate_key_schedule =====
    private static int[][] generateKeySchedule(byte[] keyMaterial) {
        int[] buffer = new int[16];
        for (int i = 0; i < 16; i++) {
            buffer[i] = (keyMaterial[i] & 0xFF) ^ (PlayfairTables.t_key[i] & 0xFF);
        }
        int[][] ks = new int[11][4];
        int ti = 0;
        for (int round = 0; round < 11; round++) {
            ks[round][0] = leWord(buffer, 0);
            int[] t1 = s1Table(ti);
            int[] t2 = s1Table(ti + 1);
            int[] t3 = s1Table(ti + 2);
            int[] t4 = s1Table(ti + 3);
            ti += 4;
            buffer[0] = (buffer[0] ^ t1[buffer[0x0d]] ^ (INDEX_MANGLE[round] & 0xFF)) & 0xFF;
            buffer[1] = (buffer[1] ^ t2[buffer[0x0e]]) & 0xFF;
            buffer[2] = (buffer[2] ^ t3[buffer[0x0f]]) & 0xFF;
            buffer[3] = (buffer[3] ^ t4[buffer[0x0c]]) & 0xFF;
            ks[round][1] = leWord(buffer, 4);
            setLeWord(buffer, 4, leWord(buffer, 4) ^ leWord(buffer, 0));
            ks[round][2] = leWord(buffer, 8);
            setLeWord(buffer, 8, leWord(buffer, 8) ^ leWord(buffer, 4));
            ks[round][3] = leWord(buffer, 12);
            setLeWord(buffer, 12, leWord(buffer, 12) ^ leWord(buffer, 8));
        }
        return ks;
    }

    /** table_index(i) = table_s1[((31*i)%40)<<8 .. +256] 的 256 字节子表 */
    private static int[] s1Table(int i) {
        int off = ((31 * i) % 40) << 8;
        int[] t = new int[256];
        for (int k = 0; k < 256; k++) t[k] = PlayfairTables.table_s1[off + k] & 0xFF;
        return t;
    }

    // ===== omg_hax.c: cycle =====
    private static void cycle(byte[] block, int[][] ks) {
        int[] b = new int[16];
        for (int i = 0; i < 16; i++) b[i] = block[i] & 0xFF;
        for (int w = 0; w < 4; w++) setLeWord(b, w * 4, leWord(b, w * 4) ^ ks[10][w]);
        permuteBlock1(b);
        for (int round = 0; round < 9; round++) {
            int k0 = ks[9 - round][0];
            int ab = PlayfairTables.table_s5[b[3] ^ ((k0 >> 24) & 0xFF)]
                    ^ PlayfairTables.table_s6[b[2] ^ ((k0 >> 16) & 0xFF)]
                    ^ PlayfairTables.table_s8[b[0] ^ (k0 & 0xFF)]
                    ^ PlayfairTables.table_s7[b[1] ^ ((k0 >> 8) & 0xFF)];
            setLeWord(b, 0, ab);

            int k1 = ks[9 - round][1];
            ab = PlayfairTables.table_s5[b[7] ^ ((k1 >> 24) & 0xFF)]
                    ^ PlayfairTables.table_s6[b[6] ^ ((k1 >> 16) & 0xFF)]
                    ^ PlayfairTables.table_s7[b[5] ^ ((k1 >> 8) & 0xFF)]
                    ^ PlayfairTables.table_s8[b[4] ^ (k1 & 0xFF)];
            setLeWord(b, 4, ab);

            int k2 = ks[9 - round][2];
            setLeWord(b, 8, PlayfairTables.table_s5[b[11] ^ ((k2 >> 24) & 0xFF)]
                    ^ PlayfairTables.table_s6[b[10] ^ ((k2 >> 16) & 0xFF)]
                    ^ PlayfairTables.table_s7[b[9] ^ ((k2 >> 8) & 0xFF)]
                    ^ PlayfairTables.table_s8[b[8] ^ (k2 & 0xFF)]);

            int k3 = ks[9 - round][3];
            setLeWord(b, 12, PlayfairTables.table_s5[b[15] ^ ((k3 >> 24) & 0xFF)]
                    ^ PlayfairTables.table_s6[b[14] ^ ((k3 >> 16) & 0xFF)]
                    ^ PlayfairTables.table_s7[b[13] ^ ((k3 >> 8) & 0xFF)]
                    ^ PlayfairTables.table_s8[b[12] ^ (k3 & 0xFF)]);

            permuteBlock2(b, 8 - round);
        }
        for (int w = 0; w < 4; w++) setLeWord(b, w * 4, leWord(b, w * 4) ^ ks[0][w]);
        for (int i = 0; i < 16; i++) block[i] = (byte) b[i];
    }

    private static void permuteBlock1(int[] block) {
        byte[] s3 = PlayfairTables.table_s3;
        block[0] = s3[block[0]] & 0xFF;
        block[4] = s3[0x400 + block[4]] & 0xFF;
        block[8] = s3[0x800 + block[8]] & 0xFF;
        block[12] = s3[0xc00 + block[12]] & 0xFF;

        int tmp = block[13];
        block[13] = s3[0x100 + block[9]] & 0xFF;
        block[9] = s3[0xd00 + block[5]] & 0xFF;
        block[5] = s3[0x900 + block[1]] & 0xFF;
        block[1] = s3[0x500 + tmp] & 0xFF;

        tmp = block[2];
        block[2] = s3[0xa00 + block[10]] & 0xFF;
        block[10] = s3[0x200 + tmp] & 0xFF;
        tmp = block[6];
        block[6] = s3[0xe00 + block[14]] & 0xFF;
        block[14] = s3[0x600 + tmp] & 0xFF;

        tmp = block[3];
        block[3] = s3[0xf00 + block[7]] & 0xFF;
        block[7] = s3[0x300 + block[11]] & 0xFF;
        block[11] = s3[0x700 + block[15]] & 0xFF;
        block[15] = s3[0xb00 + tmp] & 0xFF;
    }

    /** permute_table_2(i) = table_s4[((71*i)%144)<<8 .. +256] */
    private static void permuteBlock2(int[] block, int round) {
        byte[] s4 = PlayfairTables.table_s4;
        block[0] = s4[s4Idx(round * 16 + 0) + block[0]] & 0xFF;
        block[4] = s4[s4Idx(round * 16 + 4) + block[4]] & 0xFF;
        block[8] = s4[s4Idx(round * 16 + 8) + block[8]] & 0xFF;
        block[12] = s4[s4Idx(round * 16 + 12) + block[12]] & 0xFF;

        int tmp = block[13];
        block[13] = s4[s4Idx(round * 16 + 13) + block[9]] & 0xFF;
        block[9] = s4[s4Idx(round * 16 + 9) + block[5]] & 0xFF;
        block[5] = s4[s4Idx(round * 16 + 5) + block[1]] & 0xFF;
        block[1] = s4[s4Idx(round * 16 + 1) + tmp] & 0xFF;

        tmp = block[2];
        block[2] = s4[s4Idx(round * 16 + 2) + block[10]] & 0xFF;
        block[10] = s4[s4Idx(round * 16 + 10) + tmp] & 0xFF;
        tmp = block[6];
        block[6] = s4[s4Idx(round * 16 + 6) + block[14]] & 0xFF;
        block[14] = s4[s4Idx(round * 16 + 14) + tmp] & 0xFF;

        tmp = block[3];
        block[3] = s4[s4Idx(round * 16 + 3) + block[7]] & 0xFF;
        block[7] = s4[s4Idx(round * 16 + 7) + block[11]] & 0xFF;
        block[11] = s4[s4Idx(round * 16 + 11) + block[15]] & 0xFF;
        block[15] = s4[s4Idx(round * 16 + 15) + tmp] & 0xFF;
    }

    private static int s4Idx(int i) {
        return ((71 * i) % 144) << 8;
    }

    /** message_table_index(i) = table_s2[((97*i)%144)<<8 .. +256] */
    private static int s2Idx(int i) {
        return ((97 * i) % 144) << 8;
    }

    // ===== omg_hax.c: decryptMessage（SETUP1 ekey 前的 164B keymsg 解出 128B 明文）=====
    private static void decryptMessage(byte[] messageIn, byte[] decryptedMessage) {
        int[] buffer = new int[16];
        int mode = messageIn[12] & 0xFF;
        byte[] s2 = PlayfairTables.table_s2;
        int[] s9 = PlayfairTables.table_s9;
        byte[] s10 = PlayfairTables.table_s10;
        byte[] mkey = PlayfairTables.message_key; // [4][144]
        byte[] miv = PlayfairTables.message_iv;   // [4][16]

        for (int i = 0; i < 8; i++) {
            // mode==3 从后往前取块，其余正序（跳过 16 字节头）
            for (int j = 0; j < 16; j++) {
                if (mode == 3) buffer[j] = messageIn[(0x80 - 0x10 * i) + j] & 0xFF;
                else buffer[j] = messageIn[0x10 * (i + 1) + j] & 0xFF;
            }
            for (int j = 0; j < 9; j++) {
                int base = 0x80 - 0x10 * j;
                int mk = mode * 144 + base;
                buffer[0x0] = (s2[s2Idx(base) + buffer[0x0]] & 0xFF) ^ (mkey[mk] & 0xFF);
                buffer[0x4] = (s2[s2Idx(base + 0x4) + buffer[0x4]] & 0xFF) ^ (mkey[mk + 0x4] & 0xFF);
                buffer[0x8] = (s2[s2Idx(base + 0x8) + buffer[0x8]] & 0xFF) ^ (mkey[mk + 0x8] & 0xFF);
                buffer[0xc] = (s2[s2Idx(base + 0xc) + buffer[0xc]] & 0xFF) ^ (mkey[mk + 0xc] & 0xFF);

                int tmp = buffer[0x0d];
                buffer[0xd] = (s2[s2Idx(base + 0xd) + buffer[0x9]] & 0xFF) ^ (mkey[mk + 0xd] & 0xFF);
                buffer[0x9] = (s2[s2Idx(base + 0x9) + buffer[0x5]] & 0xFF) ^ (mkey[mk + 0x9] & 0xFF);
                buffer[0x5] = (s2[s2Idx(base + 0x5) + buffer[0x1]] & 0xFF) ^ (mkey[mk + 0x5] & 0xFF);
                buffer[0x1] = (s2[s2Idx(base + 0x1) + tmp] & 0xFF) ^ (mkey[mk + 0x1] & 0xFF);

                tmp = buffer[0x02];
                buffer[0x2] = (s2[s2Idx(base + 0x2) + buffer[0xa]] & 0xFF) ^ (mkey[mk + 0x2] & 0xFF);
                buffer[0xa] = (s2[s2Idx(base + 0xa) + tmp] & 0xFF) ^ (mkey[mk + 0xa] & 0xFF);
                tmp = buffer[0x06];
                buffer[0x6] = (s2[s2Idx(base + 0x6) + buffer[0xe]] & 0xFF) ^ (mkey[mk + 0x6] & 0xFF);
                buffer[0xe] = (s2[s2Idx(base + 0xe) + tmp] & 0xFF) ^ (mkey[mk + 0xe] & 0xFF);

                tmp = buffer[0x3];
                buffer[0x3] = (s2[s2Idx(base + 0x3) + buffer[0x7]] & 0xFF) ^ (mkey[mk + 0x3] & 0xFF);
                buffer[0x7] = (s2[s2Idx(base + 0x7) + buffer[0xb]] & 0xFF) ^ (mkey[mk + 0x7] & 0xFF);
                buffer[0xb] = (s2[s2Idx(base + 0xb) + buffer[0xf]] & 0xFF) ^ (mkey[mk + 0xb] & 0xFF);
                buffer[0xf] = (s2[s2Idx(base + 0xf) + tmp] & 0xFF) ^ (mkey[mk + 0xf] & 0xFF);

                // 4 个 LE 字 = table_s9 四路 XOR（顺序写，后续字读的字节未被覆盖，与 C 一致）
                setLeWord(buffer, 0, s9[buffer[0x0]] ^ s9[0x100 + buffer[0x1]]
                        ^ s9[0x200 + buffer[0x2]] ^ s9[0x300 + buffer[0x3]]);
                setLeWord(buffer, 4, s9[buffer[0x4]] ^ s9[0x100 + buffer[0x5]]
                        ^ s9[0x200 + buffer[0x6]] ^ s9[0x300 + buffer[0x7]]);
                setLeWord(buffer, 8, s9[buffer[0x8]] ^ s9[0x100 + buffer[0x9]]
                        ^ s9[0x200 + buffer[0xa]] ^ s9[0x300 + buffer[0xb]]);
                setLeWord(buffer, 12, s9[buffer[0xc]] ^ s9[0x100 + buffer[0xd]]
                        ^ s9[0x200 + buffer[0xe]] ^ s9[0x300 + buffer[0xf]]);
            }
            // table_s10 置换（结构同 permute_block_1）
            buffer[0x0] = s10[buffer[0x0]] & 0xFF;
            buffer[0x4] = s10[0x400 + buffer[0x4]] & 0xFF;
            buffer[0x8] = s10[0x800 + buffer[0x8]] & 0xFF;
            buffer[0xc] = s10[0xc00 + buffer[0xc]] & 0xFF;

            int tmp = buffer[0x0d];
            buffer[0xd] = s10[0xd00 + buffer[0x9]] & 0xFF;
            buffer[0x9] = s10[0x900 + buffer[0x5]] & 0xFF;
            buffer[0x5] = s10[0x500 + buffer[0x1]] & 0xFF;
            buffer[0x1] = s10[0x100 + tmp] & 0xFF;

            tmp = buffer[0x02];
            buffer[0x2] = s10[0x200 + buffer[0xa]] & 0xFF;
            buffer[0xa] = s10[0xa00 + tmp] & 0xFF;
            tmp = buffer[0x06];
            buffer[0x6] = s10[0x600 + buffer[0xe]] & 0xFF;
            buffer[0xe] = s10[0xe00 + tmp] & 0xFF;

            tmp = buffer[0x3];
            buffer[0x3] = s10[0x300 + buffer[0x7]] & 0xFF;
            buffer[0x7] = s10[0x700 + buffer[0xb]] & 0xFF;
            buffer[0xb] = s10[0xb00 + buffer[0xf]] & 0xFF;
            buffer[0xf] = s10[0xf00 + tmp] & 0xFF;

            // 与前一块（或 iv）CBC 异或写入输出
            if (mode != 3) {
                int outOff = 0x10 * i;
                if (i > 0) {
                    for (int k = 0; k < 16; k++)
                        decryptedMessage[outOff + k] = (byte) (buffer[k] ^ (messageIn[0x10 * i + k] & 0xFF));
                } else {
                    for (int k = 0; k < 16; k++)
                        decryptedMessage[k] = (byte) (buffer[k] ^ (miv[mode * 16 + k] & 0xFF));
                }
            } else {
                int outOff = 0x70 - 0x10 * i;
                if (i < 7) {
                    for (int k = 0; k < 16; k++)
                        decryptedMessage[outOff + k] = (byte) (buffer[k] ^ (messageIn[outOff + k] & 0xFF));
                } else {
                    for (int k = 0; k < 16; k++)
                        decryptedMessage[outOff + k] = (byte) (buffer[k] ^ (miv[mode * 16 + k] & 0xFF));
                }
            }
        }
    }

    // ===== omg_hax.c: generate_session_key =====
    private static byte[] generateSessionKey(byte[] oldSap, byte[] messageIn) {
        byte[] decryptedMessage = new byte[128];
        decryptMessage(messageIn, decryptedMessage);
        byte[] newSap = new byte[320];
        System.arraycopy(STATIC_SOURCE_1, 0, newSap, 0x000, 0x11);
        System.arraycopy(decryptedMessage, 0, newSap, 0x011, 0x80);
        System.arraycopy(oldSap, 0x80, newSap, 0x091, 0x80);
        System.arraycopy(STATIC_SOURCE_2, 0, newSap, 0x111, 0x2f);
        byte[] sessionKey = INITIAL_SESSION_KEY.clone();
        byte[] md5 = new byte[16];
        for (int round = 0; round < 5; round++) {
            byte[] base = java.util.Arrays.copyOfRange(newSap, round * 64, round * 64 + 64);
            modifiedMd5(base, sessionKey, md5);
            sapHash(base, sessionKey); // 原地覆写 sessionKey
            for (int i = 0; i < 4; i++)
                setLeWord(sessionKey, i * 4, leWord(sessionKey, i * 4) + leWord(md5, i * 4));
        }
        // 每 4 字节组内反转
        for (int i = 0; i < 16; i += 4) {
            byte t = sessionKey[i];
            sessionKey[i] = sessionKey[i + 3];
            sessionKey[i + 3] = t;
            t = sessionKey[i + 1];
            sessionKey[i + 1] = sessionKey[i + 2];
            sessionKey[i + 2] = t;
        }
        for (int i = 0; i < 16; i++) sessionKey[i] ^= 121;
        return sessionKey;
    }

    // ===== modified_md5.c =====
    private static final int[] MD5_SHIFT = {
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

    private static void modifiedMd5(byte[] originalBlockIn, byte[] keyIn, byte[] keyOut) {
        byte[] blockIn = originalBlockIn.clone(); // i==31 时会原地换字
        int A = leWord(keyIn, 0), B = leWord(keyIn, 4), C = leWord(keyIn, 8), D = leWord(keyIn, 12);
        for (int i = 0; i < 64; i++) {
            int j;
            if (i < 16) j = i;
            else if (i < 32) j = (5 * i + 1) % 16;
            else if (i < 48) j = (3 * i + 5) % 16;
            else j = (7 * i) % 16;
            // C 里 input 按大端拼装
            int input = (blockIn[4 * j] & 0xFF) << 24 | (blockIn[4 * j + 1] & 0xFF) << 16
                    | (blockIn[4 * j + 2] & 0xFF) << 8 | (blockIn[4 * j + 3] & 0xFF);
            int kConst = (int) (long) (4294967296.0 * Math.abs(Math.sin(i + 1)));
            int Z = A + input + kConst;
            if (i < 16) Z = Integer.rotateLeft(Z + ((B & C) | (~B & D)), MD5_SHIFT[i]);
            else if (i < 32) Z = Integer.rotateLeft(Z + ((B & D) | (C & ~D)), MD5_SHIFT[i]);
            else if (i < 48) Z = Integer.rotateLeft(Z + (B ^ C ^ D), MD5_SHIFT[i]);
            else Z = Integer.rotateLeft(Z + (C ^ (B | ~D)), MD5_SHIFT[i]);
            Z = Z + B;
            int tmp = D;
            D = C;
            C = B;
            B = Z;
            A = tmp;
            if (i == 31) {
                swapWords(blockIn, A & 15, B & 15);
                swapWords(blockIn, C & 15, D & 15);
                swapWords(blockIn, (A & (15 << 4)) >> 4, (B & (15 << 4)) >> 4);
                swapWords(blockIn, (A & (15 << 8)) >> 8, (B & (15 << 8)) >> 8);
                swapWords(blockIn, (A & (15 << 12)) >> 12, (B & (15 << 12)) >> 12);
            }
        }
        setLeWord(keyOut, 0, leWord(keyIn, 0) + A);
        setLeWord(keyOut, 4, leWord(keyIn, 4) + B);
        setLeWord(keyOut, 8, leWord(keyIn, 8) + C);
        setLeWord(keyOut, 12, leWord(keyIn, 12) + D);
    }

    private static void swapWords(byte[] block, int wa, int wb) {
        for (int k = 0; k < 4; k++) {
            byte t = block[wa * 4 + k];
            block[wa * 4 + k] = block[wb * 4 + k];
            block[wb * 4 + k] = t;
        }
    }

    // ===== sap_hash.c =====
    private static void sapHash(byte[] blockIn, byte[] keyOut) {
        int[] buffer0 = {0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE,
                0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F};
        int[] buffer1 = new int[210];
        int[] buffer2 = {0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56, 0xF6, 0x1C,
                0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1, 0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E,
                0xFE, 0x44, 0xAF, 0xDE, 0xC3, 0x20, 0x9D, 0x42, 0x3A};
        int[] buffer3 = new int[132];
        int[] buffer4 = {0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11,
                0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7};

        // 填充 buffer1：按 LE 字读后取大端字节序
        for (int i = 0; i < 210; i++) {
            int inWord = leWord(blockIn, ((i % 64) >> 2) * 4);
            buffer1[i] = (inWord >>> ((3 - (i % 4)) << 3)) & 0xff;
        }
        // 840 轮混淆（C 用无符号 32 位取模，用 long 复现）
        for (int i = 0; i < 840; i++) {
            int x = buffer1[(int) (((i - 155) & 0xffffffffL) % 210)];
            int y = buffer1[(int) (((i - 57) & 0xffffffffL) % 210)];
            int z = buffer1[(int) (((i - 13) & 0xffffffffL) % 210)];
            int w = buffer1[(int) ((i & 0xffffffffL) % 210)];
            buffer1[i % 210] = (rol8(y, 5) + (rol8(z, 3) ^ w) - rol8(x, 7)) & 0xff;
        }
        garble(buffer0, buffer1, buffer2, buffer3, buffer4);

        for (int i = 0; i < 16; i++) keyOut[i] = (byte) 0xE1;
        for (int i = 0; i < 11; i++) {
            if (i == 3) keyOut[i] = 0x3d;
            else keyOut[i] = (byte) (((keyOut[i] & 0xFF) + buffer3[SAP_I0_INDEX[i] * 4]) & 0xff);
        }
        for (int i = 0; i < 20; i++) keyOut[i % 16] ^= (byte) buffer0[i];
        for (int i = 0; i < 35; i++) keyOut[i % 16] ^= (byte) buffer2[i];
        for (int i = 0; i < 210; i++) keyOut[i % 16] ^= (byte) buffer1[i];

        // 16x16 反向混淆（原地，读写顺序与 C 一致）
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                int x = keyOut[(int) (((i - 7) & 0xffffffffL) % 16)] & 0xFF;
                int y = keyOut[i] & 0xFF;
                int z = keyOut[(int) (((i - 37) & 0xffffffffL) % 16)] & 0xFF;
                int w = keyOut[(int) (((i - 177) & 0xffffffffL) % 16)] & 0xFF;
                keyOut[i] = (byte) (rol8(x, 1) ^ y ^ rol8(z, 6) ^ rol8(w, 5));
            }
        }
    }

    // ===== hand_garble.c：逐行移植，所有写回都 & 0xFF（C 的 unsigned char 截断语义）=====
    private static void garble(int[] b0, int[] b1, int[] b2, int[] b3, int[] b4) {
        int tmp, tmp2, tmp3;
        int A, B, C, D, E, M, J, G, F, H, K, R, S, T, U, V, W, X, Y, Z;

        b2[12] = (0x14 + (((b1[64] & 92) | ((b1[99] / 3) & 35)) & b4[rol8x(b4[b1[206] % 21], 4) % 21])) & 0xFF;
        b1[4] = ((b1[99] / 5) * (b1[99] / 5) * 2) & 0xFF;
        b2[34] = 0xb8;
        b1[153] = (b1[153] ^ (b2[b1[203] % 35] * b2[b1[203] % 35] * b1[190])) & 0xFF;
        b0[3] = (b0[3] - (((b4[b1[205] % 21] >> 1) & 80) | 0xe6440)) & 0xFF;
        b0[16] = 0x93;
        b0[13] = 0x62;
        b1[33] = (b1[33] - (b4[b1[36] % 21] & 0xf6)) & 0xFF;
        tmp2 = b2[b1[67] % 35];
        b2[12] = 0x07;
        tmp = b0[b1[181] % 20];
        b1[2] = (b1[2] - 3136) & 0xFF;
        b0[19] = b4[b1[58] % 21];
        b3[0] = (92 - b2[b1[32] % 35]) & 0xFF;
        b3[4] = (b2[b1[15] % 35] + 0x9e) & 0xFF;
        b1[34] = (b1[34] + (b4[((b2[b1[15] % 35] + 0x9e) & 0xff) % 21] / 5)) & 0xFF;
        // C 中 0xfffffee6 是无符号数，Java 用等位值的 -282
        b0[19] = (b0[19] + (-282 - ((b0[b3[4] % 20] >> 1) & 102))) & 0xFF;
        b1[15] = ((3 * (((b1[72] >> (b4[b1[190] % 21] & 7)) ^ (b1[72] << ((7 - (b4[b1[190] % 21] - 1)) & 7)))
                - (3 * b4[b1[126] % 21]))) ^ b1[15]) & 0xFF;
        b0[15] = (b0[15] ^ (b2[b1[181] % 35] * b2[b1[181] % 35] * b2[b1[181] % 35])) & 0xFF;
        b2[4] = (b2[4] ^ (b1[202] / 3)) & 0xFF;
        A = 92 - b0[b3[0] % 20];
        E = (A & 0xc6) | (~b1[105] & 0xc6) | (A & (~b1[105]));
        b2[1] = (b2[1] + (E * E * E)) & 0xFF;
        b0[19] = (b0[19] ^ (((224 | (b4[b1[92] % 21] & 27)) * b2[b1[41] % 35]) / 3)) & 0xFF;
        b1[140] = (b1[140] + weirdRor8(92, b1[5] & 7)) & 0xFF;
        b2[12] = (b2[12] + (((((~b1[4]) ^ b2[b1[12] % 35]) | b1[182]) & 192)
                | (((~b1[4]) ^ b2[b1[12] % 35]) & b1[182]))) & 0xFF;
        b1[36] = (b1[36] + 125) & 0xFF;
        b1[124] = rol8x((((74 & b1[138]) | ((74 | b1[138]) & b0[15])) & b0[b1[43] % 20])
                | (((74 & b1[138]) | ((74 | b1[138]) & b0[15]) | b0[b1[43] % 20]) & 95), 4) & 0xFF;
        b3[8] = ((((b0[b3[4] % 20] & 95) & ((b4[b1[68] % 21] & 46) << 1)) | 16) ^ 92) & 0xFF;
        A = b1[177] + b4[b1[79] % 21];
        D = (((A >> 1) | ((3 * b1[148]) / 5)) & b2[1]) | ((A >> 1) & ((3 * b1[148]) / 5));
        b3[12] = (-34 - D) & 0xFF;
        A = 8 - (b2[22] & 7);
        B = (b1[33] >> (A & 7));
        C = b1[33] << (b2[22] & 7);
        b2[16] = (b2[16] + (((b2[b3[0] % 35] & 159) | b0[b3[4] % 20] | 8) - ((B ^ C) | 128))) & 0xFF;
        b0[14] = (b0[14] ^ b2[b3[12] % 35]) & 0xFF;
        A = weirdRol8(b4[b0[b1[201] % 20] % 21], ((b2[b1[112] % 35] << 1) & 7));
        D = (b0[b1[208] % 20] & 131) | (b0[b1[164] % 20] & 124);
        b1[19] = (b1[19] + ((A & (D / 5)) | ((A | (D / 5)) & 37))) & 0xFF;
        b2[8] = weirdRor8(140, ((b4[b1[45] % 21] + 92) * (b4[b1[45] % 21] + 92)) & 7) & 0xFF;
        b1[190] = 56;
        b2[8] = (b2[8] ^ b3[0]) & 0xFF;
        b1[53] = (~((b0[b1[83] % 20] | 204) / 5)) & 0xFF;
        b0[13] = (b0[13] + b0[b1[41] % 20]) & 0xFF;
        b0[10] = (((b2[b3[0] % 35] & b1[2]) | ((b2[b3[0] % 35] | b1[2]) & b3[12])) / 15) & 0xFF;
        A = (((56 | (b4[b1[2] % 21] & 68)) | b2[b3[8] % 35]) & 42) | (((b4[b1[2] % 21] & 68) | 56) & b2[b3[8] % 35]);
        b3[16] = ((A * A) + 110) & 0xFF;
        b3[20] = (202 - b3[16]) & 0xFF;
        b3[24] = b1[151];
        b2[13] = (b2[13] ^ b4[b3[0] % 21]) & 0xFF;
        B = ((b2[b1[179] % 35] - 38) & 177) | (b3[12] & 177);
        C = (b2[b1[179] % 35] - 38) & b3[12];
        b3[28] = (30 + ((B | C) * (B | C))) & 0xFF;
        b3[32] = (b3[28] + 62) & 0xFF;
        A = ((b3[20] + (b3[0] & 74)) | ~b4[b3[0] % 21]) & 121;
        B = ((b3[20] + (b3[0] & 74)) & ~b4[b3[0] % 21]);
        tmp3 = (A | B);
        // C 中 0xffffffa6 的等位值是 -90
        C = ((((A | B) ^ -90) | b3[0]) & 4) | (((A | B) ^ -90) & b3[0]);
        b1[47] = ((b2[b1[89] % 35] + C) ^ b1[47]) & 0xFF;
        b3[36] = (((rol8((tmp & 179) + 68, 2) & b0[3]) | (tmp2 & ~b0[3])) - 15) & 0xFF;
        b1[123] = (b1[123] ^ 221) & 0xFF;
        A = (b4[b3[0] % 21] / 3) - b2[b3[4] % 35];
        C = (((b3[0] & 163) + 92) & 246) | (b3[0] & 92);
        E = ((C | b3[24]) & 54) | (C & b3[24]);
        b3[40] = (A - E) & 0xFF;
        b3[44] = (tmp3 ^ 81 ^ (((b3[0] >> 1) & 101) + 26)) & 0xFF;
        b3[48] = b2[b3[4] % 35] & 27;
        b3[52] = 27;
        b3[56] = 199;
        b3[64] = (b3[4] + (((((((b3[40] | b3[24]) & 177) | (b3[40] & b3[24]))
                & ((((b4[b3[0] % 20] & 177) | 176)) | (b4[b3[0] % 21] & ~3)))
                | ((((b3[40] & b3[24]) | ((b3[40] | b3[24]) & 177)) & 199)
                | ((((b4[b3[0] % 21] & 1) + 176) | (b4[b3[0] % 21] & ~3)) & b3[56])))
                & (~b3[52])) | b3[48])) & 0xFF;
        b2[33] = (b2[33] ^ b1[26]) & 0xFF;
        b1[106] = (b1[106] ^ b3[20] ^ 133) & 0xFF;
        b2[30] = (((b3[64] / 3) - (275 | (b3[0] & 247))) ^ b0[b1[122] % 20]) & 0xFF;
        b1[22] = ((b2[b1[90] % 35] & 95) | 68) & 0xFF;
        A = (b4[b3[36] % 21] & 184) | (b2[b3[44] % 35] & ~184);
        b2[18] = (b2[18] + ((A * A * A) >> 1)) & 0xFF;
        b2[5] = (b2[5] - b4[b1[92] % 21]) & 0xFF;
        A = (((b1[41] & ~24) | (b2[b1[183] % 35] & 24)) & (b3[16] + 53)) | (b3[20] & b2[b3[20] % 35]);
        B = (b1[17] & (~b3[44])) | (b0[b1[59] % 20] & b3[44]);
        b2[18] = (b2[18] ^ (A * B)) & 0xFF;
        A = weirdRor8(b1[11], b2[b1[28] % 35] & 7) & 7;
        B = (((b0[b1[93] % 20] & ~b0[14]) | (b0[14] & 150)) & ~28) | (b1[7] & 28);
        b2[22] = ((((B | weirdRol8(b2[b3[0] % 35], A)) & b2[33]) | (B & weirdRol8(b2[b3[0] % 35], A))) + 74) & 0xff;
        A = b4[(b0[b1[39] % 20] ^ 217) % 21];
        b0[15] = (b0[15] - (((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0])) & A)
                | ((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0]) | A) & b3[32]))) & 0xFF;

        // T 的计算
        B = (((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b0[b3[64] % 20] | b2[b1[57] % 35]) & 95)
                | (b3[64] & 45) | 82) & 32);
        C = ((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b2[b1[57] % 35] | b0[b3[64] % 20]) & 95))
                & ((b3[64] & 45) | 82);
        D = (((b3[0] / 3) - (b3[64] | b1[22]))) ^ (b3[28] + 62) ^ (B | C);
        T = b0[(D & 0xff) % 20];

        b3[68] = ((b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20])
                | b2[b3[64] % 35]) & 0xFF;
        U = b0[b1[50] % 20];
        W = b2[b1[138] % 35];
        X = b4[b1[39] % 21];
        Y = b0[b1[4] % 20];
        Z = b4[b1[202] % 21];
        V = b0[b1[151] % 20];
        S = b2[b1[14] % 35];
        R = b0[b1[145] % 20];

        A = (b2[b3[68] % 35] & b0[b1[209] % 20]) | ((b2[b3[68] % 35] | b0[b1[209] % 20]) & 24);
        B = weirdRol8(b4[b1[127] % 21], b2[b3[68] % 35] & 7);
        C = (A & b0[10]) | (B & ~b0[10]);
        D = 7 ^ (b4[b2[b3[36] % 35] % 21] << 1);
        b3[72] = ((C & 71) | (D & ~71)) & 0xFF;
        b2[2] = (b2[2] + ((((b0[b3[20] % 20] << 1) & 159) | (b4[b1[190] % 21] & ~159))
                & ((((b4[b3[64] % 21] & 110) | (b0[b1[25] % 20] & ~110)) & ~150) | (b1[25] & 150)))) & 0xFF;
        b2[14] = (b2[14] - (((b2[b3[20] % 35] & (b3[72] ^ b2[b1[100] % 35])) & ~34) | (b1[97] & 34))) & 0xFF;
        b0[17] = 115;
        b1[23] = (b1[23] ^ ((((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72])
                | (b4[b1[17] % 21] & b0[b3[20] % 20])) & (b1[50] / 3))
                | ((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72])
                | (b4[b1[17] % 21] & b0[b3[20] % 20]) | (b1[50] / 3)) & 246)) << 1)) & 0xFF;
        b0[13] = ((((((b0[b3[40] % 20] | b1[10]) & 82) | (b0[b3[40] % 20] & b1[10])) & 209)
                | ((b0[b1[39] % 20] << 1) & 46)) >> 1);
        b2[33] = (b2[33] - (b1[113] & 9)) & 0xFF;
        b2[28] = (b2[28] - ((((2 | (b1[110] & 222)) >> 1) & ~223) | (b3[20] & 223))) & 0xFF;
        J = weirdRol8((V | Z), (U & 7));
        A = (b2[16] & T) | (W & (~b2[16]));
        B = (b1[33] & 17) | (X & ~17);
        E = ((Y | ((A + B) / 5)) & 147) | (Y & ((A + B) / 5));
        M = (b3[40] & b4[((b3[8] + J + E) & 0xff) % 21])
                | ((b3[40] | b4[((b3[8] + J + E) & 0xff) % 21]) & b2[23]);
        b0[15] = ((((b4[b3[20] % 21] - 48) & (~b1[184])) | ((b4[b3[20] % 21] - 48) & 189)
                | (189 & ~b1[184])) & (M * M * M)) & 0xFF;
        b2[22] = (b2[22] + b1[183]) & 0xFF;
        b3[76] = ((3 * b4[b1[1] % 21]) ^ b3[0]) & 0xFF;
        A = b2[((b3[8] + (J + E)) & 0xff) % 35];
        F = (((b4[b1[178] % 21] & A) | ((b4[b1[178] % 21] | A) & 209)) * b0[b1[13] % 20])
                * (b4[b1[26] % 21] >> 1);
        G = (F + 0x733ffff9) * 198 - (((F + 0x733ffff9) * 396 + 212) & 212) + 85;
        b3[80] = (b3[36] + (G ^ 148) + ((G ^ 107) << 1) - 127) & 0xFF;
        b3[84] = (b2[b3[64] % 35] & 245) | (b2[b3[20] % 35] & 10);
        A = b0[b3[68] % 20] | 81;
        b2[18] = (b2[18] - (((A * A * A) & ~b0[15]) | ((b3[80] / 15) & b0[15]))) & 0xFF;
        b3[88] = (b3[8] + J + E - b0[b1[160] % 20]
                + (b4[b0[((b3[8] + J + E) & 255) % 20] % 21] / 3)) & 0xFF;
        B = ((R ^ b3[72]) & ~198) | ((S * S) & 198);
        F = (b4[b1[69] % 21] & b1[172]) | ((b4[b1[69] % 21] | b1[172]) & ((b3[12] - B) + 77));
        b0[16] = (147 - ((b3[72] & ((F & 251) | 1)) | (((F & 250) | b3[72]) & 198))) & 0xFF;
        C = (b4[b1[168] % 21] & b0[b1[29] % 20] & 7) | ((b4[b1[168] % 21] | b0[b1[29] % 20]) & 6);
        F = (b4[b1[155] % 21] & b1[105]) | ((b4[b1[155] % 21] | b1[105]) & 141);
        b0[3] = (b0[3] - b4[weirdRol32(F, C) % 21]) & 0xFF;
        b1[5] = (weirdRor8(b0[12], ((b0[b1[61] % 20] / 5) & 7))
                ^ (int) (((~b2[b3[84] % 35]) & 0xffffffffL) / 5)) & 0xFF;
        b1[198] = (b1[198] + b1[3]) & 0xFF;
        A = (162 | b2[b3[64] % 35]);
        b1[164] = (b1[164] + ((A * A) / 5)) & 0xFF;
        G = weirdRor8(139, (b3[80] & 7));
        C = ((b4[b3[64] % 21] * b4[b3[64] % 21] * b4[b3[64] % 21]) & 95) | (b0[b3[40] % 20] & ~95);
        b3[92] = ((G & 12) | (b0[b3[20] % 20] & 12) | (G & b0[b3[20] % 20]) | C) & 0xFF;
        b2[12] = (b2[12] + (((b1[103] & 32) | (b3[92] & (b1[103] | 60)) | 16) / 3)) & 0xFF;
        b3[96] = b1[143];
        b3[100] = 27;
        b3[104] = ((((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) ^ 119) & 0xFF;
        b3[108] = (238 & ((((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) << 1)) & 0xFF;
        b3[112] = ((~b3[64] & (b3[84] / 3)) ^ 49) & 0xFF;
        b3[116] = (98 & ((~b3[64] & (b3[84] / 3)) << 1)) & 0xFF;
        A = (b1[35] & b2[8]) | (b3[40] & ~b2[8]);
        B = (A & b3[64]) | ((b3[84] / 3) & ~b3[64]);
        b1[143] = (b3[96] - ((B & (86 + ((b1[172] & 64) >> 1)))
                | (((((b1[172] & 65) >> 1) ^ 86) | ((~b3[64] & (b3[84] / 3))
                | (((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]))) & b3[100]))) & 0xFF;
        b2[29] = 162;
        A = (((b4[b3[88] % 21] & 160) | (b0[b1[125] % 20] & 95)) >> 1);
        B = b2[b1[149] % 35] ^ (b1[43] * b1[43]);
        b0[15] = (b0[15] + ((B & A) | ((A | B) & 115))) & 0xFF;
        b3[120] = (b3[64] - b0[b3[40] % 20]) & 0xFF;
        b1[95] = b4[b3[20] % 21];
        A = weirdRor8(b2[b3[80] % 35],
                (b2[b1[17] % 35] * b2[b1[17] % 35] * b2[b1[17] % 35]) & 7);
        b0[7] = (b0[7] - (A * A)) & 0xFF;
        b2[8] = (b2[8] - b1[184]
                + (b4[b1[202] % 21] * b4[b1[202] % 21] * b4[b1[202] % 21])) & 0xFF;
        b0[16] = (b2[b1[102] % 35] << 1) & 132;
        b3[124] = ((b4[b3[40] % 21] >> 1) ^ b3[68]) & 0xFF;
        b0[7] = (b0[7] - (b0[b1[191] % 20]
                - (((b4[b1[80] % 21] << 1) & ~177) | (b4[b4[b3[88] % 21] % 21] & 177)))) & 0xFF;
        b0[6] = b0[b1[119] % 20];
        A = (b4[b1[190] % 21] & ~209) | (b1[118] & 209);
        B = b0[b3[120] % 20] * b0[b3[120] % 20];
        b0[12] = (b0[b3[84] % 20] ^ (b2[b1[71] % 35] + b2[b1[15] % 35]))
                & ((A & B) | ((A | B) & 27)) & 0xFF;
        B = (b1[32] & b2[b3[88] % 35]) | ((b1[32] | b2[b3[88] % 35]) & 23);
        D = (((b4[b1[57] % 21] * 231) & 169) | (B & 86));
        F = (((b0[b1[82] % 20] & ~29) | (b4[b3[124] % 21] & 29)) & 190) | (b4[(D / 5) % 21] & ~190);
        H = b0[b3[40] % 20] * b0[b3[40] % 20] * b0[b3[40] % 20];
        K = (H & b1[82]) | (H & 92) | (b1[82] & 92);
        b3[128] = (((F & K) | ((F | K) & 192)) ^ (D / 5)) & 0xFF;
        b2[25] = (b2[25] ^ (((b0[b3[120] % 20] << 1) * b1[5])
                - (weirdRol8(b3[76], (b4[b3[124] % 21] & 7)) & (b3[20] + 110)))) & 0xFF;
    }
}
