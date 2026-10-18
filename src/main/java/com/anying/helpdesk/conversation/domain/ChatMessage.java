package com.anying.helpdesk.conversation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.TableField;
import com.anying.helpdesk.conversation.mapper.KnowledgeAnswerReportTypeHandler;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "chat_messages", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private ChatMessageRole role;
    private String content;
    private ChatMessageStatus status;
    private Long modelConfigId;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String errorCode;
    private ChatMode mode;
    private String applicabilityScope;
    @TableField(typeHandler = KnowledgeAnswerReportTypeHandler.class)
    private KnowledgeAnswerReport knowledgeAnswer;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
