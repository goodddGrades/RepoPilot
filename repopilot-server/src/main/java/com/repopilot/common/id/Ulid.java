package com.repopilot.common.id;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * ULID 生成与校验（Crockford Base32，26 位大写，字典序等于时间序）。
 *
 * <p>结构：前 10 位为 48 位毫秒时间戳，后 16 位为 80 位随机数。同一毫秒内多次生成时
 * 对随机段做进位递增，保证同毫秒内仍然单调，避免"同一批插入的 ID 顺序随机"。
 *
 * <p>为什么不用 UUIDv4：主键是 CHAR(26)，需要字典序可排序；B+ 树按时间递增插入
 * 能显著减少页分裂，也让按 id 排序等价于按创建时间排序。为什么不用数据库自增：
 * ID 需要在写入前确定（幂等回放、跨表引用、异步投递），不能依赖数据库返回。
 */
public final class Ulid {

    /** Crockford Base32：去掉了容易混淆的 I、L、O、U。 */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
            // 允许小写输入便于人工比对日志
            LOOKUP[Character.toLowerCase(ALPHABET[i])] = i;
        }
    }

    public static final int LENGTH = 26;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastTimestamp = -1L;
    private static final byte[] lastRandom = new byte[10];

    private Ulid() {
    }

    public static String newId() {
        return generate(System.currentTimeMillis());
    }

    static synchronized String generate(long timestamp) {
        byte[] random = new byte[10];
        if (timestamp == lastTimestamp) {
            System.arraycopy(lastRandom, 0, random, 0, 10);
            increment(random);
        } else {
            RANDOM.nextBytes(random);
            lastTimestamp = timestamp;
        }
        System.arraycopy(random, 0, lastRandom, 0, 10);
        return encode(timestamp, random);
    }

    /** 大端进位：把 80 位随机段当作无符号整数加一。 */
    private static void increment(byte[] value) {
        for (int i = value.length - 1; i >= 0; i--) {
            if (++value[i] != 0) {
                return;
            }
        }
    }

    private static String encode(long timestamp, byte[] random) {
        char[] chars = new char[LENGTH];
        chars[0] = ALPHABET[(int) ((timestamp >>> 45) & 0x07)];
        for (int i = 1; i <= 9; i++) {
            chars[i] = ALPHABET[(int) ((timestamp >>> (45 - 5 * i)) & 0x1F)];
        }
        int bitBuffer = 0;
        int bitCount = 0;
        int index = 10;
        for (byte b : random) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                chars[index++] = ALPHABET[(bitBuffer >>> (bitCount - 5)) & 0x1F];
                bitCount -= 5;
            }
        }
        return new String(chars);
    }

    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        String value = candidate.toUpperCase(Locale.ROOT);
        // 48 位时间戳只占 50 位空间的前 48 位，因此首位最大为 '7'
        if (value.charAt(0) > '7') {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = value.charAt(i);
            if (c >= LOOKUP.length || LOOKUP[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /** 写入数据库前的前置校验：非法 ID 不允许进库，避免出现无法排序、无法追溯的主键。 */
    public static String requireValid(String candidate) {
        if (!isValid(candidate)) {
            throw new IllegalArgumentException("非法的 ULID: " + candidate);
        }
        return candidate.toUpperCase(Locale.ROOT);
    }
}
