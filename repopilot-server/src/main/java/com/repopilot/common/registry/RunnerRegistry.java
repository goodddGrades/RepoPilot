package com.repopilot.common.registry;

import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.RunnerType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 已注册的 Runner 与能力声明。
 *
 * <p>V1 只注册 {@link RunnerType#MOCK}。CODEX / CUSTOM 在枚举与数据库里存在，但**没有适配器**：
 * 如果这里把它们当作"可用"，实验会进入 QUEUED 后永远领不到任务，或者在 Worker 侧跑出一个
 * 未经验证的命令。启动时直接拒绝，比"先跑起来再说"诚实。
 *
 * <p>能力字符串格式遵循 API 契约：{@code RUN:MOCK}、{@code EVAL:<evaluatorKey>}。
 * TASK-004 只消费 {@code RUN:*}；{@code EVAL:*} 被接受但不会产生任何任务
 * （评测算子由 TASK-007 接手）。
 */
@Component
public class RunnerRegistry {

    private static final Set<RunnerType> REGISTERED = EnumSet.of(RunnerType.MOCK);
    private static final Set<TaskType> MOCK_TASKS =
            EnumSet.of(TaskType.AGENT_TASK, TaskType.LLM_CALL);

    public boolean isRegistered(RunnerType type) {
        return type != null && REGISTERED.contains(type);
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
     */
    public List<RunnerType> parseCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            throw BusinessException.field("capabilities", "capabilities 不能为空");
        }
        Set<RunnerType> result = new LinkedHashSet<>();
        for (String raw : capabilities) {
            if (raw == null || raw.isBlank()) {
                throw BusinessException.field("capabilities", "capabilities 不能包含空值");
            }
            String value = raw.trim();
            int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                throw BusinessException.field("capabilities",
                        "能力格式必须是 TYPE:NAME，收到: " + value);
            }
            String prefix = value.substring(0, colon).toUpperCase(Locale.ROOT);
            String name = value.substring(colon + 1);
            if ("RUN".equals(prefix)) {
                RunnerType type;
                try {
                    type = RunnerType.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw BusinessException.field("capabilities", "未知的 Runner: " + name);
                }
                if (!isRegistered(type)) {
                    throw BusinessException.field("capabilities",
                            "Runner 未注册（V1 仅支持 MOCK）: " + name);
                }
                result.add(type);
            } else if (!"EVAL".equals(prefix)) {
                throw BusinessException.field("capabilities", "未知的能力类型: " + prefix);
            }
        }
        return new ArrayList<>(result);
    }
}
