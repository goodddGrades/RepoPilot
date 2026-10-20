package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.ratelimit.RateLimitDecision;
import com.repopilot.common.ratelimit.RateLimitPolicy;
import com.repopilot.common.ratelimit.RedisTokenBucketRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口限流的验收（真实 MySQL + 真实 Redis）。
 *
 * <p>为什么必须连真实 Redis：Lua 的原子性只能在真实服务端证明。用 Mock 或
 * "假脚本"验证不了"64 个并发请求恰好只放行 N 个"——那正是本任务的核心承诺。
 *
 * <p>本类用**自己的一套小阈值**（capacity=3 等），与 {@code application-it.yml}
 * 里给其它用例的宽松阈值分开：其它集成测试会连续创建上百个实验，
 * 共用一套小额度会让它们变成难以复现的 429。
 *
 * <p>缺 Redis 时整类跳过，跳过数量会写进交付报告，不伪装成通过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "repopilot.rate-limit.enabled=true",
        "repopilot.rate-limit.environment=it",
        // 打开信任转发头，才能在同一台机器上模拟"不同主体"。
        // 生产默认关闭——该头可被客户端伪造。
        "repopilot.rate-limit.trust-forwarded-for=true",

        "repopilot.rate-limit.policies.api-write.capacity=3",
        "repopilot.rate-limit.policies.api-write.refill-tokens-per-second=0.0001",
        "repopilot.rate-limit.policies.api-write.cost-per-request=1",
        "repopilot.rate-limit.policies.api-write.ttl=10m",
        "repopilot.rate-limit.policies.api-write.failure-mode=FAIL_OPEN",
        "repopilot.rate-limit.policies.api-write.methods[0]=POST",

        // 补充/过期用的是独立小策略：路径指向"改状态"的入口，请求体不需要合法，
        // 拦截器在参数解析之前就会执行，所以 400/404 也会消耗令牌——这正是期望行为。
        "repopilot.rate-limit.policies.rl-refill.capacity=1",
        "repopilot.rate-limit.policies.rl-refill.refill-tokens-per-second=10",
        "repopilot.rate-limit.policies.rl-refill.cost-per-request=1",
        "repopilot.rate-limit.policies.rl-refill.ttl=1m",
        "repopilot.rate-limit.policies.rl-refill.failure-mode=FAIL_OPEN",
        "repopilot.rate-limit.policies.rl-refill.paths[0]=/api/v1/experiments/*/cancel",
        "repopilot.rate-limit.policies.rl-refill.methods[0]=POST",

        "repopilot.rate-limit.policies.rl-ttl.capacity=1",
        "repopilot.rate-limit.policies.rl-ttl.refill-tokens-per-second=0.0001",
        "repopilot.rate-limit.policies.rl-ttl.cost-per-request=1",
        // 300ms 后桶被回收：用来证明"Key 有 TTL，空闲状态不会永久占内存"。
        "repopilot.rate-limit.policies.rl-ttl.ttl=300ms",
        "repopilot.rate-limit.policies.rl-ttl.failure-mode=FAIL_OPEN",
        "repopilot.rate-limit.policies.rl-ttl.paths[0]=/api/v1/imports",
        "repopilot.rate-limit.policies.rl-ttl.methods[0]=POST"
})
@EnabledIfEnvironmentVariable(named = "REPOPILOT_IT_REDIS_HOST", matches = ".+")
class RateLimitIT extends AbstractApiIT {

    private static final String KEY_PATTERN = "repopilot:ratelimit:it:*";

    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisTokenBucketRateLimiter limiter;

    @BeforeEach
    @AfterEach
    void clearBuckets() {
        Set<String> keys = redis.keys(KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void writeEndpointShouldReturn429WithRetryAfterWhenBucketIsExhausted() {
        String ip = uniqueIp();

        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> allowed = postAs(ip, "/api/v1/experiments", "{}", "rl-" + i);
            assertThat(allowed.getStatusCode().value())
                    .as("第 %d 次请求应放行（可能因参数非法返回 400，但不能是 429）", i)
                    .isNotEqualTo(429);
        }

        ResponseEntity<String> rejected = postAs(ip, "/api/v1/experiments", "{}", "rl-4");

        assertThat(rejected.getStatusCode().value()).isEqualTo(429);
        assertThat(codeOf(rejected)).isEqualTo("RATE_LIMITED");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(Long.parseLong(rejected.getHeaders().getFirst("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("api-write");
        // 统一信封：客户端按既有结构解析，不需要为 429 写第二套分支。
        assertThat(rejected.getBody()).contains("\"requestId\"", "\"timestamp\"");
    }

    @Test
    void exhaustedSubjectShouldNotAffectOtherSubjects() {
        String exhausted = uniqueIp();
        String other = uniqueIp();

        for (int i = 1; i <= 3; i++) {
            postAs(exhausted, "/api/v1/experiments", "{}", "iso-a-" + i);
        }
        assertThat(postAs(exhausted, "/api/v1/experiments", "{}", "iso-a-4").getStatusCode().value())
                .isEqualTo(429);

        // 另一个主体有自己独立的桶：多主体隔离不是"共享一个全局计数"。
        assertThat(postAs(other, "/api/v1/experiments", "{}", "iso-b-1").getStatusCode().value())
                .isNotEqualTo(429);
    }

    @Test
    void readRequestsAreNotRateLimited() {
        String ip = uniqueIp();
        for (int i = 1; i <= 3; i++) {
            postAs(ip, "/api/v1/experiments", "{}", "ro-" + i);
        }
        assertThat(postAs(ip, "/api/v1/experiments", "{}", "ro-4").getStatusCode().value()).isEqualTo(429);

        // 只读查询不受写入口策略影响：限流不该把浏览页面也一起关掉。
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> listed = getWithIp(ip, "/api/v1/experiments");
            assertThat(listed.getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void tokensAreRefilledOverTime() throws Exception {
        String ip = uniqueIp();

        assertThat(postAs(ip, "/api/v1/experiments/01JTESTSHORTID000000000000/cancel", null, "rf-1")
                .getStatusCode().value()).isNotEqualTo(429);
        assertThat(postAs(ip, "/api/v1/experiments/01JTESTSHORTID000000000000/cancel", null, "rf-2")
                .getStatusCode().value()).as("容量 1，紧接着的第二次必须被拒").isEqualTo(429);

        // 速率 10/s → 300ms 补 3 个令牌（容量 1，封顶 1）。用轮询而不是硬等固定值：
        // 断言"最终会恢复"，而不是"恰好 300ms 恢复"——后者依赖机器负载，必然偶发失败。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean recovered = false;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> probe = postAs(ip,
                    "/api/v1/experiments/01JTESTSHORTID000000000000/cancel", null, "rf-" + Ulid.newId());
            if (probe.getStatusCode().value() != 429) {
                recovered = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(recovered).as("令牌应按速率补充，3 秒内必须恢复放行").isTrue();
    }

    @Test
    void idleBucketExpiresSoKeysDoNotLiveForever() throws Exception {
        String ip = uniqueIp();

        assertThat(postAs(ip, "/api/v1/imports", "{}", "ttl-1").getStatusCode().value()).isNotEqualTo(429);
        assertThat(postAs(ip, "/api/v1/imports", "{}", "ttl-2").getStatusCode().value()).isEqualTo(429);

        // ttl=300ms：桶被回收后重新从满桶开始（这是 TTL 语义，不是"额度被补满"）。
        Thread.sleep(700);

        assertThat(postAs(ip, "/api/v1/imports", "{}", "ttl-3").getStatusCode().value())
                .as("空闲超过 TTL 后 key 应已消失，桶重新以满状态建立")
                .isNotEqualTo(429);
        assertThat(redis.keys(KEY_PATTERN)).isNotEmpty();
    }

    @Test
    void sameSubjectAndCostAreIsolatedAcrossPolicies() {
        String ip = uniqueIp();
        for (int i = 1; i <= 3; i++) {
            postAs(ip, "/api/v1/experiments", "{}", "pol-" + i);
        }
        assertThat(postAs(ip, "/api/v1/experiments", "{}", "pol-4").getStatusCode().value()).isEqualTo(429);

        // api-write 用尽不影响 api-bulk：不同策略各有独立的桶。
        RateLimitPolicy bulk = new RateLimitPolicy("api-bulk", 5, 0.05, 1, Duration.ofMinutes(30),
                RateLimitPolicy.FailureMode.FAIL_OPEN, List.of("/api/v1/imports"), Set.of("POST"));
        RateLimitDecision decision = limiter.acquire(bulk, "ip:" + ip);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void concurrentRequestsMustNotExceedCapacity() throws Exception {
        // 这条用例是本任务的核心证据：并发下"读—判—扣"必须是原子的。
        // 若把逻辑写在 Java 侧（GET → 计算 → SET），64 个并发会放行远多于 8 个。
        int capacity = 8;
        int concurrency = 64;
        RateLimitPolicy policy = new RateLimitPolicy("rl-concurrent", capacity, 0.000001, 1,
                Duration.ofMinutes(5), RateLimitPolicy.FailureMode.FAIL_OPEN,
                List.of("/internal-test/concurrent"), Set.of("POST"));
        String subject = "ip:concurrent-" + Ulid.newId();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return limiter.acquire(policy, subject).allowed();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed)
                    .as("容量 %d 时并发 %d 个请求只能放行 %d 个，多一个都说明不是原子扣减",
                            capacity, concurrency, capacity)
                    .isEqualTo(capacity);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void healthEndpointShouldReportRedisAsUp() throws Exception {
        ResponseEntity<String> response = get("/api/health");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Redis 不再是"固定 UNKNOWN 的预留项"：这里按组件名精确断言它被真实探测并报 UP，
        // 不用字符串 contains 匹配中文文案（响应编码变化会让那种断言无谓地红）。
        JsonNode components = objectMapper.readTree(response.getBody())
                .path("data").path("components");
        JsonNode redisComponent = null;
        for (JsonNode component : components) {
            if ("redis".equals(component.path("name").asText())) {
                redisComponent = component;
                break;
            }
        }
        assertThat(redisComponent).as("/api/health 必须包含 redis 组件").isNotNull();
        assertThat(redisComponent.path("status").asText()).isEqualTo("UP");
        assertThat(redisComponent.path("detail").asText()).contains("PING");
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 每次调用返回不同的主体地址，避免用例之间互相吃额度。
     *
     * <p>用递增计数器而不是随机数：随机值在 CI 上有概率撞车，表现为"偶发 429"，
     * 那种 flaky 最难查。
     */
    private String uniqueIp() {
        int n = IP_SEQUENCE.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private ResponseEntity<String> postAs(String ip, String path, String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", idempotencyKey);
        headers.add("X-Forwarded-For", ip);
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body == null ? "{}" : body, headers), String.class);
    }

    private ResponseEntity<String> getWithIp(String ip, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", ip);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
