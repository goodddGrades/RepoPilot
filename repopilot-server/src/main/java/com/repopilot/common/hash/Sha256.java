package com.repopilot.common.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 小写十六进制摘要。
 *
 * <p>只做一件事：把字节变成 64 位十六进制字符串。集中在一处，是为了让
 * "哈希列一律小写 64 位、UTF-8 编码"这条约束只有一份实现，不会某个模块写成大写、
 * 另一个模块写成 Base64 —— 那会让唯一键比较与"两个版本 hash 相等"的判断悄悄失效。
 */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {
    }

    public static String ofBytes(byte[] bytes) {
        MessageDigest digest = newDigest();
        return hex(digest.digest(bytes));
    }

    public static String ofUtf8(String text) {
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行环境被裁剪过，属于致命配置错误。
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
