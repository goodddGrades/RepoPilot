package com.repopilot.common.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Worker 协议与租约参数。
 *
 * <p>默认值与 {@code docs/architecture/06-reliability-design.md} 的"默认参数是可配置设计起点"一致：
 * 租约 60 秒、心跳 15 秒、每 Run 最多 2 次尝试、退避 5/15 秒。
 * 硬截止时间独立于续租：续租只能把 {@code lease_until} 推到 {@code deadline_at}，
 * 不能把 {@code deadline_at} 往后挪。
 *
 * <p>这里刻意不使用 {@code @Value} 分散注入：租约、退避、上限是一组相互约束的参数，
 * 集中在一个对象里更容易审查"改了一个忘了另一个"。
 */
@ConfigurationProperties(prefix = "repopilot.worker")
public record WorkerProperties(
        /** Worker Bearer 凭证；为空时内部接口一律拒绝（fail closed）。 */
        String token,
        /** 允许的 workerId 列表（逗号分隔）；为空表示不限制（凭证仍然必须正确）。 */
        String allowedIds,
        /** 租约时长。 */
        Duration lease,
        /** 心跳建议间隔，仅回传给 Worker 用于自检。 */
        Duration heartbeatInterval,
        /** 单次领取上限。 */
        int maxTasksLimit,
        /** 单 Run 最多尝试次数（含首次）。 */
        int maxAttemptsPerRun,
        /** 第一次重试前的退避。 */
        Duration retryBackoffFirst,
        /** 第二次及以后重试前的退避。 */
        Duration retryBackoffSecond,
        /** 单个任务的硬截止时间。 */
        Duration taskDeadline,
        /** 后台扫描（推进 PENDING、回收过期租约）的间隔。 */
        Duration scanInterval,
        /** 是否启用后台扫描；集成测试关闭它，由用例显式调用以消除时序不确定性。 */
        boolean schedulerEnabled,
        /**
         * 单评测 Job 最多尝试次数（含首次）。
         *
         * <p>放在记录末尾而不是中间：Spring Boot 的构造器绑定按名称匹配，
         * 新增字段不会影响已有配置文件；缺省时由下面的紧凑构造器补 3。
         */
        Integer maxAttemptsPerJob
) {

    public WorkerProperties {
        token = token == null ? "" : token;
        allowedIds = allowedIds == null ? "" : allowedIds;
        lease = lease == null ? Duration.ofSeconds(60) : lease;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(15) : heartbeatInterval;
        maxTasksLimit = maxTasksLimit <= 0 ? 10 : maxTasksLimit;
        maxAttemptsPerRun = maxAttemptsPerRun <= 0 ? 2 : maxAttemptsPerRun;
        retryBackoffFirst = retryBackoffFirst == null ? Duration.ofSeconds(5) : retryBackoffFirst;
        retryBackoffSecond = retryBackoffSecond == null ? Duration.ofSeconds(15) : retryBackoffSecond;
        taskDeadline = taskDeadline == null ? Duration.ofMinutes(10) : taskDeadline;
        scanInterval = scanInterval == null ? Duration.ofSeconds(2) : scanInterval;
        maxAttemptsPerJob = maxAttemptsPerJob == null || maxAttemptsPerJob <= 0 ? 3 : maxAttemptsPerJob;
    }

    /** 解析白名单；空白项被忽略。 */
    public List<String> allowedWorkerIds() {
        if (allowedIds.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(allowedIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }}
