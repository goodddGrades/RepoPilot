package com.repopilot.it;

import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.LocalFileSystemArtifactStorage;
import com.repopilot.modules.trace.service.ArtifactMaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-006 附件边界验收：下载一致性与孤儿清理。
 *
 * <p>这两点都属于"证据可信"：内容变了必须报错而不是照常下载；存储里有、元数据里没有的
 * 文件要清掉，但**有元数据的一律保留**。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-006 附件访问与清理")
class ArtifactAccessIT extends AbstractExecutionIT {

    @Autowired
    private ArtifactStorage storage;

    @Autowired
    private ArtifactMaintenanceService maintenanceService;

    private String upload(String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("artifactType", "EVIDENCE");
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "evidence.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/artifacts", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().value())
                .as("上传应返回 201: %s", response.getBody()).isEqualTo(201);
        return dataId(response);
    }

    @Test
    @DisplayName("对象被替换后下载返回 409，且不泄漏存储路径")
    void downloadShouldRejectObjectThatNoLongerMatchesMetadata() throws IOException {
        String artifactId = upload("原始证据内容");
        String objectKey = jdbc.queryForObject("SELECT object_key FROM artifacts WHERE id = ?",
                String.class, artifactId);

        LocalFileSystemArtifactStorage local = (LocalFileSystemArtifactStorage) storage;
        Path file = local.root().resolve(objectKey);
        Files.write(file, "被替换的内容".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = get("/api/v1/artifacts/" + artifactId + "/download");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        String body = String.valueOf(response.getBody());
        assertThat(body).contains("RESOURCE_CONFLICT");
        // 存储路径与内部异常细节不得出现在响应里
        assertThat(body).doesNotContain(objectKey);
        assertThat(body).doesNotContain(local.root().toString());

        // 恢复对象，避免污染后续断言
        Files.write(file, "原始证据内容".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/api/v1/artifacts/" + artifactId + "/download").getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("清理只删无元数据的孤儿对象，有登记的证据保留")
    void cleanupShouldDeleteOnlyOrphanObjects() {
        // 直接写对象、不登记元数据：这就是"上传后事务失败"留下的孤儿
        ArtifactStorage.StoredObject orphan = storage.put("artifacts",
                "orphan".getBytes(StandardCharsets.UTF_8), "text/plain");
        String referencedId = upload("有元数据的证据");
        String referencedKey = jdbc.queryForObject("SELECT object_key FROM artifacts WHERE id = ?",
                String.class, referencedId);

        // 把宽限期设为未来：只留下"元数据存在"这一条判定标准
        int deleted = maintenanceService.deleteOrphanObjects(Instant.now().plusSeconds(3600));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(storage.exists(orphan.objectKey())).isFalse();
        assertThat(storage.exists(referencedKey)).isTrue();
        // 元数据与可下载性都不受影响
        assertThat(count("SELECT COUNT(*) FROM artifacts WHERE id = ?", referencedId)).isEqualTo(1);
        assertThat(get("/api/v1/artifacts/" + referencedId + "/download").getStatusCode().value())
                .isEqualTo(200);
    }
}
