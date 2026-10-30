package com.repopilot.common.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 确定性 JSON 规范化：同一份语义内容，任何一次序列化都必须得到完全相同的字节。
 *
 * <p>用途只有一个——计算 {@code config_hash} / {@code content_hash}。这两个值会写进数据库，
 * 用来判断"两个版本内容是否相同"。一旦规范化结果不稳定，同一份配置两次发布会得到两个 hash，
 * 历史对比与"复制版本后 hash 相等"的断言就全部失效。
 *
 * <p>规则（与 {@code docs/architecture/03-database-design.md} 一致）：
 * <ol>
 *   <li>对象键按 Unicode 码点升序；数组保持原顺序。</li>
 *   <li>无多余空白：{@code ,} 与 {@code :} 后不加空格。</li>
 *   <li>布尔输出 {@code true/false}，{@code null} 保留。</li>
 *   <li>数字保留原始标度：浮点反序列化用 {@code USE_BIG_DECIMAL_FOR_FLOATS}，
 *       输出用 {@link BigDecimal#toPlainString()}。禁止 {@code 1.0 → 1}、{@code 0.10 → 0.1}。</li>
 *   <li>字符串最小转义：只转 {@code "}、{@code \} 与控制字符；非 ASCII 原样输出 UTF-8，
 *       不转成 U+XXXX 转义形式。</li>
 *   <li>输出 UTF-8 字节，{@code sha256} 取小写十六进制。</li>
 * </ol>
 *
 * <p>刻意不直接使用 {@code ObjectMapper.writeValueAsString}：它不保证键序，且对数字标度、
 * Unicode 转义的控制权不足。这里的实现是显式递归，行为可以用单元测试固定住。 */
public final class CanonicalJson {

    /**
     * 反序列化时保留浮点原始标度；这是"1.0 不变 1、0.10 不变 0.1"的前提。
     *
     * <p>两个开关缺一不可：{@code USE_BIG_DECIMAL_FOR_FLOATS} 让浮点变 {@link java.math.BigDecimal}
     * 而不是 {@code double}（{@code double} 无法表示 0.10 的标度），
     * {@code withExactBigDecimals(true)} 阻止节点构造时 {@code stripTrailingZeros}
     * （否则 1.0 会被规整成 1）。只开第一个仍会把 {@code 1.0} 输出成 {@code 1} —— 实测过。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    /**
     * 键排序比较器。
     *
     * <p>业务键都是 ASCII，{@link String#compareTo} 此时等价于码点序；
     * 对包含补充平面字符的键，用码点比较保证与"Unicode 码点升序"严格一致。
     */
    private static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private CanonicalJson() {
    }

    public static JsonNode readTree(byte[] bytes) throws java.io.IOException {
        return MAPPER.readTree(bytes);
    }

    public static JsonNode readTree(String text) throws java.io.IOException {
        return MAPPER.readTree(text);
    }

    /** 解析为对象树；不是合法 JSON 对象时返回 false，供调用方转成字段级校验错误。 */
    public static boolean isObjectNode(JsonNode node) {
        return node instanceof ObjectNode;
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    public static byte[] canonicalBytes(JsonNode node) {
        return canonicalize(node).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            writeObject((ObjectNode) node, sb);
        } else if (node.isArray()) {
            writeArray((ArrayNode) node, sb);
        } else if (node.isTextual()) {
            writeString(node.textValue(), sb);
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNumber()) {
            sb.append(number(node));
        } else if (node.isBinary()) {
            // 二进制节点不应出现在配置/样本里；用其文本形式而不是抛异常，
            // 避免把一个格式问题变成 500。
            writeString(node.asText(), sb);
        } else {
            writeString(node.asText(), sb);
        }
    }

    private static void writeObject(ObjectNode object, StringBuilder sb) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = object.fields();
        while (it.hasNext()) {
            fields.add(it.next());
        }
        fields.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));

        sb.append('{');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeString(fields.get(i).getKey(), sb);
            sb.append(':');
            write(fields.get(i).getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeArray(ArrayNode array, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(array.get(i), sb);
        }
        sb.append(']');
    }

    private static String number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().toString();
        }
        BigDecimal decimal = node.decimalValue();
        return decimal.toPlainString();
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
