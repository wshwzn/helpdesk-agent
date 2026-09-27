package com.anying.helpdesk.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("agent_tool_calls")
public class AgentToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long assistantMessageId;
    private Long customerId;
    private String callId;
    private Integer sequenceNo;
    private String toolName;
    private String argumentsJson;
    private String resultJson;
    private AgentToolCallStatus status;
    private String errorCode;
    private Long durationMs;
    @Version
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
