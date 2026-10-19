package com.repopilot.common.storage;

/**
 * 对象存储抽象。
 *
 * <p>V1 只有本地文件系统实现（{@link LocalFileSystemArtifactStorage}）；切换 MinIO 时只需替换
 * 这个接口的实现，Dataset 业务服务不需要改动 —— 这是把它抽象出来的唯一目的。
 *
 * <p>约定：
 * <ul>
 *   <li><b>对象键由服务端生成</b>，调用方只提供前缀。用户上传的文件名不参与路径，
 *       从根上避免 {@code ../} 之类路径穿越。</li>
 *   <li>{@link #put} 在返回前必须保证内容已完整落盘；失败抛 {@link StorageException}，
 *       不留半截文件。</li>
 *   <li>调用方负责"何时删"。事务失败时必须调用 {@link #delete} 清理，
 *       否则会留下无主的 {@code artifacts} 行或孤儿文件。</li>
 * </ul>
 */
public interface ArtifactStorage {

    /**
     * 写入内容并返回存储结果。
     *
     * @param prefix      逻辑前缀，例如 {@code imports}；服务端在其下生成对象键
     * @param content     原始字节
     * @param contentType MIME 类型，仅记录，不影响存储路径
     */
    StoredObject put(String prefix, byte[] content, String contentType);

    void delete(String objectKey);

    boolean exists(String objectKey);

    /**
     * 读取对象全部字节。
     *
     * <p>TASK-005 新增：导入需要重新读取已上传的原始文件，而不是让前端再传一遍。
     * 读取失败抛 {@link StorageException}；调用方据此返回"原文件不可读"，
     * 不能把空内容当作合法文件继续解析。
     *
     * @throws StorageException 对象不存在或不可读
     */
    byte[] get(String objectKey);

    /**
     * 一次写入的结果。
     *
     * @param objectKey  服务端生成的相对键
     * @param provider   {@code LOCAL} / {@code MINIO}
     * @param sizeBytes  字节数
     * @param sha256     内容摘要（小写 64 位）
     */
    record StoredObject(String objectKey, String provider, long sizeBytes, String sha256) {
    }
}
