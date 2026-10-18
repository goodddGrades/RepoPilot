package com.repopilot.common.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储装配。
 *
 * <p>{@code repopilot.storage.type} 只接受 {@code local}。将来接 MinIO 时在这里加一个分支，
 * 其余代码（`DatasetImportService` 等）不需要改 —— 它们只依赖 {@link ArtifactStorage}。
 * 未知类型直接启动失败，而不是静默回退到本地：静默回退会让人以为文件写到对象存储了。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    public ArtifactStorage artifactStorage(StorageProperties properties) {
        String type = properties.getType() == null ? "local" : properties.getType().trim().toLowerCase();
        if ("local".equals(type)) {
            return new LocalFileSystemArtifactStorage(properties.getLocal());
        }
        throw new IllegalStateException("不支持的存储类型: " + properties.getType());
    }
}
