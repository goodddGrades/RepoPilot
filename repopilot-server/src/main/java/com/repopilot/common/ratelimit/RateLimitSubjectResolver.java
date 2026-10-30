package com.repopilot.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 解析限流主体。
 *
 * <p><b>现状与局限（必须如实写明）</b>：本仓库当前<b>没有</b>用户/租户身份体系
 * ——没有登录、没有 token、没有 {@code SecurityContext}。因此入口限流只能以
 * 客户端地址作为主体，这是一个<b>退而求其次</b>的选择，局限是明确的：
 *
 * <ul>
 *   <li>同一 NAT / 代理后的多个真实用户共用一个出口地址，会互相挤占额度；</li>
 *   <li>攻击者可通过更换出口地址规避（分布式慢速请求不受保护）；</li>
 *   <li>{@code X-Forwarded-For} 默认<b>不信任</b>：它由客户端自由填写，
 *       直接采信等于把"换主体"的开关交给调用方。</li>
 * </ul>
 *
 * <p>一旦引入身份体系，只需要改这一个类（把 {@code ip:} 换成 {@code user:}/{@code tenant:}），
 * 限流器、Key 设计与所有测试都不用动。这也是为什么主体被做成一个
 * 带类型前缀的字符串，而不是直接传 IP。
 */
@Component
public class RateLimitSubjectResolver {

    private static final Logger log = LoggerFactory.getLogger(RateLimitSubjectResolver.class);

    /** 只接受 IPv4/IPv6 字面量的合理字符集，避免任意长字符串进入哈希与日志。 */
    private static final Pattern ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");

    private static final String FALLBACK = "unknown";

    private final RateLimitProperties properties;

    public RateLimitSubjectResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** 返回形如 {@code ip:10.0.0.1} 的主体标识；不含任何凭据。 */
    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwarded = firstHop(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return "ip:" + forwarded;
            }
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "ip:" + FALLBACK;
        }
        return "ip:" + remote.trim();
    }

    /**
     * 取 {@code X-Forwarded-For} 的第一跳。
     *
     * <p>格式是非法的值直接忽略并退回真实来源——宁可把多个客户端合并成一个主体，
     * 也不要让一个畸形请求头把限流主体变成任意字符串。
     */
    private String firstHop(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",", 2)[0].trim();
        if (!ADDRESS.matcher(first).matches()) {
            log.warn("忽略非法的 X-Forwarded-For 首跳: {}", first);
            return null;
        }
        return first;
    }
}
