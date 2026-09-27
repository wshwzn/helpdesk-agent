package com.anying.helpdesk.agent;

import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.domain.AgentToolCallStatus;
import com.anying.helpdesk.agent.mapper.AgentToolCallMapper;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AgentToolCallServiceIntegrationTest {

    @Autowired
    private AgentToolCallService toolCallService;

    @Autowired
    private AgentToolCallMapper toolCallMapper;

    /**
     * 清理工具调用测试数据，避免共享测试数据库影响其他用例。
     */
    @AfterEach
    void cleanUp() {
        toolCallMapper.delete(null);
    }

    /**
     * 验证同一 AI 回复下的多次调用会顺序落库，且历史查询只返回成功记录。
     */
    @Test
    @DisplayName("多次工具调用应按顺序落库并区分成功失败")
    void persistsMultipleCallsAndReturnsOnlySuccessfulHistory() {
        AgentExecutionContext context = new AgentExecutionContext(11L, 22L, 33L);

        AgentToolCall first = toolCallService.start(context, "list_my_tickets", "{}");
        toolCallService.completeSuccess(first.getId(), "{\"total\":2}");
        AgentToolCall second = toolCallService.start(
                context,
                "get_my_ticket",
                "{\"ticketNo\":\"HD20260927012345AB12CD34\"}"
        );
        toolCallService.completeFailure(second.getId());

        List<AgentToolCall> allCalls = toolCallMapper.selectList(null);
        assertThat(allCalls).hasSize(2);
        assertThat(allCalls).extracting(AgentToolCall::getSequenceNo).containsExactly(1, 2);
        assertThat(allCalls.get(0).getStatus()).isEqualTo(AgentToolCallStatus.SUCCESS);
        assertThat(allCalls.get(0).getResultJson()).isEqualTo("{\"total\":2}");
        assertThat(allCalls.get(1).getStatus()).isEqualTo(AgentToolCallStatus.FAILED);
        assertThat(allCalls.get(1).getErrorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(allCalls).allSatisfy(call -> {
            assertThat(call.getCallId()).isNotBlank();
            assertThat(call.getDurationMs()).isNotNegative();
            assertThat(call.getCompletedAt()).isNotNull();
        });

        List<AgentToolCall> replayable = toolCallService.listSuccessfulByAssistantMessageId(33L);
        assertThat(replayable).singleElement().satisfies(call -> {
            assertThat(call.getToolName()).isEqualTo("list_my_tickets");
            assertThat(call.getSequenceNo()).isEqualTo(1);
        });
    }
}
