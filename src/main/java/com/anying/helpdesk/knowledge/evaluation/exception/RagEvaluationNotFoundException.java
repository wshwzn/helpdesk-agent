package com.anying.helpdesk.knowledge.evaluation.exception;

public class RagEvaluationNotFoundException extends RuntimeException {

    /**
     * 表示指定知识库中的评测用例或运行记录不存在。
     */
    public RagEvaluationNotFoundException(String resource, Long id) {
        super(resource + " not found: " + id);
    }
}
