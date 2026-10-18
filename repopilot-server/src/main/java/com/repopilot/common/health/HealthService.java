package com.repopilot.common.health;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 聚合各依赖组件的健康状态。
 *
 * <p>聚合规则：
 * <ul>
 *   <li>任一必需组件 DOWN → 整体 DOWN；</li>
 *   <li>存在非必需组件异常 → 整体 DEGRADED；</li>
 *   <li>其余 → UP。</li>
 * </ul>
 * 每次调用都真实探测一次，不做缓存，避免掩盖瞬时故障。
 */
@Service
public class HealthService {

    private final List<HealthProbe> probes;
    private final Environment environment;

    public HealthService(List<HealthProbe> probes, Environment environment) {
        this.probes = List.copyOf(probes);
        this.environment = environment;
    }

    public HealthSnapshot snapshot() {
        List<ComponentHealth> components = probes.stream()
                .map(HealthProbe::probe)
                .toList();
        return new HealthSnapshot(
                aggregate(components),
                environment.getProperty("spring.application.name", "repopilot-server"),
                environment.getProperty("spring.application.version", "unknown"),
                String.join(",", Arrays.asList(environment.getActiveProfiles())),
                Instant.now(),
                components);
    }

    private HealthStatus aggregate(List<ComponentHealth> components) {
        boolean requiredDown = components.stream()
                .anyMatch(c -> c.required() && c.status() == HealthStatus.DOWN);
        if (requiredDown) {
            return HealthStatus.DOWN;
        }
        boolean optionalDegraded = components.stream()
                .anyMatch(c -> !c.required()
                        && (c.status() == HealthStatus.DOWN || c.status() == HealthStatus.DEGRADED));
        return optionalDegraded ? HealthStatus.DEGRADED : HealthStatus.UP;
    }
}
