package com.anying.helpdesk.knowledge.answer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.KNOWLEDGE_PROTOCOL_INVALID;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerModelClient.ReviewReason.*;

/** 实验复核协议：模型逐项判断，后端检查覆盖完整性并汇总，不接收模型自报的总批准。 */
public record KnowledgeReviewChecklist(List<ClaimCheck> claims, Verdict statusAppropriate,
                                       Verdict complete, Verdict conflictsHandled, Verdict clarificationAppropriate) {
    public enum Verdict { PASS, FAIL, UNCERTAIN }
    public record ClaimCheck(String path, Verdict supported, Verdict conditionsPreserved) { }

    /** 为普通结论及冲突双方分配稳定路径，不把结论文本本身当作标识。 */
    public static List<String> claimPaths(KnowledgeAnswerDraft draft) {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < draft.claims().size(); i++) paths.add("claims/" + i);
        for (int i = 0; i < draft.conflicts().size(); i++) {
            paths.add("conflicts/" + i + "/first");
            paths.add("conflicts/" + i + "/second");
        }
        return List.copyOf(paths);
    }

    /** 必须逐项覆盖且全部通过才批准；缺项或重复项是协议失败，不能冒充业务否决。 */
    public KnowledgeAnswerModelClient.Review decide(KnowledgeAnswerDraft draft) {
        var expected = new HashSet<>(claimPaths(draft));
        if (claims == null || claims.size() != expected.size() || statusAppropriate == null || complete == null
                || conflictsHandled == null || clarificationAppropriate == null) throw invalid();
        var seen = new HashSet<String>();
        List<Verdict> verdicts = new ArrayList<>(List.of(statusAppropriate, complete, conflictsHandled, clarificationAppropriate));
        for (var claim : claims) {
            if (claim == null || !expected.contains(claim.path()) || !seen.add(claim.path())
                    || claim.supported() == null || claim.conditionsPreserved() == null) throw invalid();
            verdicts.add(claim.supported());
            verdicts.add(claim.conditionsPreserved());
        }
        if (verdicts.contains(Verdict.UNCERTAIN)) return new KnowledgeAnswerModelClient.Review(false, UNCERTAIN, this);
        var reason = claims.stream().anyMatch(item -> item.supported() == Verdict.FAIL || item.conditionsPreserved() == Verdict.FAIL)
                ? UNSUPPORTED_CLAIM : statusAppropriate == Verdict.FAIL ? WRONG_STATUS
                : complete == Verdict.FAIL ? INCOMPLETE_ANSWER : conflictsHandled == Verdict.FAIL ? MISSED_CONFLICT
                : clarificationAppropriate == Verdict.FAIL ? UNNECESSARY_CLARIFICATION : NONE;
        return new KnowledgeAnswerModelClient.Review(reason == NONE, reason, this);
    }

    /** 使用固定错误码描述检查表协议缺陷，不持久化厂商自由文本。 */
    private KnowledgeAnswerFailure invalid() {
        return new KnowledgeAnswerFailure(KNOWLEDGE_PROTOCOL_INVALID, "incomplete review checklist");
    }
}
