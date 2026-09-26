package com.anying.helpdesk.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TicketControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsAndQueriesTicket() throws Exception {
        String id = createTicket(1);
        mockMvc.perform(get("/api/tickets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("File upload fails"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"));

        mockMvc.perform(get("/api/tickets").param("creatorId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].creatorId").value(1));
    }

    @Test
    void supportAgentClaimsAndResolvesTicket() throws Exception {
        String id = createTicket(10);

        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportId": 200}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeId").value(200))
                .andExpect(jsonPath("$.data.assignedAt").isNotEmpty());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "supportId": 200,
                                  "resolution": "Reset the customer's upload quota."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolution").value("Reset the customer's upload quota."))
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty());

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 10, "reason": "No longer needed"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_STATUS"));
    }

    @Test
    void customerCancelsOwnPendingTicket() throws Exception {
        String id = createTicket(11);

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 11, "reason": "Issue resolved itself"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("Issue resolved itself"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
    }

    @Test
    void customerCancelsOwnProcessingTicket() throws Exception {
        String id = createTicket(13);

        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportId": 200}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tickets/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 13, "reason": "No longer needs support"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.assigneeId").value(200))
                .andExpect(jsonPath("$.data.cancelReason").value("No longer needs support"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportId": 200, "resolution": "Late resolution attempt"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_STATUS"));
    }

    @Test
    void rejectsResolutionByUnassignedSupportAgent() throws Exception {
        String id = createTicket(12);
        mockMvc.perform(post("/api/support/tickets/{id}/claim", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportId": 200}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/support/tickets/{id}/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportId": 201, "resolution": "Attempted by another agent"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACCESS_DENIED"));
    }

    private String createTicket(long customerId) throws Exception {
        String response = mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "File upload fails",
                                  "description": "The upload repeatedly returns error E102.",
                                  "priority": "HIGH",
                                  "creatorId": %d
                                }
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.data.id").toString();
    }
}
