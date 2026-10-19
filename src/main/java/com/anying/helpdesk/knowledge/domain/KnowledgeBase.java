package com.anying.helpdesk.knowledge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("knowledge_bases")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Integer retrievalCandidateLimit = 50;
    private BigDecimal vectorMinSimilarity = BigDecimal.ZERO;
    private Integer retrievalResultLimit = 5;
    private Boolean isDefault = false;
    private String clarificationPolicy;
    private Long clarificationRevision = 0L;
    private Long createdBy;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
