package com.repopilot.modules.dataset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.reliability.mapper.ApiIdempotencyKeyMapper;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.StorageException;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import com.repopilot.modules.dataset.mapper.DatasetVersionMapper;
import com.repopilot.modules.importing.mapper.ImportBatchMapper;
import com.repopilot.modules.trace.mapper.ArtifactMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入失败时的文件清理（ArtifactStorage 硬性要求第 3 条）。
 *
 * <p>数据库事务回滚不了文件系统，所以"事务失败后删掉刚写入的对象"必须是一个被测试盯住的行为，
 * 而不是注释里的一句话。这里用替身把落库步骤打断在"文件已写入、批次未落库"之间——
 * 这正是真实故障（唯一键冲突、并发幂等、连接中断）发生的位置。
 */
class DatasetImportCleanupTest {

    private static final String VERSION_ID = "01J0000000000000000000AB";
    private static final String OBJECT_KEY = "imports/2026/10/19/01J0000000000000000000CD";
    private static final byte[] CONTENT = ("{\"schemaVersion\":1,\"caseKey\":\"a\",\"taskType\":\"AGENT_TASK\","
            + "\"input\":{}}\n").getBytes(StandardCharsets.UTF_8);

    private final DatasetVersionGuard guard = mock(DatasetVersionGuard.class);
    private final DatasetCaseMapper caseMapper = mock(DatasetCaseMapper.class);
    private final DatasetVersionMapper versionMapper = mock(DatasetVersionMapper.class);
    private final ArtifactMapper artifactMapper = mock(ArtifactMapper.class);
    private final ImportBatchMapper importBatchMapper = mock(ImportBatchMapper.class);
    private final DatasetCaseBatchWriter caseWriter = mock(DatasetCaseBatchWriter.class);
    private final ArtifactStorage storage = mock(ArtifactStorage.class);

    @Test
    void rollbackAfterStoringShouldDeleteTheWrittenObject() {
        givenDraftVersion();
        when(storage.put(anyString(), any(), anyString())).thenReturn(
                new ArtifactStorage.StoredObject(OBJECT_KEY, "LOCAL", CONTENT.length, Sha256.ofBytes(CONTENT)));
        when(artifactMapper.insert(any())).thenReturn(1);
        // 批次落库失败：文件此时已经写进去了，必须被清理
        when(importBatchMapper.insert(any())).thenThrow(new IllegalStateException("db is gone"));

        DatasetImportService service = newService(storage);

        assertThatThrownBy(() -> service.importCases(VERSION_ID, multipart(), "idem-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete(OBJECT_KEY);
        verify(caseWriter, never()).insertAll(anyList());
    }

    @Test
    void storageFailureShouldReturn503AndWriteNothing() {
        givenDraftVersion();
        when(storage.put(anyString(), any(), anyString()))
                .thenThrow(new StorageException("disk full"));

        DatasetImportService service = newService(storage);

        assertThatThrownBy(() -> service.importCases(VERSION_ID, multipart(), "idem-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        // 没写成功就不该去删；删一个不存在的对象只会在日志里制造噪音
        verify(storage, never()).delete(anyString());
        verify(artifactMapper, never()).insert(any());
        verify(caseWriter, never()).insertAll(anyList());
    }

    private void givenDraftVersion() {
        DatasetVersion version = new DatasetVersion();
        version.setId(VERSION_ID);
        version.setStatus(VersionStatus.DRAFT);
        when(guard.lockEditable(VERSION_ID)).thenReturn(version);
        when(caseMapper.findExistingKeys(anyString(), anyList())).thenReturn(List.of());
    }

    private MockMultipartFile multipart() {
        return new MockMultipartFile("file", "cases.jsonl", "application/x-ndjson", CONTENT);
    }

    private DatasetImportService newService(ArtifactStorage artifactStorage) {
        ApiIdempotencyKeyMapper keyMapper = mock(ApiIdempotencyKeyMapper.class);
        when(keyMapper.findByScopeAndKey(anyString(), anyString())).thenReturn(Optional.empty());

        PlatformTransactionManager transactionManager = new NoopTransactionManager();
        IdempotencyService idempotency =
                new IdempotencyService(keyMapper, new ObjectMapper(), transactionManager);

        return new DatasetImportService(guard, new DatasetCaseValidator(), caseWriter, caseMapper,
                versionMapper, artifactMapper, importBatchMapper, artifactStorage,
                idempotency, transactionManager);
    }

    /**
     * 不做任何隔离的事务管理器：{@code TransactionTemplate.execute} 仍然会在回调抛异常时
     * 调用 rollback 并重新抛出，这正是本测试要复现的那一段行为。
     */
    private static final class NoopTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
