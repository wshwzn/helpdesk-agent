package com.anying.helpdesk.agent.action.exception;

public class PendingActionExpiredException extends RuntimeException {

    /**
     * 创建草稿已超过客户确认期限的异常。
     */
    public PendingActionExpiredException(Long id) {
        super("pending action has expired: " + id);
    }
}
