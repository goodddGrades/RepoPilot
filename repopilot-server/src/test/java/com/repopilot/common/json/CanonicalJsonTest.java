package com.repopilot.common.json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规范化 JSON 的行为由这些用例固定住。
 *
 * <p>这些断言看起来琐碎，但它们保护的是"历史 hash 可复现"：任何一条被改坏，
 * 已经发布版本的 {@code content_hash} 就再也算不出来了，而且不会报错——
 * 只会表现为"同样的内容算出不同 hash"。
 */
class CanonicalJsonTest {

    @Test
    void keyOrderShouldNotAffectOutput() throws Exception {
        String a = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"b\":1,\"a\":2}"));
        String b = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"a\":2,\"b\":1}"));
        assertThat(a).isEqualTo(b).isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void decimalScaleShouldBePreserved() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"x\":1.0,\"y\":0.10,\"z\":1}"));
        // 1.0 不能变成 1，0.10 不能变成 0.1：标度是内容的一部分
        assertThat(out).isEqualTo("{\"x\":1.0,\"y\":0.10,\"z\":1}");
    }

    @Test
    void nonAsciiShouldStayRaw() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"y\":\"中文 ok\"}"));
        assertThat(out).contains("中文");
        assertThat(out).doesNotContain("\\u");
    }

    @Test
    void arrayOrderShouldBePreserved() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"a\":[3,1,2]}"));
        assertThat(out).isEqualTo("{\"a\":[3,1,2]}");
    }

    @Test
    void nestedObjectsShouldBeSortedRecursively() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree(
                "{\"outer\":{\"b\":{\"d\":1,\"c\":2},\"a\":3}}"));
        assertThat(out).isEqualTo("{\"outer\":{\"a\":3,\"b\":{\"c\":2,\"d\":1}}}");
    }

    @Test
    void onlyControlAndQuoteCharsShouldBeEscaped() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"s\":\"a\\\"b\\\\c\\nd\"}"));
        assertThat(out).isEqualTo("{\"s\":\"a\\\"b\\\\c\\nd\"}");
    }

    @Test
    void nullAndBooleanShouldBeCanonical() throws Exception {
        String out = CanonicalJson.canonicalize(CanonicalJson.readTree("{\"n\":null,\"t\":true,\"f\":false}"));
        assertThat(out).isEqualTo("{\"f\":false,\"n\":null,\"t\":true}");
    }
}
