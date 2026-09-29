package com.anying.helpdesk.knowledge.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("rag_evaluation_cases")
public class RagEvaluationCase {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String question;
    private Long createdBy;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
