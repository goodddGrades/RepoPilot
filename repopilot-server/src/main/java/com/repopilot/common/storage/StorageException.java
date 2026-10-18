package com.repopilot.common.storage;

/** 存储层故障（磁盘不可写、对象键非法等）。由业务层转换为 503 或 500，不直接暴露给调用方。 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
