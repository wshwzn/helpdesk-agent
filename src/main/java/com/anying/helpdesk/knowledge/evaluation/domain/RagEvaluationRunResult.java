package com.anying.helpdesk.knowledge.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("rag_evaluation_run_results")
public class RagEvaluationRunResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long caseId;
    private String question;
    private String expectedDocumentsJson;
    private String retrievedResultsJson;
    private Integer expectedDocumentCount;
    private Integer matchedDocumentCount;
    private Integer firstRelevantRank;
    private Boolean hit;
    private BigDecimal recallAtK;
    private BigDecimal reciprocalRank;
    private Long latencyMs;
    private LocalDateTime createdAt;
}
