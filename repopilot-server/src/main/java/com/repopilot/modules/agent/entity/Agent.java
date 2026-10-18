package com.repopilot.modules.agent.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * Agent身份，不持有运行密钥。
 *
 * <p>对应表 {@code agents}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class Agent {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 唯一业务标识，大小写敏感 */
    private String code;
    /** 名称 */
    private String name;
    /** 说明 */
    private String description;
    /** MOCK或CODING等注册类型，服务层白名单 */
    private String agentType;
    /** 生命周期 */
    private LifecycleStatus status;
    /** UTC创建时间 */
    private Instant createdAt;
    /** UTC更新时间 */
    private Instant updatedAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public LifecycleStatus getStatus() {
        return status;
    }

    public void setStatus(LifecycleStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
