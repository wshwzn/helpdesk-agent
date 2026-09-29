package com.anying.helpdesk.knowledge.evaluation.dto;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "单条评测问题的检索结果与指标")
public record RagEvaluationCaseResultResponse(
        @Schema(description = "评测用例主键") Long caseId,
        @Schema(description = "运行时的问题快照") String question,
        @Schema(description = "运行时的期望文档快照") List<RagExpectedDocumentResponse> expectedDocuments,
        @Schema(description = "是否至少命中一个期望文档") boolean hit,
        @Schema(description = "期望文档数量") int expectedDocumentCount,
        @Schema(description = "实际命中的期望文档数量") int matchedDocumentCount,
        @Schema(description = "第一个期望文档在 Chunk 列表中的名次；未命中时为空") Integer firstRelevantRank,
        @Schema(description = "该问题的 Recall@K") BigDecimal recallAtK,
        @Schema(description = "该问题的倒数排名") BigDecimal reciprocalRank,
        @Schema(description = "检索耗时，单位毫秒") long latencyMs,
        @Schema(description = "实际召回的 Chunk 列表") List<KnowledgeSearchResultResponse> retrievedResults
) {

    public RagEvaluationCaseResultResponse {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
        retrievedResults = retrievedResults == null ? List.of() : List.copyOf(retrievedResults);
    }
}
