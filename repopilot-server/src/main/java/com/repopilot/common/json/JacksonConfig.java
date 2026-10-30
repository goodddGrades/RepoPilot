package com.repopilot.common.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 让 HTTP 层的 JSON 解析与 {@link CanonicalJson} 采用同一套数字语义。
 *
 * <p>不做这件事会出现一个很难查的偏差：请求体里的 {@code 0.10} 在进入 Controller 时就已经
 * 被解析成 {@code 0.1}，之后无论 {@code CanonicalJson} 多正确，写进 {@code config_hash}
 * 的内容都不是用户提交的内容。也就是说"保留标度"必须从入口开始，而不是只在算 hash 时。
 *
 * <p>同时打开 {@code WRITE_BIGDECIMAL_AS_PLAIN}，避免响应里出现 {@code 1E+2} 这种形式。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer repopilotJacksonCustomizer() {
        return builder -> {
            builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            builder.featuresToEnable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
            builder.postConfigurer(mapper ->
                    mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)));
        };
    }
}
