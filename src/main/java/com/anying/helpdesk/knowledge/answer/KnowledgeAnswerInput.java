package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 由会话层校验得到的有效问题；续答使用服务端保存的原问题和知识库。 */
public record KnowledgeAnswerInput(String question, String scope, Long knowledgeBaseId,
        Long continuationOfMessageId, Long policyRevision, List<String> scopes) {
    /** 将新旧输入统一为受限范围列表，普通旧模式调用允许未使用的空范围。 */
    public KnowledgeAnswerInput {
        scopes = KnowledgeScopes.resolve(scope, scopes);
        scope = KnowledgeScopes.single(scopes);
    }
    /** 兼容原单范围调用和历史续答。 */
    public KnowledgeAnswerInput(String question, String scope, Long baseId, Long continuationId, Long revision) {
        this(question, scope, baseId, continuationId, revision, null);
    }
}
