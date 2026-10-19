package com.repopilot.modules.experiment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.worker.WorkerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Worker 内部接口的 Bearer 凭证校验。
 *
 * <p>网络可达不等于有授权：{@code /internal/v1} 允许领取任务与写入执行结果，
 * 如果只靠"这个端口在内网"，任何能访问后端的人都能伪造一次成功。
 *
 * <p>{@code repopilot.worker.token} 未配置时<b>一律拒绝</b>（fail closed），
 * 而不是"没配就不校验"——后者会在部署时悄悄变成一个未鉴权的执行入口。
 */
@Component
public class WorkerAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/internal/v1";

    private final WorkerProperties props;
    private final ObjectMapper objectMapper;

    public WorkerAuthFilter(WorkerProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String configured = props.token();
        if (configured == null || configured.isBlank()) {
            reject(response, "Worker 凭证未配置，内部接口不可用");
            return;
        }
        String authorization = request.getHeader("Authorization");
        String presented = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        if (!constantTimeEquals(configured, presented)) {
            reject(response, "Worker 凭证无效");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(ErrorCode.UNAUTHORIZED, message));
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
