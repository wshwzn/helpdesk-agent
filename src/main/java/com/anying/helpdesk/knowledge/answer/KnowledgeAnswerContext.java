package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 服务端保存的检索经历；规划缺口不是事实，不得作为结论或引用来源。 */
public record KnowledgeAnswerContext(KnowledgeRetrievalRun.StopReason stopReason,
        List<String> knowledgeGaps, List<String> userConditions, List<String> pendingRuleQueries,
        boolean searchedAfterAssessment) {

    /** 规划未确认用户条件时，不把找不到内部资料改成向用户追问；未查规则不得确定作答。 */
    public void validate(KnowledgeAnswerDraft draft) {
        if (draft.status() == KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION && userConditions.isEmpty())
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_UNJUSTIFIED_CLARIFICATION,
                    "clarification requires an assessed user condition");
        if (draft.status() == KnowledgeAnswerDraft.Status.ANSWERABLE && !pendingRuleQueries.isEmpty())
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_RULE_CHECK_INCOMPLETE,
                    "required rule search was not executed");
    }
}
