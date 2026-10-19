package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.answer.evaluation.KnowledgeReviewEvaluationCli;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;

import static com.anying.helpdesk.knowledge.answer.KnowledgeReviewChecklist.Verdict.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeReviewChecklistTest {
    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final KnowledgeAnswerDraft draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
            List.of(new KnowledgeAnswerDraft.Claim("600元", List.of(new KnowledgeAnswerDraft.Reference("E1", "每晚上限600元")))),
            List.of(), List.of());

    /** 后端仅在所有结论和全局检查均为 PASS 时批准。 */
    @Test
    @DisplayName("检查表全部通过才批准，任意否决或不确定均不放行")
    void requiresEveryCheckToPass() {
        assertThat(checklist(PASS).decide(draft).approved()).isTrue();
        assertThat(checklist(FAIL).decide(draft).reason()).isEqualTo(KnowledgeAnswerModelClient.ReviewReason.UNSUPPORTED_CLAIM);
        assertThat(checklist(UNCERTAIN).decide(draft).reason()).isEqualTo(KnowledgeAnswerModelClient.ReviewReason.UNCERTAIN);
        for (String field : List.of("statusAppropriate", "complete", "conflictsHandled", "clarificationAppropriate")) {
            var node = json.valueToTree(checklist(PASS));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field, "FAIL");
            assertThat(json.convertValue(node, KnowledgeReviewChecklist.class).decide(draft).approved()).isFalse();
        }
    }

    /** 对结论数量的校验不能替代身份校验，重复和未知路径都会留下漏检。 */
    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DUPLICATE", "UNKNOWN", "NULL_FIELD"})
    @DisplayName("漏项、重复、未知路径及缺少判断字段都归为协议错误")
    void rejectsIncompleteCoverage(String mutation) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(checklist(PASS));
        var claims = (com.fasterxml.jackson.databind.node.ArrayNode) node.get("claims");
        switch (mutation) {
            case "MISSING" -> claims.removeAll();
            case "DUPLICATE" -> claims.add(claims.get(0).deepCopy());
            case "UNKNOWN" -> ((com.fasterxml.jackson.databind.node.ObjectNode) claims.get(0)).put("path", "claims/999");
            case "NULL_FIELD" -> node.remove("complete");
        }
        assertThatThrownBy(() -> json.convertValue(node, KnowledgeReviewChecklist.class).decide(draft))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        error -> assertThat(error.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID"));
    }

    /** 冲突双方也需要逐项覆盖；同样数量的重复路径不能替代另一方。 */
    @Test
    @DisplayName("冲突双方必须分别复核且空结论仍检查全局状态")
    void coversConflictsAndEmptyDraft() {
        var conflict = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.CONFLICT, List.of(), List.of(),
                List.of(new KnowledgeAnswerDraft.Conflict("额度", draft.claims().getFirst(), draft.claims().getFirst())));
        assertThat(KnowledgeReviewChecklist.claimPaths(conflict)).containsExactly("conflicts/0/first", "conflicts/0/second");
        var check = new KnowledgeReviewChecklist(List.of(
                new KnowledgeReviewChecklist.ClaimCheck("conflicts/0/first", PASS, PASS),
                new KnowledgeReviewChecklist.ClaimCheck("conflicts/0/first", PASS, PASS)), PASS, PASS, PASS, PASS);
        assertThatThrownBy(() -> check.decide(conflict)).hasMessage("incomplete review checklist");
        var empty = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE, List.of(), List.of(), List.of());
        assertThat(new KnowledgeReviewChecklist(List.of(), FAIL, PASS, PASS, PASS).decide(empty).approved()).isFalse();
    }

    /** 协议错误的调用仍有成本，但不能被评测算为负例正确拦截。 */
    @Test
    @DisplayName("检查表协议错误保留已知用量且运行器单列技术失败")
    void preservesUsageAndSeparatesTechnicalError() throws Exception {
        var chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"claims\":[],\"statusAppropriate\":\"PASS\",\"complete\":\"PASS\",\"conflictsHandled\":\"PASS\",\"clarificationAppropriate\":\"PASS\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 10, 30)).build()));
        var client = new KnowledgeAnswerModelClient(json);
        var item = dataset().cases().getFirst();
        var row = KnowledgeReviewEvaluationCli.execute(client, new ResolvedChatModel(1L, "test", false, chat), item, 1,
                KnowledgeAnswerModelClient.ReviewStrategy.CHECKLIST);
        assertThat(row.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID");
        assertThat(row.review()).isNull();
        assertThat(row.usage().totalTokens()).isEqualTo(30);
    }

    /** 固定集只有语义错误，引用与结构必须先合法，且标签和理由不会送给模型。 */
    @Test
    @DisplayName("固定正反草案通过程序校验且请求不泄露预期标签")
    void validatesDatasetAndHidesLabels() throws Exception {
        var dataset = dataset();
        KnowledgeReviewEvaluationCli.validate(dataset);
        assertThat(dataset.cases()).hasSize(24);
        assertThat(dataset.cases().stream().filter(KnowledgeReviewEvaluationCli.Case::expectedApproved)).hasSize(12);
        var item = dataset.cases().getFirst();
        var chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            var input = json.readTree(prompt.getInstructions().getLast().getText());
            assertThat(input.has("expectedApproved")).isFalse();
            assertThat(input.has("rationale")).isFalse();
            assertThat(input.has("id")).isFalse();
            assertThat(input.get("question").asText()).isEqualTo(item.question());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"approved\":true}"))));
        });
        new KnowledgeAnswerModelClient(json).review(new ResolvedChatModel(1L, "test", false, chat), item.question(), item.scope(),
                item.evidence(), item.draft(), KnowledgeAnswerModelClient.ReviewStrategy.BASELINE);
        verify(chat, times(1)).call(any(Prompt.class));
    }

    /** 默认策略仍使用旧布尔协议，检查表只通过显式配置或评测参数启用。 */
    @Test
    @DisplayName("默认复核仍为基线且能读取旧版复核结构")
    void defaultStaysBaseline() throws Exception {
        var chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"approved\":true}")))));
        var client = new KnowledgeAnswerModelClient(json);
        var result = client.review(new ResolvedChatModel(1L, "test", false, chat), "问题", "ALL", List.of(), draft);
        assertThat(result.value().approved()).isTrue();
        assertThat(result.value().checklist()).isNull();
        assertThat(json.readValue("{\"approved\":false,\"reason\":null}", KnowledgeAnswerModelClient.Review.class).checklist()).isNull();
    }

    /** 构造单结论完整检查表，单独改变支持判断以测试汇总边界。 */
    private KnowledgeReviewChecklist checklist(KnowledgeReviewChecklist.Verdict supported) {
        return new KnowledgeReviewChecklist(List.of(new KnowledgeReviewChecklist.ClaimCheck("claims/0", supported, PASS)), PASS, PASS, PASS, PASS);
    }

    /** 读取仓库冻结题集，避免测试与真实运行使用不同样本。 */
    private KnowledgeReviewEvaluationCli.Dataset dataset() throws Exception {
        return json.readValue(Path.of("docs/evaluation/knowledge-review-cases.json").toFile(), KnowledgeReviewEvaluationCli.Dataset.class);
    }
}
