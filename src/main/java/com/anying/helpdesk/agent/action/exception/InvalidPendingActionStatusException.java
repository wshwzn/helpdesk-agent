package com.anying.helpdesk.agent.action.exception;

import com.anying.helpdesk.agent.action.domain.PendingActionStatus;

public class InvalidPendingActionStatusException extends RuntimeException {

    /**
     * 创建待确认操作状态不允许当前业务动作的异常。
     */
    public InvalidPendingActionStatusException(String operation, PendingActionStatus status) {
        super("cannot " + operation + " pending action in status " + status);
    }
}
