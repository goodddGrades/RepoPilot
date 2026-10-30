package com.repopilot.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.reliability.entity.ApiIdempotencyKey;
import com.repopilot.common.reliability.mapper.ApiIdempotencyKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 请求幂等：同一个 {@code Idempotency-Key} 只产生一个资源。
 *
 * <p>生命周期：
 * <ol>
 *   <li>业务事务开始时先 {@link #replay}（本事务内查）——命中直接返回，不执行业务；</li>
 *   <li>执行业务写入；</li>
 *   <li>同一事务内 {@link #record} 写回执；事务回滚则回执一起消失，不会出现"回执说成功、数据没写"。</li>
 * </ol>
 *
 * <p>并发同 key：两个请求都查不到回执，都会尝试写入，其中一个在业务唯一键上失败。
 * 此时 {@link #execute} 捕获 {@link DuplicateKeyException}，**另开新事务**重新读回执
 * （当前事务的快照读不到别的会话刚提交的行），读到就复用，读不到再按资源冲突返回。
 * 不用"重试 N 次"，因为那不是幂等，只是把竞态窗口缩小了一点。
 */
@Service
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_KEY_LENGTH = 128;

    private final ApiIdempotencyKeyMapper mapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiredTx;
    private final TransactionTemplate newTx;

    public IdempotencyService(ApiIdempotencyKeyMapper mapper,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.requiredTx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 在当前事务内查回执并校验摘要。
     *
     * @return 命中的结果；未命中返回 empty
     * @throws BusinessException 同 key 不同摘要（{@code IDEMPOTENCY_CONFLICT}，409）
     */
    public <T> Optional<IdempotentResult<T>> replay(String scope, String key, String requestHash, Class<T> type) {
        Optional<ApiIdempotencyKey> found = mapper.findByScopeAndKey(scope, key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toResult(found.get(), requestHash, type));
    }

    /**
     * 写回执。必须与业务写入在同一事务内调用。
     *
     * @param data 已序列化前的业务响应，用于重放
     */
    public void record(String scope, String key, String requestHash,
                       String resourceType, String resourceId, int httpStatus, Object data) {
        Instant now = Instant.now();
        ApiIdempotencyKey entity = new ApiIdempotencyKey();
        entity.setId(Ulid.newId());
        entity.setScope(scope);
        entity.setIdempotencyKey(key);
        entity.setRequestHash(requestHash);
        entity.setResourceType(resourceType);
        entity.setResourceId(resourceId);
        entity.setHttpStatus(httpStatus);
        entity.setResponseJson(data == null ? objectMapper.nullNode() : objectMapper.valueToTree(data));
        entity.setExpiresAt(now.plus(TTL));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
    }

    /**
     * 执行一次幂等动作。
     *
     * <p>正常路径直接执行；只有并发撞唯一键时才去读别人已提交的回执。
     * 这样常温和并发走的是同一条业务代码，不会出现"并发场景走特殊分支"的隐患。
     */
    public <T> IdempotentResult<T> execute(String scope, String key, String requestHash,
                                           Class<T> type, Supplier<IdempotentResult<T>> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException ex) {
            Optional<IdempotentResult<T>> replayed = replayInNewTransaction(scope, key, requestHash, type);
            if (replayed.isPresent()) {
                return replayed.get();
            }
            // 说明撞的不是幂等键，而是业务唯一键（如 code 重复）：交给全局处理器转 409。
            throw ex;
        }
    }

    /** 新事务读回执：必须能看到其他会话已提交的行。 */
    public <T> Optional<IdempotentResult<T>> replayInNewTransaction(String scope, String key,
                                                                    String requestHash, Class<T> type) {
        Optional<ApiIdempotencyKey> found = newTx.execute(status -> mapper.findByScopeAndKey(scope, key));
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toResult(found.get(), requestHash, type));
    }

    public String scope(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private <T> IdempotentResult<T> toResult(ApiIdempotencyKey entity, String requestHash, Class<T> type) {
        if (!entity.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key 已用于不同的请求内容");
        }
        if (entity.getIdempotencyKey().length() > MAX_KEY_LENGTH) {
            throw BusinessException.field("Idempotency-Key", "Idempotency-Key 长度超限");
        }
        T data = null;
        JsonNode node = entity.getResponseJson();
        if (node != null && !node.isNull()) {
            data = objectMapper.convertValue(node, type);
        }
        return new IdempotentResult<>(data, entity.getHttpStatus(), true);
    }
}
