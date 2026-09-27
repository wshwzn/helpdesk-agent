package com.anying.helpdesk.agent.action.exception;

public class ConcurrentPendingActionUpdateException extends RuntimeException {

    /**
     * 创建待确认操作已被其他请求修改的并发异常。
     */
    public ConcurrentPendingActionUpdateException(Long id) {
        super("pending action was modified concurrently: " + id);
    }
}
