package com.anying.helpdesk.knowledge.answer;

/** 由会话层校验得到的有效问题；续答使用服务端保存的原问题和知识库。 */
public record KnowledgeAnswerInput(String question, String scope, Long knowledgeBaseId,
                                   Long continuationOfMessageId, Long policyRevision) { }
