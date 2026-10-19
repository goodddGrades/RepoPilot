package com.repopilot.modules.annotation.service;

import com.repopilot.modules.annotation.mapper.AnnotationRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 已审核人工结论的只读门面（TASK-010 新增，供报告的"已审核标注视图"使用）。
 *
 * <p>报告有两种视图，差别必须显式：
 * <ul>
 *   <li>{@code AUTOMATIC}：只看 {@code evaluation_results}；</li>
 *   <li>{@code REVIEWED}：人工复核通过处以人工结论为准。</li>
 * </ul>
 *
 * <p>关键约束：<b>人工结论永远不写回 {@code evaluation_results}</b>。这里返回的是
 * 只读视图，报告在内存里做覆盖，数据库里的自动结论与它的生成来源（Judge 版本、
 * 提示词、证据）始终完整保留——否则"机器当时判了什么"就再也查不到了。
 */
@Service
public class ReviewedVerdictQueryService {

    /**
     * 一条已审核人工结论。
     *
     * @param slotKey   覆盖目标的键：Job 级为 {@code jobId}，Run 级为 {@code runId}
     * @param runId     所属 Run
     * @param jobId     绑定的 Job；Run 级复核任务为 {@code null}（表示覆盖该 Run 全部算子）
     * @param verdict   人工结论；已是字典内取值
     * @param reviewedAt 审核时间
     */
    public record Row(String slotKey, String runId, String jobId, String verdict,
                      java.time.Instant reviewedAt) {
    }

    private final AnnotationRecordMapper recordMapper;

    public ReviewedVerdictQueryService(AnnotationRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /**
     * 某实验下全部已审核通过的人工结论，按 {@code (runId, 审核时间)} 升序。
     *
     * <p>同一槽位有多条 REVIEW（退回再提交）时，调用方取最后一条即为最终结论；
     * 顺序放在这里保证，调用方不需要自己排序。
     */
    @Transactional(readOnly = true)
    public List<Row> reviewedVerdictsOfExperiment(String experimentId) {
        return recordMapper.listReviewedVerdictsOfExperiment(experimentId).stream()
                .map(row -> new Row(
                        row.slotJobId != null ? row.slotJobId : row.slotRunId,
                        row.slotRunId,
                        row.slotJobId,
                        row.verdict,
                        row.createdAt))
                .toList();
    }
}