package com.repopilot.common.web;

import com.repopilot.common.api.RequestIdHolder;
import com.repopilot.common.id.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个请求分配/透传追踪 ID，并写入 MDC。
 *
 * <p>规则：
 * <ul>
 *   <li>请求头 {@code X-Request-Id} 合法（ASCII 字母、数字、下划线、短横线，长度 1~64）时直接复用；</li>
 *   <li>否则生成新的 ULID；</li>
 *   <li>响应头回写最终使用的 ID，便于客户端与服务端日志对齐；</li>
 *   <li>请求结束后清理 MDC，避免线程池串号。</li>
 * </ul>
 *
 * <p>不校验入参是否为 ULID：requestId 是不透明值，允许上游网关传入自有格式；
 * 只有服务端自己生成时才使用 ULID（按时间可排序，便于日志按时间聚合）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader(RequestIdHolder.HEADER_NAME);
        String requestId = isValid(inbound) ? inbound : Ulid.newId();

        MDC.put(RequestIdHolder.MDC_KEY, requestId);
        response.setHeader(RequestIdHolder.HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdHolder.MDC_KEY);
        }
    }

    private boolean isValid(String candidate) {
        if (!StringUtils.hasText(candidate) || candidate.length() > MAX_LENGTH) {
            return false;
        }
        return candidate.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}
