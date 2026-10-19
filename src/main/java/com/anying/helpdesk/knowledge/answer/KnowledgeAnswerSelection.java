package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 模型仅选择本轮片段，不具有填写或修改引文的权限。 */
public record KnowledgeAnswerSelection(
        String citationSetId, KnowledgeAnswerDraft.Status status, List<Claim> claims,
        List<String> missingConditions, List<Conflict> conflicts
) {
    public record Claim(String text, List<Reference> references) { }
    public record Reference(String evidenceId, String paragraphId) { }
    public record Conflict(String issue, Claim first, Claim second) { }
}
