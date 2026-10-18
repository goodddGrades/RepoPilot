package com.repopilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * RepoPilot 核心后端启动类。
 *
 * <p>本工程采用<b>模块化单体</b>架构：单个 Spring Boot 进程，按业务模块划分包边界，
 * 模块之间通过应用服务接口协作，禁止跨模块直接访问对方 Mapper 或表。
 * 不使用微服务、Spring Cloud 或任何分布式组件。
 *
 * <p>包结构约定（详见 {@code com.repopilot.modules} 下的 package-info）：
 * <pre>
 * com.repopilot
 *   ├── common      通用能力：统一响应、异常、健康检查、ID/状态/持久化基础
 *   └── modules     业务模块（agent / dataset / evaluationconfig / experiment /
 *                   trace / evaluation / annotation / importing）
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan({"com.repopilot.modules", "com.repopilot.common.reliability"})
public class RepoPilotServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoPilotServerApplication.class, args);
    }
}
