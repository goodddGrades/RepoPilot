package com.repopilot.modules.trace.service;

import com.repopilot.common.cursor.CursorSigner;
import com.repopilot.common.exception.BusinessException;

import java.time.Instant;

/**
 * Trace 分页游标：{@code (occurred_at, id)} 的键集位置 + 归属校验。
 *
 * <p>契约要求游标编码"这两个字段、Run ID 及过滤条件摘要并签名"。把 Run 与过滤条件一起编进去，
 * 是为了让三种误用在读取前就被拒绝，而不是悄悄返回另一批数据：
 * <ul>
 *   <li>拿 A 的游标查 B —— 400；</li>
 *   <li>换了 attemptId / eventType / allAttempts 却复用旧游标 —— 400（必须从第一页重查）；</li>
 *   <li>改过游标内容 —— 签名不匹配，400。</li>
 * </ul>
 *
 * <p>排序键用 {@code occurred_at, id}，不用数据库自增或 created_at：迟到事件可能写入旧时间段，
 * created_at 会把它排到末尾，而 occurred_at 才是事件真实发生顺序；同一毫秒用 id 做稳定平局，
 * 保证翻页不重不漏。
 */
record TraceCursor(String runId, String scopeHash, Instant occurredAt, String id) {

    private static final String VERSION = "v1";
    private static final char SEP = '|';

    static TraceCursor of(String runId, String scopeHash, Instant occurredAt, String id) {
        return new TraceCursor(runId, scopeHash, occurredAt, id);
    }

    String encode(CursorSigner signer) {
        String payload = VERSION + SEP + runId + SEP + scopeHash + SEP + occurredAt.toEpochMilli()
                + SEP + id;
        return signer.sign(payload);
    }

    static TraceCursor decode(CursorSigner signer, String token, String expectedRunId,
                              String expectedScopeHash) {
        String payload = signer.verify(token);
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            throw BusinessException.field("cursor", "游标版本不受支持，请从第一页重新查询");
        }
        if (!expectedRunId.equals(parts[1])) {
            // 跨 Run 复用：直接拒绝，绝不把另一条轨迹的边界当成这条的
            throw BusinessException.field("cursor", "游标不属于当前 Run，请从第一页重新查询");
        }
        if (!expectedScopeHash.equals(parts[2])) {
            throw BusinessException.field("cursor", "过滤条件已变化，游标不能复用，请从第一页重新查询");
        }
        long millis;
        try {
            millis = Long.parseLong(parts[3]);
        } catch (NumberFormatException ex) {
            throw BusinessException.field("cursor", "游标内容不合法，请从第一页重新查询");
        }
        if (parts[4].isBlank()) {
            throw BusinessException.field("cursor", "游标内容不合法，请从第一页重新查询");
        }
        return new TraceCursor(expectedRunId, expectedScopeHash,
                Instant.ofEpochMilli(millis), parts[4]);
    }
}
