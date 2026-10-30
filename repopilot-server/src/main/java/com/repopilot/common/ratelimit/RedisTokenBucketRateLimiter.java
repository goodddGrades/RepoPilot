package com.repopilot.common.ratelimit;

import com.repopilot.common.hash.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 基于 Redis + Lua 的分布式令牌桶。
 *
 * <p>为什么是 Lua 而不是应用侧计算：见 {@code ratelimit/token_bucket.lua} 头部注释。
 * 一句话——Redis 单线程执行整个脚本，"读—补—判—扣—写"之间不可能被并发插入，
 * 而 Java 侧的 GET/SET 在同样并发下会超额放行。
 *
 * <p>Key 设计：
 * <pre>{@code {prefix}:{environment}:{policy}:{subjectHash}}</pre>
 * <ul>
 *   <li>{@code prefix} 隔离业务命名空间，便于整体清理（{@code SCAN repopilot:ratelimit:*}）；</li>
 *   <li>{@code environment} 隔离 dev/it/prod，避免测试环境吃光开发环境额度；</li>
 *   <li>{@code policy} 让同一主体的不同入口各自计量；</li>
 *   <li>{@code subjectHash} 是 SHA-256 前 16 位十六进制。<b>不写原始主体标识</b>：
 *       既避免 IPv6 里的冒号污染 key 结构，也避免把客户端地址原样沉淀到
 *       Redis 键空间与慢查询日志里。任何凭据类信息一律不进 key。</li>
 * </ul>
 *
 * <p>TTL：每次写入都刷新 {@code PEXPIRE}。空闲的桶会被自动回收，
 * 长时间不活跃的客户端不会永久占用内存（这也是"Key 必须有合理 TTL"的落地）。
 */
@Component
public class RedisTokenBucketRateLimiter implements TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /** 主体哈希取前 16 位：2^64 空间，碰撞概率可忽略，key 也足够短。 */
    private static final int SUBJECT_HASH_LENGTH = 16;

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<List> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis,
                                       RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setLocation(new ClassPathResource("ratelimit/token_bucket.lua"));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    @Override
    public RateLimitDecision acquire(RateLimitPolicy policy, String subject) {
        String key = key(policy, subject);
        List<?> raw;
        try {
            raw = redis.execute(script, List.of(key),
                    Long.toString(policy.capacity()),
                    // 速率用十进制定点表示（BigDecimal.toPlainString）：
                    // String.valueOf(double) 在极小值时会输出 "1.0E-4"，
                    // 虽然 Lua 的 tonumber 也能解析，但两端传参格式不一致会让
                    // 排查"为什么 Java 算出来和 Python 不一样"变得很费劲。
                    // toPlainString 既不丢精度，也不产生指数。
                    BigDecimal.valueOf(policy.refillPerSecond()).toPlainString(),
                    Long.toString(policy.costPerRequest()),
                    Long.toString(policy.ttl().toMillis()));
        } catch (DataAccessException ex) {
            // 连接失败、超时、脚本执行错误都收敛到这里。
            // 不在这里决定放行还是拒绝——那是策略的 failure-mode，由调用方决定。
            log.warn("限流依赖不可用，策略={}：{}", policy.name(), ex.getMessage());
            throw new RateLimitUnavailableException(policy.name(), ex);
        } catch (RuntimeException ex) {
            // 非 DataAccess 的运行期错误（脚本语法、返回值解析）：同样归入不可用，
            // 但日志级别更高——它通常意味着脚本被改坏了，而不是网络抖动。
            log.error("限流脚本执行失败，策略={}", policy.name(), ex);
            throw new RateLimitUnavailableException(policy.name(), ex);
        }
        return toDecision(policy, subject, raw);
    }

    /** Key 命名规则集中在这里，测试直接调用以断言命名空间、环境隔离与哈希。 */
    String key(RateLimitPolicy policy, String subject) {
        String hash = Sha256.ofUtf8(subject).substring(0, SUBJECT_HASH_LENGTH);
        return "%s:%s:%s:%s".formatted(properties.getKeyPrefix(),
                properties.getEnvironment(), policy.name(), hash);
    }

    private RateLimitDecision toDecision(RateLimitPolicy policy, String subject, List<?> raw) {
        if (raw == null || raw.size() < 4) {
            throw new RateLimitUnavailableException(policy.name(),
                    new IllegalStateException("限流脚本返回值格式不正确: " + raw));
        }
        long allowed = toLong(raw.get(0));
        long remaining = toLong(raw.get(1));
        long retryAfter = toLong(raw.get(2));
        long capacity = toLong(raw.get(3));
        if (allowed < 0) {
            // 脚本显式拒绝参数：说明 capacity/rate/cost/ttl 里有非正值。
            // 这类问题本应在 RateLimitPolicy 构造时被拦下，走到这里意味着
            // 有人绕过了校验（例如直接调脚本），按配置错误处理而不是放行。
            throw new IllegalStateException("限流脚本拒绝了参数，策略=" + policy.name()
                    + "（capacity/refill/cost/ttl 必须为正数）");
        }
        RateLimitDecision decision = new RateLimitDecision(allowed == 1, remaining, capacity,
                allowed == 1 ? 0L : retryAfter, policy.name(), subject);
        if (!decision.allowed()) {
            log.debug("限流拒绝: policy={}, subjectHash={}, retryAfterMs={}",
                    policy.name(), Sha256.ofUtf8(subject).substring(0, SUBJECT_HASH_LENGTH), retryAfter);
        }
        return decision;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        if (value instanceof String text) {
            return Long.parseLong(text.trim());
        }
        throw new IllegalStateException("无法解析限流脚本返回值: " + value);
    }
}
