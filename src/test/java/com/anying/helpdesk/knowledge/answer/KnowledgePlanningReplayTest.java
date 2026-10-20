package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.answer.evaluation.KnowledgePlanningEvaluationCli;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgePlanningReplayTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 固定八种独立场景，输入身份重复或证据歧义应在计费调用之前拒绝。 */
    @Test @DisplayName("规划回放题集有效且拒绝重复题号")
    void validatesDataset() throws Exception {
        var dataset = dataset();
        KnowledgePlanningEvaluationCli.validate(dataset);
        assertThat(dataset.cases()).hasSize(8);
        assertThatThrownBy(() -> KnowledgePlanningEvaluationCli.validate(new KnowledgePlanningEvaluationCli.Dataset(
                1, dataset.labelSource(), dataset.description(), dataset.adoptionCriteria(),
                List.of(dataset.cases().getFirst(), dataset.cases().getFirst())))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 真实规划客户端只接收业务输入，场景答案标签和准入标准不进入提示。 */
    @Test @DisplayName("回放只调用规划且不向模型泄露预期决策")
    void labelsStayOutsidePrompt() throws Exception {
        var chat = mock(ChatModel.class);
        var model = new ResolvedChatModel(1L, "test", true, chat);
        var client = new KnowledgeAnswerModelClient(json);
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"decision\":\"SUFFICIENT\",\"gaps\":[],\"tasks\":[],\"userConditions\":[]}")))));
        var row = KnowledgePlanningEvaluationCli.execute(client, model, dataset().cases().getFirst(), 1);
        assertThat(row.scenarioChecksPassed()).isTrue();
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).doesNotContain("expectedDecisions", "adoptionCriteria", "P01", "direct_fact");
    }

    /** 失败结果没有伪造规划或通过标记，原错误分类与用量保留用于汇总。 */
    @Test @DisplayName("回放保留规划失败分类和已知用量")
    void retainsFailure() throws Exception {
        var client = mock(KnowledgeAnswerModelClient.class);
        when(client.plan(any(), any(), any(), any(), any())).thenThrow(new KnowledgeAnswerFailure(
                KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_RULE_ANCHOR_INVALID, "anchor", new KnowledgeAnswerModelClient.TokenUsage(2, 1, 3)));
        var row = KnowledgePlanningEvaluationCli.execute(client, new ResolvedChatModel(1L, "test", false, mock(ChatModel.class)),
                dataset().cases().getFirst(), 1);
        assertThat(row.plan()).isNull();
        assertThat(row.scenarioChecksPassed()).isFalse();
        assertThat(row.errorCode()).isEqualTo("KNOWLEDGE_PLAN_RULE_ANCHOR_INVALID");
        assertThat(row.usage().totalTokens()).isEqualTo(3);
    }

    /** 从独立题集读取固定输入，不依赖运行中的后端或真实模型。 */
    private KnowledgePlanningEvaluationCli.Dataset dataset() throws Exception {
        return json.readValue(Path.of("docs/evaluation/knowledge-planning-cases.json").toFile(), KnowledgePlanningEvaluationCli.Dataset.class);
    }
}
