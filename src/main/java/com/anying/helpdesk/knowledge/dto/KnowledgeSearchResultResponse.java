package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RRF 融合后的知识库 Chunk")
public record KnowledgeSearchResultResponse(
        @Schema(description = "融合排序名次", example = "1") int rank,
        @Schema(description = "来源文档主键", example = "3") Long documentId,
        @Schema(description = "Chunk 在文档中的序号", example = "2") int chunkSequence,
        @Schema(description = "原始文件名", example = "README.md") String originalName,
        @Schema(description = "Chunk 文本内容") String content,
        @Schema(description = "RRF 融合分数", example = "0.0325") double rrfScore,
        @Schema(description = "BM25 名次；未被该路召回时为空", example = "2") Integer bm25Rank,
        @Schema(description = "BM25 原始分数；未被该路召回时为空", example = "4.21") Double bm25Score,
        @Schema(description = "向量检索名次；未被该路召回时为空", example = "1") Integer vectorRank,
        @Schema(description = "向量检索原始分数；未被该路召回时为空", example = "0.87") Double vectorScore,
        @Schema(description = "本次检索时核对的业务元数据；旧快照可能为空") KnowledgeDocumentMetadata metadata
) {
    /**
     * 兼容不含业务元数据的历史检索快照构造。
     */
    public KnowledgeSearchResultResponse(int rank, Long documentId, int chunkSequence, String originalName,
                                         String content, double rrfScore, Integer bm25Rank, Double bm25Score,
                                         Integer vectorRank, Double vectorScore) {
        this(rank, documentId, chunkSequence, originalName, content, rrfScore,
                bm25Rank, bm25Score, vectorRank, vectorScore, null);
    }
}
