package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import java.util.List;

/** 多轮检索诊断独立于客户答案，旧版快照没有此字段。 */
public record KnowledgeRetrievalRun(boolean enabled, int maxRounds, int maxSearches,
        int maxEvidence, int maxCharacters, long schedulingBudgetMs,
        List<Search> searches, List<Planning> plans, StopReason stopReason) {
    public enum StopReason {
        SINGLE_PASS, NO_MATCH, SUFFICIENT, NEEDS_USER_INPUT, EXHAUSTED,
        NO_NEW_EVIDENCE, REPEATED_QUERY, ROUND_BUDGET, TIME_BUDGET,
        EVIDENCE_BUDGET, TECHNICAL_FAILURE, CANCELLED
    }
    public record Search(int round, String query, KnowledgeSearchResponse response,
                         boolean truncated, List<String> addedEvidenceIds) { }
    public record Planning(int afterRound, KnowledgeRetrievalPlan plan) { }
}
