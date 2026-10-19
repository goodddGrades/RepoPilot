package com.repopilot.common.registry;

import com.repopilot.common.state.RunnerType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 已注册的 Runner 与能力声明。
 *
 * <p>V1 只注册 {@link RunnerType#MOCK}。CODEX / CUSTOM 在枚举与数据库里存在，但**没有适配器**：
 * 如果这里把它们当作"可用"，实验会进入 QUEUED 后永远领不到任务，或者在 Worker 侧跑出一个
 * 未经验证的命令。启动时直接拒绝，比"先跑起来再说"诚实。
 *
 * <p>能力字符串格式遵循 API 契约：{@code RUN:MOCK}、{@code EVAL:<evaluatorKey>}。
 * TASK-004 只消费 {@code RUN:*}；TASK-007 起 {@code EVAL:*} 由
 * {@link com.repopilot.modules.evaluation.service.EvaluationClaimService} 消费。
 */
@Component
public class RunnerRegistry {

    private static final Set<RunnerType> REGISTERED = EnumSet.of(RunnerType.MOCK);
    private static final Set<TaskType> MOCK_TASKS =
            EnumSet.of(TaskType.AGENT_TASK, TaskType.LLM_CALL);

    /** 无状态判断：供 {@link WorkerCapabilities} 这类静态解析器复用，避免为了校验格式去建 Bean。 */
    public static boolean isRegisteredType(RunnerType type) {
        return type != null && REGISTERED.contains(type);
    }

    public boolean isRegistered(RunnerType type) {
        return isRegisteredType(type);
    }

    public Set<TaskType> supportedTasks(RunnerType type) {
        return type == RunnerType.MOCK ? Set.copyOf(MOCK_TASKS) : Set.of();
    }

    public boolean supports(RunnerType type, TaskType taskType) {
        return isRegistered(type) && supportedTasks(type).contains(taskType);
    }

    public String capability(RunnerType type) {
        return "RUN:" + type.name();
    }

    /**
     * 从 Worker 声明的能力里解析出可领取的 Runner 类型。
     *
     * <p>未知格式直接 400：把拼错的能力静默忽略，会让 Worker 一直"领取成功但什么也拿不到"，
     * 排查成本远高于启动时失败。
     *
     * <p>TASK-007 起完整解析（含 {@code EVAL:*}）由 {@link WorkerCapabilities} 负责；
     * 本方法保留给只关心执行能力的调用方。
     */
    public List<RunnerType> parseCapabilities(List<String> capabilities) {
        return WorkerCapabilities.parse(capabilities).runners();
    }
}
