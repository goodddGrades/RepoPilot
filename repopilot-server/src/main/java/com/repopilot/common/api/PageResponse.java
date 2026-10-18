package com.repopilot.common.api;

import java.util.List;

/**
 * 分页响应体，作为 {@link ApiResponse#data()} 的内容。
 *
 * <p>字段名与契约一致：`items/page/pageSize/total`。总数与当页数据必须来自同一次查询口径，
 * 否则会出现"总数 0 但有 10 条"的错位。
 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long total) {
        return new PageResponse<>(items, query.page(), query.pageSize(), total);
    }

    public static <T> PageResponse<T> empty(PageQuery query) {
        return new PageResponse<>(List.of(), query.page(), query.pageSize(), 0L);
    }
}
