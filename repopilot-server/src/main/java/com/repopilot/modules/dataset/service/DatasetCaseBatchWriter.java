package com.repopilot.modules.dataset.service;

import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 样本批量写入。
 *
 * <p>必须分片：MySQL 单条语句的占位符上限是 65535，而一行样本占 8 个占位符，
 * 10000 行的一次性 INSERT 会直接抛 "Prepared statement contains too many placeholders"。
 * 分片大小取 500（4000 个占位符），既远离上限，也不会让单条 SQL 长到难以在日志里阅读。
 *
 * <p>为什么不用 {@code ExecutorType.BATCH} 逐条 addBatch：那仍然是一次往返一行，
 * 10000 行就是 10000 次网络交互；多值 INSERT 是 20 次。
 */
@Component
public class DatasetCaseBatchWriter {

    private static final int CHUNK_SIZE = 500;

    private final DatasetCaseMapper caseMapper;

    public DatasetCaseBatchWriter(DatasetCaseMapper caseMapper) {
        this.caseMapper = caseMapper;
    }

    /** 写入全部样本；调用方负责事务与唯一性校验。 */
    public void insertAll(List<DatasetCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return;
        }
        for (int start = 0; start < cases.size(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, cases.size());
            caseMapper.batchInsert(cases.subList(start, end));
        }
    }
}
