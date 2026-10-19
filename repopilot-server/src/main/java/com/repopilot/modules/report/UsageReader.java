package com.repopilot.modules.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * usage JSON 的读取（TASK-010）。
 *
 * <p>为什么单独一个类：成本口径是这个报告里最容易悄悄算错的地方。金额可能是
 * JSON 数字，也可能是十进制字符串（契约要求"金额 JSON 使用十进制字符串"）；
 * 字段名在不同适配器下也不完全一致。任何一处"null 当0"都会让报告少报成本。
 *
 * <p>因此这里只做三件事，**不做任何推断**：
 * <ul>
 *   <li>读到金额就返回金额，读不到就返回 {@code null}（绝不返回 0）；
 *   <li>金额必须与币种一起返回，缺币种的金额按未知处理——不同币种不相加；
 *   <li>已知的多种字段名都认，但认不出来就是未知，不猜。
 * </ul>
 *
 * <p>字段名兼容说明：{@code docs/modules/trace-pipeline.md} 约定的是
 * {@code costAmount/currency/costKnown/costSource}，而当前 Worker 的 CLI 与 Judge
 * 适配器实际写的是 {@code costUsd/currency}。两者都读，读到 {@code costAmount} 优先
 * （那是契约字段）。这个不一致已记入 TASK-010 交付报告的已知限制。
 */
public final class UsageReader {

    private static final String[] COST_AMOUNT_KEYS = {"costAmount", "costUsd", "actualCost"};
    private static final String[] CURRENCY_KEYS = {"currency", "costCurrency", "cost_currency"};

    /** 一个 usage 里的金额与币种。{@code amount} 为 null 表示未知，不是 0。 */
    public record Cost(BigDecimal amount, String currency) {

        public boolean known() {
            return amount != null && currency != null && !currency.isBlank();
        }

        public static final Cost UNKNOWN = new Cost(null, null);
    }

    private UsageReader() {
    }

    /**
     * 读取金额。
     *
     * @return 有金额且有币种时返回；任一缺失都返回 {@link Cost#UNKNOWN}
     */
    public static Cost costOf(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Cost.UNKNOWN;
        }
        // 显式的 costKnown=false 是上游在说"我确实不知道"，优先于任何字段猜测
        JsonNode known = usage.get("costKnown");
        if (known != null && known.isBoolean() && !known.asBoolean()) {
            return Cost.UNKNOWN;
        }
        BigDecimal amount = decimalOf(usage, COST_AMOUNT_KEYS);
        String currency = textOf(usage, CURRENCY_KEYS);
        if (amount == null || amount.signum() < 0 || currency == null) {
            return Cost.UNKNOWN;
        }
        return new Cost(amount, currency);
    }

    /** 执行耗时（毫秒）；未知返回 {@code null}，不返回 0。 */
    public static Long latencyMsOf(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        for (String key : new String[]{"latencyMs", "elapsedMs"}) {
            JsonNode node = usage.get(key);
            if (node != null && node.isNumber()) {
                return node.asLong();
            }
        }
        return null;
    }

    private static BigDecimal decimalOf(JsonNode node, String[] keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            // 十进制字符串与 JSON 数字都要接受：适配器写法不统一，但两者语义相同
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual()) {
                try {
                    return new BigDecimal(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 不是金额就当没写，不抛异常：一个 usage 里多个候选键，
                    // 第一个解析失败不代表整个 usage 不可用
                }
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, String[] keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim().toUpperCase();
            }
        }
        return null;
    }
}