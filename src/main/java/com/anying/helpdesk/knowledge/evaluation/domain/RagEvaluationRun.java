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
@TableName("rag_evaluation_runs")
public class RagEvaluationRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private Integer candidateLimit;
    private BigDecimal vectorMinSimilarity;
    private Integer resultLimit;
    private Integer totalCases;
    private Integer hitCases;
    private BigDecimal hitRate;
    private BigDecimal recallAtK;
    private BigDecimal meanReciprocalRank;
    private Long averageLatencyMs;
    private Long createdBy;
    private LocalDateTime createdAt;
}
