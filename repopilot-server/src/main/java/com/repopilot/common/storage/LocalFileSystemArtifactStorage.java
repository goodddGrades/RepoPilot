package com.repopilot.common.storage;

import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 本地文件系统实现：把上传内容写到配置的根目录下。
 *
 * <p>几条刻意的设计：
 * <ul>
 *   <li><b>先写临时文件再原子改名</b>：进程在中途被终止时不会留下"看起来完整、实际截断"的对象。
 *       若不做这一步，重启后可能读到半个 JSONL 文件。</li>
 *   <li><b>对象键含 ULID 与日期分层</b>：单目录文件数不会无限膨胀，且键唯一不依赖文件名。</li>
 *   <li><b>路径必须落在根目录内</b>：写入前用规范化绝对路径做前缀检查，
 *       即使调用方（或将来某个实现）传入了奇怪的键也拒绝。</li>
 *   <li><b>根目录不进 Git</b>：默认 {@code var/storage}，已在 {@code .gitignore} 中。</li>
 * </ul>
 */
public class LocalFileSystemArtifactStorage implements ArtifactStorage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final String PROVIDER = "LOCAL";

    private final Path root;
    private final long maxBytes;

    public LocalFileSystemArtifactStorage(StorageProperties.Local config) {
        this.root = Path.of(config.getRoot()).toAbsolutePath().normalize();
        this.maxBytes = config.getMaxBytes();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("无法创建存储根目录: " + root, ex);
        }
    }

    @Override
    public StoredObject put(String prefix, byte[] content, String contentType) {
        if (content == null) {
            throw new StorageException("写入内容为空");
        }
        if (content.length > maxBytes) {
            throw new StorageException("内容超过存储层上限 " + maxBytes + " 字节");
        }

        String objectKey = prefix + "/" + LocalDate.now().format(DAY) + "/" + Ulid.newId();
        Path target = resolveInsideRoot(objectKey);
        Path parent = target.getParent();
        Path temp = parent.resolve("." + target.getFileName() + ".tmp");

        try {
            Files.createDirectories(parent);
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                // 某些文件系统（网络盘）不支持原子改名；退化为普通移动并立即校验长度。
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(target);
            if (size != content.length) {
                Files.deleteIfExists(target);
                throw new StorageException("写入后长度不一致，已删除不完整对象: " + objectKey);
            }
            return new StoredObject(objectKey, PROVIDER, size, Sha256.ofBytes(content));
        } catch (IOException ex) {
            deleteQuietly(temp);
            deleteQuietly(target);
            throw new StorageException("写入对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolveInsideRoot(objectKey));
        } catch (IOException ex) {
            throw new StorageException("删除对象失败: " + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolveInsideRoot(objectKey));
    }

    @Override
    public byte[] get(String objectKey) {
        Path path = resolveInsideRoot(objectKey);
        try {
            if (!Files.isRegularFile(path)) {
                throw new StorageException("对象不存在: " + objectKey);
            }
            long size = Files.size(path);
            if (size > maxBytes) {
                // 存储层上限之外的巨大对象不应被读进内存；这通常意味着对象键被替换过
                throw new StorageException("对象超过读取上限 " + maxBytes + " 字节: " + objectKey);
            }
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new StorageException("读取对象失败: " + objectKey, ex);
        }
    }

    /** 根目录绝对路径，供运维排查与测试断言（不参与业务逻辑）。 */
    public Path root() {
        return root;
    }

    private Path resolveInsideRoot(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new StorageException("对象键为空");
        }
        if (objectKey.startsWith("/") || objectKey.contains("..") || objectKey.indexOf('\\') >= 0) {
            throw new StorageException("非法对象键: " + objectKey);
        }
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw new StorageException("对象键越出存储根目录: " + objectKey);
        }
        return path;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不覆盖原始异常：原始异常才是有诊断价值的那个。
        }
    }
}
