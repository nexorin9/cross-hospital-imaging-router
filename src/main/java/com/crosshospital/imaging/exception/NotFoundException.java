package com.crosshospital.imaging.exception;

/**
 * 资源未找到异常（HTTP 404 语义）。
 *
 * <p>按业务层规范使用：当按主键 / 唯一键查询不到目标实体时抛出。
 * 信息科操作员看到 404 即知道是节点不存在 / AET 拼错。
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String resource, String key) {
        super(resource + " not found: " + key);
    }
}