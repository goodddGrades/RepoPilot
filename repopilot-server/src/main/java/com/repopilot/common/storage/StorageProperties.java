package com.repopilot.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置。
 *
 * <pre>
 * repopilot:
 *   storage:
 *     type: local              # V1 仅 local；minio 留待后续
 *     local:
 *       root: var/storage      # 必须是不进版本库的目录
 *       max-bytes: 67108864
 * </pre>
 */
@ConfigurationProperties(prefix = "repopilot.storage")
public class StorageProperties {

    private String type = "local";
    private final Local local = new Local();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public static class Local {

        private String root = "var/storage";
        private long maxBytes = 64L * 1024 * 1024;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
