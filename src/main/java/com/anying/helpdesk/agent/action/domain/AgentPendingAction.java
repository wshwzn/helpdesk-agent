package com.anying.helpdesk.agent.action.domain;

import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("agent_pending_actions")
public class AgentPendingAction {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long assistantMessageId;
    private Long customerId;
    private PendingActionType actionType;
    private PendingActionStatus status;
    private String title;
    private String description;
    private TicketPriority priority;
    private String priorityReason;
    private Long ticketId;
    private LocalDateTime expiresAt;
    private LocalDateTime executedAt;
    private LocalDateTime cancelledAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
