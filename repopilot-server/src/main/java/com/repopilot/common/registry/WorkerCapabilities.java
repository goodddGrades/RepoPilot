package com.repopilot.common.registry;

import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.RunnerType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Worker 声明的能力清单。
 *
 * <p>能力字符串格式来自 API 契约：{@code RUN:MOCK}、{@code EVAL:<evaluatorKey>}。
 * TASK-007 起一次领取可以同时包含两类：执行任务与评测任务共用一个队列协议，
 * 但**能力必须分开声明**——只有声明了 {@code EVAL:output.non_empty} 的 Worker
 * 才会拿到对应的评测 Job，避免"能跑 Agent 就默认能判分"。
 *
 * <p>这里只做格式与 Runner 注册校验。{@code EVAL} 的 key 是否真的存在、
 * 是否已发布，由评测模块查库确认：common 层不依赖业务模块。
 */
public record WorkerCapabilities(List<RunnerType> runners, List<String> evaluatorKeys) {

    private static final Pattern EVALUATOR_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]{0,63}$");

    public WorkerCapabilities {
        runners = runners == null ? List.of() : List.copyOf(runners);
        evaluatorKeys = evaluatorKeys == null ? List.of() : List.copyOf(evaluatorKeys);
    }

    public static WorkerCapabilities parse(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            throw BusinessException.field("capabilities", "capabilities 不能为空");
        }
        Set<RunnerType> runners = new LinkedHashSet<>();
        Set<String> evaluatorKeys = new LinkedHashSet<>();
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
                if (!RunnerRegistry.isRegisteredType(type)) {
                    throw BusinessException.field("capabilities",
                            "Runner 未注册（V1 仅支持 MOCK）: " + name);
                }
                runners.add(type);
            } else if ("EVAL".equals(prefix)) {
                if (!EVALUATOR_KEY.matcher(name).matches()) {
                    throw BusinessException.field("capabilities",
                            "算子 key 格式不合法（应形如 output.non_empty）: " + name);
                }
                evaluatorKeys.add(name);
            } else {
                throw BusinessException.field("capabilities", "未知的能力类型: " + prefix);
            }
        }
        return new WorkerCapabilities(new ArrayList<>(runners), new ArrayList<>(evaluatorKeys));
    }

    public boolean isEmpty() {
        return runners.isEmpty() && evaluatorKeys.isEmpty();
    }
}
