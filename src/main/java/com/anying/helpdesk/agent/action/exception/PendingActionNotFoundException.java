package com.anying.helpdesk.agent.action.exception;

public class PendingActionNotFoundException extends RuntimeException {

    /**
     * 创建不暴露其他客户操作是否存在的未找到异常。
     */
    public PendingActionNotFoundException(Long id) {
        super("pending action not found: " + id);
    }
}
