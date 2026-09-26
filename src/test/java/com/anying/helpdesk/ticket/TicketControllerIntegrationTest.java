package com.anying.helpdesk.ticket;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TicketControllerIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsAndQueriesOwnTicket() throws Exception {
        TestSession customer = registerCustomer("customer_one");
        String id = createTicket(customer.token());

        mockMvc.perform(get("/api/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("File upload fails"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.creatorId").value(customer.userId()));

        mockMvc.perform(get("/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].creatorId").value(customer.userId()));

        TestSession anotherCustomer = registerCustomer("customer_two");
        mockMvc.perform(get("/api/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(anotherCustomer.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACCESS_DENIED"));
    }

    @Test
    void supportAgentClaimsAndResolvesTicket() throws Exception {
        TestSession customer = registerCustomer("customer_three");
        TestSession support = createSupport("support_one");
        String id = createTicket(customer.token());

        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(support.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeId").value(support.userId()))
                .andExpect(jsonPath("$.data.assignedAt").isNotEmpty());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(support.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolution": "Reset the customer's upload quota."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolution").value("Reset the customer's upload quota."))
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty());

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "No longer needed"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_STATUS"));
    }

    @Test
    void customerCancelsOwnPendingTicket() throws Exception {
        TestSession customer = registerCustomer("customer_four");
        String id = createTicket(customer.token());

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Issue resolved itself"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("Issue resolved itself"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
    }

    @Test
    void customerCancelsOwnProcessingTicket() throws Exception {
        TestSession customer = registerCustomer("customer_five");
        TestSession support = createSupport("support_two");
        String id = createTicket(customer.token());

        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(support.token())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "No longer needs support"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.assigneeId").value(support.userId()))
                .andExpect(jsonPath("$.data.cancelReason").value("No longer needs support"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(support.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolution": "Late resolution attempt"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_STATUS"));
    }

    @Test
    void rejectsResolutionByUnassignedSupportAgent() throws Exception {
        TestSession customer = registerCustomer("customer_six");
        TestSession assignedSupport = createSupport("support_three");
        TestSession anotherSupport = createSupport("support_four");
        String id = createTicket(customer.token());

        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(assignedSupport.token())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(anotherSupport.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolution": "Attempted by another agent"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACCESS_DENIED"));
    }

    private TestSession registerCustomer(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s",
                                  "displayName": "Test Customer"
                                }
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return readSession(response);
    }

    private TestSession createSupport(String username) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("Test Support");
        user.setRole(UserRole.SUPPORT);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return login(username);
    }

    private TestSession login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return readSession(response);
    }

    private String createTicket(String token) throws Exception {
        String response = mockMvc.perform(post("/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "File upload fails",
                                  "description": "The upload repeatedly returns error E102.",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.id").toString();
    }

    private TestSession readSession(String response) {
        Number userId = JsonPath.read(response, "$.data.user.id");
        String token = JsonPath.read(response, "$.data.accessToken");
        return new TestSession(token, userId.longValue());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record TestSession(String token, long userId) {
    }
}
