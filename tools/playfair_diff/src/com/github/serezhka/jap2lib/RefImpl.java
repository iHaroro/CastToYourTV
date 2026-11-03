package com.github.serezhka.jap2lib;

/** 暴露 OmgHax 给对拍测试用 */
public final class RefImpl {
    public static byte[] decryptAesKey(byte[] keymsg164, byte[] ekey72) {
        OmgHax hax = new OmgHax();
        byte[] out = new byte[16];
        hax.decryptAesKey(keymsg164, ekey72, out);
        return out;
    }
}
