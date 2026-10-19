package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.mapper.KnowledgeBaseMapper;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeApplicabilityServiceTest {
    private final KnowledgeApplicabilityService service = new KnowledgeApplicabilityService(mock(KnowledgeBaseMapper.class),
            JsonMapper.builder().build(), Validation.buildDefaultValidatorFactory().getValidator());
    private final KnowledgeApplicabilityPolicy.ScopeOption sh = new KnowledgeApplicabilityPolicy.ScopeOption("CN_SH", "上海");
    private final KnowledgeApplicabilityPolicy.ScopeOption bj = new KnowledgeApplicabilityPolicy.ScopeOption("CN_BJ", "北京");

    /** 配置可迁移到其他业务主题，不依赖住宿关键词或测试题号。 */
    @Test
    @DisplayName("未知、重复、空分类和不确定结果不能绕过条件校验")
    void rejectsMalformedOrUncertainRoutes() {
        var policy = new KnowledgeApplicabilityPolicy(1L, List.of(topic("allowance", List.of(sh))));
        for (var route : List.of(new KnowledgeApplicabilityPolicy.Route(List.of("invented"), false),
                new KnowledgeApplicabilityPolicy.Route(List.of("allowance", "allowance"), false),
                new KnowledgeApplicabilityPolicy.Route(null, false),
                new KnowledgeApplicabilityPolicy.Route(List.of(), null),
                new KnowledgeApplicabilityPolicy.Route(List.of(), true))) {
            assertThatThrownBy(() -> service.requiredOptions(policy, route)).isInstanceOf(KnowledgeAnswerFailure.class);
        }
        assertThat(service.requiredOptions(policy, new KnowledgeApplicabilityPolicy.Route(List.of(), false))).isEmpty();
    }

    /** 多问题共享一个显式范围时只能选择所有需范围主题的交集。 */
    @Test
    @DisplayName("多主题取范围交集，互不相容时拒绝猜测")
    void intersectsRequiredScopes() {
        var policy = new KnowledgeApplicabilityPolicy(1L, List.of(topic("a", List.of(sh, bj)),
                topic("b", List.of(sh)), topic("generic", List.of())));
        assertThat(service.requiredOptions(policy, new KnowledgeApplicabilityPolicy.Route(List.of("a", "b", "generic"), false)))
                .containsExactly(sh);
        var incompatible = new KnowledgeApplicabilityPolicy(1L, List.of(topic("a", List.of(sh)), topic("b", List.of(bj))));
        assertThatThrownBy(() -> service.requiredOptions(incompatible,
                new KnowledgeApplicabilityPolicy.Route(List.of("a", "b"), false)))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        error -> assertThat(error.errorCode()).isEqualTo("KNOWLEDGE_APPLICABILITY_UNCERTAIN"));
    }

    /** 检查真实协议调用的数据边界，地区选项和制度事实不会进入分类模型。 */
    @Test
    @DisplayName("路由只看到原问题与主题描述且不接受额外回答字段")
    void routingPayloadAndProtocol() {
        var chat = mock(ChatModel.class);
        var model = new ResolvedChatModel(1L, "test", true, chat);
        var client = new KnowledgeAnswerModelClient(JsonMapper.builder().build());
        var policy = new KnowledgeApplicabilityPolicy(1L, List.of(topic("allowance", List.of(sh))));
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"topicIds\":[\"allowance\"],\"uncertain\":false}")))));
        assertThat(client.route(model, "补贴如何计算？", policy).value().topicIds()).containsExactly("allowance");
        var capture = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(capture.capture());
        assertThat(capture.getValue().getInstructions().getLast().getText())
                .contains("补贴如何计算", "allowance").doesNotContain("CN_SH", "scopeOptions", "revision");
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"topicIds\":[],\"uncertain\":false,\"answer\":\"伪造答案\"}")))));
        assertThatThrownBy(() -> client.route(model, "问题", policy)).isInstanceOf(KnowledgeAnswerFailure.class);
    }

    /** 构造与测试题库无关的通用主题。 */
    private KnowledgeApplicabilityPolicy.Topic topic(String id, List<KnowledgeApplicabilityPolicy.ScopeOption> options) {
        return new KnowledgeApplicabilityPolicy.Topic(id, "主题 " + id + " 的业务问题", options);
    }
}
