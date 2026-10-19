package com.repopilot.common.worker;

import com.repopilot.common.hash.Sha256;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 租约令牌的生成与比对。
 *
 * <p>数据库只保存 {@code sha256(token)}：即使库被只读泄露，攻击者拿到摘要也无法构造出可用的
 * {@code leaseToken}。明文只在领取响应里返回一次，之后 Worker 每次调用都带上它。
 *
 * <p>比对使用常量时间比较，避免通过响应时间逐字节猜 token。这里的威胁模型是"同网段攻击者"，
 * 不是侧信道学术场景，但常量时间比较的实现成本几乎为零。
 */
public final class LeaseTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private LeaseTokens() {
    }

    /** 生成 32 字节随机令牌（base64url，无填充）。 */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** 令牌摘要，写入 {@code lease_token_hash}。 */
    public static String hash(String token) {
        return Sha256.ofUtf8(token);
    }

    /** 常量时间比较；任一参数为空都返回 false。 */
    public static boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null) {
            return false;
        }
        byte[] actual = hash(token).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }
}
