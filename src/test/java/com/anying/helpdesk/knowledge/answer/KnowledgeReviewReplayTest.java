package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.answer.evaluation.KnowledgeReviewEvaluationCli;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeReviewReplayTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 原样回放历史输入并确认错误反例仍通过结构/引用检查，从而只测试语义。 */
    @Test
    @DisplayName("28题回放集保持真实B03和B09原问题、范围、证据和草案")
    void validatesAndPreservesHistoricalInputs() throws Exception {
        var dataset = json.readValue(Path.of("docs/evaluation/knowledge-review-replay-cases.json").toFile(),
                KnowledgeReviewEvaluationCli.Dataset.class);
        KnowledgeReviewEvaluationCli.validate(dataset);
        assertThat(dataset.cases()).hasSize(28);
        var history = json.readTree(Path.of("docs/evaluation/runs/2026-10-19-澄清续答数据.json").toFile());
        for (var mapping : List.of(List.of("H01P", "B03"), List.of("H02P", "B09"))) {
            var item = dataset.cases().stream().filter(c -> c.id().equals(mapping.getFirst())).findFirst().orElseThrow();
            var source = java.util.stream.StreamSupport.stream(history.get("results").spliterator(), false)
                    .filter(node -> node.get("id").asText().equals(mapping.getLast())).findFirst().orElseThrow().get("diagnostic");
            assertThat(item.question()).isEqualTo(source.get("question").asText());
            assertThat(item.scope()).isEqualTo(source.get("applicabilityScope").asText());
            assertThat(item.draft()).isEqualTo(json.treeToValue(source.get("draft"), KnowledgeAnswerDraft.class));
            assertThat(item.evidence()).isEqualTo(json.readerForListOf(KnowledgeAnswerReport.Evidence.class).readValue(source.get("evidence")));
        }
    }

    /** 候选保持旧布尔协议，不把标签、人工解释或历史复核结果发送给模型。 */
    @Test
    @DisplayName("上下文候选仅改变复核规则且默认仍是BASELINE")
    void contextualCandidateRemainsExplicitAndStrict() {
        var chat = mock(ChatModel.class);
        var resolved = new ResolvedChatModel(1L, "test", true, chat);
        var client = new KnowledgeAnswerModelClient(json);
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE, List.of(), List.of(), List.of());
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"approved\":true}")))));
        client.review(resolved, "问题", "ALL", List.of(), draft, KnowledgeAnswerModelClient.ReviewStrategy.CONTEXTUAL);
        client.review(resolved, "问题", "ALL", List.of(), draft);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getFirst().getSystemMessage().getText()).contains("补充判定口径");
        assertThat(prompts.getAllValues().getLast().getSystemMessage().getText()).doesNotContain("补充判定口径");
        assertThat(prompts.getAllValues().getFirst().getUserMessage().getText()).doesNotContain("expectedApproved", "rationale", "H01");
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"approved\":true,\"reason\":\"UNSUPPORTED_CLAIM\"}")))));
        assertThatThrownBy(() -> client.review(resolved, "问题", "ALL", List.of(), draft,
                KnowledgeAnswerModelClient.ReviewStrategy.CONTEXTUAL)).isInstanceOf(KnowledgeAnswerFailure.class);
    }
}
