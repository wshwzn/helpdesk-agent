package com.anying.helpdesk.knowledge.evaluation.exception;

public class RagEvaluationConflictException extends RuntimeException {

    /**
     * 表示评测集状态、文档状态或并发版本不允许当前操作。
     */
    public RagEvaluationConflictException(String message) {
        super(message);
    }
}
