package com.repopilot.modules.trace.service;

import com.repopilot.common.cursor.CursorSigner;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.modules.trace.dto.TraceDtos;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.entity.RunAttempt;
import com.repopilot.modules.trace.entity.TraceEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trace 查询：以 {@code (occurredAt, id)} 为游标的稳定分页。
 *
 * <p>在线与离线共用同一条读取路径，差异只在"读哪个 Attempt"：
 * <ul>
 *   <li>在线：默认读 accepted Attempt；执行中退化为 active Attempt（此时还没有 accepted）。</li>
 *   <li>离线：没有 RunAttempt，事件本身也没有 {@code run_attempt_id}，因此不做 Attempt 过滤。
 *       绝不为了"看起来统一"而给离线 Run 造一条 Attempt。</li>
 *   <li>{@code allAttempts=true}：只在排障时使用，在线会把所有失败尝试混进来；
 *       它不进入自动评测输入，这一点由契约规定，这里只负责如实读取。</li>
 * </ul>
 *
 * <p>单页还受两个上界约束：条数（默认 100、最多 500）与内容体积（4 MiB）。
 * 后者是为了防止 500 条 64 KiB 的载荷把一次响应撑到几十兆；被体积截断时同样返回
 * {@code hasMore=true} 与指向最后一条的 {@code nextCursor}，调用方可以继续翻。
 */
@Service
public class TraceQueryService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /** 单页响应里 payload 的近似字符预算；超出的条目留给下一页，不静默丢弃。 */
    private static final long MAX_PAGE_PAYLOAD_CHARS = 4L * 1024 * 1024;

    /** 事件类型只允许注册表风格的大写标识；未知但形状合法的类型返回空页，而不是 400。 */
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final RunStore runStore;
    private final CursorSigner cursorSigner;

    public TraceQueryService(RunStore runStore, CursorSigner cursorSigner) {
        this.runStore = runStore;
        this.cursorSigner = cursorSigner;
    }

    public TraceDtos.EventPage events(String runId, String cursor, Integer limit,
                                      String attemptId, String eventType, Boolean allAttempts) {
        // Run 先于游标校验：不存在的 Run 应得到 404，而不是让人以为是游标问题
        EvaluationRun run = runStore.requireRun(runId);

        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw BusinessException.field("limit", "limit 必须在 1~" + MAX_LIMIT + " 之间");
        }
        String type = blankToNull(eventType);
        if (type != null && !EVENT_TYPE.matcher(type).matches()) {
            throw BusinessException.field("eventType", "eventType 只能是 1~64 位大写字母、数字或下划线");
        }
        boolean all = Boolean.TRUE.equals(allAttempts);

        Scope scope = resolveScope(run, attemptId, all, type);
        TraceCursor position = cursor == null || cursor.isBlank() ? null
                : TraceCursor.decode(cursorSigner, cursor, runId, scope.hash());

        List<TraceEvent> rows = runStore.eventPage(runId, scope.attemptId(), type,
                position == null ? null : position.occurredAt(),
                position == null ? null : position.id(),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<TraceEvent> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;
        page = trimToPayloadBudget(page);

        Set<String> ownedArtifacts = ownedArtifacts(runId, page);
        List<TraceDtos.EventView> items = page.stream()
                .map(event -> TraceDtos.EventView.from(event,
                        event.getArtifactId() != null && ownedArtifacts.contains(event.getArtifactId())
                                ? event.getArtifactId() : null))
                .toList();

        // 体积截断时有效页短于 rows.size()，仍然要给下一页游标，否则尾部数据永远读不到
        boolean truncated = page.size() < (hasMore ? pageSize : rows.size());
        String nextCursor = null;
        if ((hasMore || truncated) && !page.isEmpty()) {
            TraceEvent last = page.get(page.size() - 1);
            nextCursor = TraceCursor.of(runId, scope.hash(), last.getOccurredAt(), last.getId())
                    .encode(cursorSigner);
        }
        boolean more = hasMore || truncated;
        return new TraceDtos.EventPage(items, nextCursor, more,
                new TraceDtos.EventScope(scope.attemptId(), all, type, scope.resolvedFrom()));
    }

    private Scope resolveScope(EvaluationRun run, String attemptId, boolean all, String eventType) {
        if (attemptId != null && !attemptId.isBlank()) {
            if (all) {
                throw BusinessException.field("attemptId", "attemptId 与 allAttempts 不能同时使用");
            }
            RunAttempt attempt = runStore.findAttempt(attemptId)
                    .orElseThrow(() -> BusinessException.field("attemptId", "Attempt 不存在: " + attemptId));
            if (!run.getId().equals(attempt.getRunId())) {
                // 跨 Run 读取：既不能返回数据，也不能让人借此探测别的 Run 有哪些 Attempt
                throw BusinessException.field("attemptId", "Attempt 不属于该 Run");
            }
            return new Scope(attemptId, "REQUESTED", eventType, all);
        }
        if (all) {
            return new Scope(null, "ALL", eventType, true);
        }
        if (run.getAcceptedAttemptId() != null) {
            return new Scope(run.getAcceptedAttemptId(), "ACCEPTED", eventType, false);
        }
        if (run.getActiveAttemptId() != null) {
            return new Scope(run.getActiveAttemptId(), "ACTIVE", eventType, false);
        }
        // 离线 Run（或还没被领取的在线 Run）：没有 Attempt 可过滤
        return new Scope(null, "NONE", eventType, false);
    }

    private static List<TraceEvent> trimToPayloadBudget(List<TraceEvent> page) {
        if (page.size() <= 1) {
            return page;
        }
        long chars = 0;
        int keep = 0;
        for (TraceEvent event : page) {
            long size = event.getPayloadJson() == null ? 0 : event.getPayloadJson().toString().length();
            if (keep > 0 && chars + size > MAX_PAGE_PAYLOAD_CHARS) {
                break;
            }
            chars += size;
            keep++;
        }
        return keep < page.size() ? new ArrayList<>(page.subList(0, keep)) : page;
    }

    private Set<String> ownedArtifacts(String runId, List<TraceEvent> page) {
        Set<String> ids = new LinkedHashSet<>();
        for (TraceEvent event : page) {
            if (event.getArtifactId() != null) {
                ids.add(event.getArtifactId());
            }
        }
        return runStore.artifactIdsOwnedByRun(runId, List.copyOf(ids));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 生效范围 + 过滤条件摘要。
     *
     * <p>摘要进游标：过滤条件一变，旧游标就必须被拒绝。否则"先按 TOOL_STARTED 翻两页、
     * 再换 eventType 继续用旧游标"会从错误的位置继续读，得到看似正常却缺行的一页。
     */
    private record Scope(String attemptId, String resolvedFrom, String eventType, boolean all) {

        private String hash() {
            return Sha256.ofUtf8("attempt=" + (attemptId == null ? "-" : attemptId)
                    + ";all=" + all + ";type=" + (eventType == null ? "-" : eventType))
                    .substring(0, 16);
        }
    }
}
