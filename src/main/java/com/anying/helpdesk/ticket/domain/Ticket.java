package com.anying.helpdesk.ticket.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("tickets")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private String title;
    private String description;
    private TicketStatus status;
    private TicketPriority priority;
    private Long creatorId;
    private Long assigneeId;
    private LocalDateTime assignedAt;
    private String resolution;
    private LocalDateTime resolvedAt;
    private String cancelReason;
    private LocalDateTime cancelledAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
