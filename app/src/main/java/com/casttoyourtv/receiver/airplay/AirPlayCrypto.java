package com.casttoyourtv.receiver.airplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay 配对加密（对齐 UxPlay pairing.c 的免 PIN 流程）：
 *  - 长期身份：Ed25519 密钥对（首次启动生成，公钥写进 mDNS TXT / /info 的 pk 字段）
 *  - 每次连接：X25519 临时密钥交换 -> 共享密钥
 *  - pair-verify 签名：Ed25519 签名后用 AES-128-CTR 加密（key/iv 由共享密钥派生）
 *
 * BouncyCastle 只用 org.bouncycastle.crypto 轻量 API，不走 JCA Provider，避免与系统冲突。
 */
public class AirPlayCrypto {

    private static Ed25519PrivateKeyParameters edPrivate;
    private static Ed25519PublicKeyParameters edPublic;

    /** 首次调用生成并持久化 Ed25519 密钥对（幂等） */
    public static synchronized void init(Context ctx) {
        if (edPrivate != null) return;
        SharedPreferences sp = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE);
        String seedHex = sp.getString("ed_seed", null);
        if (seedHex != null) {
            edPrivate = new Ed25519PrivateKeyParameters(hexToBytes(seedHex), 0);
        } else {
            edPrivate = new Ed25519PrivateKeyParameters(new SecureRandom());
            sp.edit().putString("ed_seed", bytesToHex(edPrivate.getEncoded())).apply();
        }
        edPublic = edPrivate.generatePublicKey();
    }

    public static synchronized byte[] edPublicKey() {
        return edPublic.getEncoded();
    }

    public static synchronized String pkHex() {
        return bytesToHex(edPublicKey());
    }

    public static byte[] edSign(byte[] msg) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, edPrivate);
        signer.update(msg, 0, msg.length);
        return signer.generateSignature();
    }

    public static boolean edVerify(byte[] sig, byte[] msg, byte[] publicKey) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
            signer.update(msg, 0, msg.length);
            return signer.verifySignature(sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** 一次连接的 X25519 会话 */
    public static class X25519Session {
        private final X25519PrivateKeyParameters priv;
        private final X25519Agreement agreement;

        public X25519Session() {
            priv = new X25519PrivateKeyParameters(new SecureRandom());
            agreement = new X25519Agreement();
            agreement.init(priv);
        }

        public byte[] publicKey() {
            return priv.generatePublicKey().getEncoded();
        }

        public byte[] agree(byte[] theirPublicKey) {
            byte[] secret = new byte[32];
            agreement.calculateAgreement(new X25519PublicKeyParameters(theirPublicKey, 0), secret, 0);
            return secret;
        }
    }

    /** UxPlay derive_key_internal：SHA512(salt || sharedSecret) 取前 keylen 字节 */
    public static byte[] deriveKey(String salt, byte[] sharedSecret, int keylen) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update(salt.getBytes("US-ASCII"));
        byte[] hash = sha.digest(sharedSecret);
        byte[] key = new byte[keylen];
        System.arraycopy(hash, 0, key, 0, keylen);
        return key;
    }

    /** AES-128-CTR 加解密（CTR 是对称流，同一函数两用） */
    public static byte[] aes128Ctr(byte[] key, byte[] iv, byte[] data, int off, int len) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data, off, len);
    }

    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
