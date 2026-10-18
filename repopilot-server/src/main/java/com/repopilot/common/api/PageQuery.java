package com.repopilot.common.api;

import com.repopilot.common.exception.BusinessException;

/**
 * 分页请求参数。
 *
 * <p>约定见 {@code docs/architecture/04-api-contract.md}：`page >= 1`，`pageSize` 默认 20、
 * 取值 1~100。越界直接 400，不静默截断——静默截断会让调用方以为拿到了全部数据。
 */
public record PageQuery(int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public PageQuery {
        if (page < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page 必须大于等于 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "pageSize 必须在 1~" + MAX_PAGE_SIZE + " 之间");
        }
    }

    public static PageQuery of(Integer page, Integer pageSize) {
        return new PageQuery(page == null ? 1 : page,
                pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
    }

    /** LIMIT 偏移量；page 从 1 开始，因此是 (page - 1) * pageSize。 */
    public int offset() {
        return (page - 1) * pageSize;
    }
}
