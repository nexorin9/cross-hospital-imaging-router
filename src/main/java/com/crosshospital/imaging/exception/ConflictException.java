package com.crosshospital.imaging.exception;

/**
 * 资源冲突异常（HTTP 409 语义）。
 *
 * <p>典型场景：
 * <ul>
 *   <li>{@code peer_aet} 已存在（创建节点）</li>
 *   <li>并发请求导致唯一键冲突（创建任务）</li>
 * </ul>
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}