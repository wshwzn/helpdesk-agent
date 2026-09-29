package com.anying.helpdesk.knowledge.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("rag_evaluation_case_documents")
public class RagEvaluationCaseDocument {

    @TableId(type = IdType.INPUT)
    private Long caseId;
    private Long documentId;
    private LocalDateTime createdAt;
}
