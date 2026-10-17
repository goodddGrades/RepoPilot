/**
 * Agent 管理模块（V1 未实现）。
 *
 * <p>职责边界（后续任务实现）：Agent 与不可变 AgentVersion 的创建、配置快照、归档，
 * 以及 Agent 历史实验查询。Runner 类型（MOCK/CODEX/CUSTOM）在此模块内定义，
 * 但 V1 只允许 MOCK。
 *
 * <p>模块内分层约定：{@code controller} / {@code application} / {@code domain} /
 * {@code infrastructure}。其他模块只能调用本模块 {@code application} 层的服务接口。
 */
package com.repopilot.modules.agent;
