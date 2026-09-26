package com.anying.helpdesk.conversation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("conversations")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private String title;
    private ConversationStatus status;
    private LocalDateTime lastMessageAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
