package com.anying.helpdesk.agent.action;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.agent.action.mapper.AgentPendingActionMapper;
import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.ticket.domain.Ticket;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PendingActionControllerIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentPendingActionMapper pendingActionMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PendingActionService pendingActionService;

    /**
     * 清理每个测试创建的草稿、消息、对话、工单和客户。
     */
    @AfterEach
    void cleanUp() {
        pendingActionMapper.delete(null);
        chatMessageMapper.delete(null);
        conversationMapper.delete(null);
        ticketMapper.delete(null);
        userMapper.delete(new LambdaQueryWrapper<User>()
                .likeRight(User::getUsername, "action_"));
    }

    /**
     * 验证客户修改会被最终工单采用，且确认接口可以安全重试。
     */
    @Test
    @DisplayName("客户修改并确认草稿后只创建一张最终工单")
    void customerUpdatesAndConfirmsDraftIdempotently() throws Exception {
        CustomerFixture customer = createCustomer("action_owner");
        PendingActionResponse action = prepareAction(
                customer,
                "无法登录",
                "客户登录失败",
                TicketPriority.HIGH
        );

        mockMvc.perform(patch("/api/agent/actions/{id}", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "重置密码后仍无法登录",
                                  "description": "今天重置两次密码后仍提示密码错误",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("重置密码后仍无法登录"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.version").value(1));

        String confirmation = mockMvc.perform(post("/api/agent/actions/{id}/confirm", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.ticket.status").value("PENDING"))
                .andExpect(jsonPath("$.data.ticket.title").value("重置密码后仍无法登录"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long ticketId = ((Number) JsonPath.read(confirmation, "$.data.ticket.id")).longValue();

        mockMvc.perform(post("/api/agent/actions/{id}/confirm", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket.id").value(ticketId));

        assertThat(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getCreatorId, customer.userId()))).isEqualTo(1);
    }

    /**
     * 验证客户数据隔离、默认优先级和取消后的状态约束。
     */
    @Test
    @DisplayName("客户只能操作自己的草稿且取消后不能确认")
    void customerCannotOperateAnotherCustomersOrCancelledAction() throws Exception {
        CustomerFixture owner = createCustomer("action_owner_two");
        CustomerFixture other = createCustomer("action_other");
        PendingActionResponse action = prepareAction(
                owner,
                "功能异常",
                "页面无法保存",
                null
        );
        assertThat(action.priority()).isEqualTo(TicketPriority.MEDIUM);

        mockMvc.perform(get("/api/agent/actions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .param("conversationId", action.conversationId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PENDING_ACTION_NOT_FOUND"));

        mockMvc.perform(post("/api/agent/actions/{id}/cancel", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(post("/api/agent/actions/{id}/confirm", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PENDING_ACTION_STATUS"));
        assertThat(ticketMapper.selectCount(null)).isZero();
    }

    /**
     * 验证超时草稿不能创建工单，且过期状态不会因异常响应回滚。
     */
    @Test
    @DisplayName("过期草稿拒绝确认并持久化过期状态")
    void expiredDraftCannotBeConfirmed() throws Exception {
        CustomerFixture customer = createCustomer("action_expired");
        PendingActionResponse action = prepareAction(
                customer,
                "一般咨询",
                "需要人工协助",
                null
        );
        AgentPendingAction stored = pendingActionMapper.selectById(action.id());
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        pendingActionMapper.updateById(stored);

        mockMvc.perform(post("/api/agent/actions/{id}/confirm", action.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENDING_ACTION_EXPIRED"));

        assertThat(pendingActionMapper.selectById(action.id()).getStatus())
                .isEqualTo(PendingActionStatus.EXPIRED);
        assertThat(ticketMapper.selectCount(null)).isZero();
    }

    /**
     * 创建客户、对话和 AI 消息，并通过真实服务生成待确认操作。
     */
    private PendingActionResponse prepareAction(
            CustomerFixture customer,
            String title,
            String description,
            TicketPriority priority
    ) throws Exception {
        long conversationId = createConversation(customer.token(), title);
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("请确认工单草稿");
        assistantMessage.setStatus(ChatMessageStatus.COMPLETED);
        assistantMessage.setVersion(0);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        assistantMessage.setUpdatedAt(LocalDateTime.now());
        chatMessageMapper.insert(assistantMessage);
        return pendingActionService.prepareCreateTicket(
                new AgentExecutionContext(customer.userId(), conversationId, assistantMessage.getId()),
                title,
                description,
                priority,
                "需要人工处理"
        );
    }

    /**
     * 注册一个测试客户并返回其主键和访问令牌。
     */
    private CustomerFixture createCustomer(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s",
                                  "displayName": "操作测试客户"
                                }
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        return new CustomerFixture(user.getId(), JsonPath.read(response, "$.data.accessToken"));
    }

    /**
     * 通过客户接口创建待确认操作所属的对话。
     */
    private long createConversation(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /**
     * 生成携带 JWT 的 Authorization 请求头值。
     */
    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record CustomerFixture(Long userId, String token) {
    }
}
