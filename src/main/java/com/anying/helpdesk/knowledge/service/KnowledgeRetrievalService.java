package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.exception.KnowledgeRetrievalException;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchHit;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchResults;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int VECTOR_CANDIDATE_MULTIPLIER = 2;
    private static final int RRF_RANK_CONSTANT = 60;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DefaultEmbeddingModelProvider embeddingModelProvider;
    private final KnowledgeChunkStore chunkStore;

    /**
     * 为查询生成向量，执行 BM25 与 KNN 双路召回，并使用标准 RRF 融合结果。
     */
    public KnowledgeSearchResponse search(Long knowledgeBaseId, KnowledgeSearchRequest request) {
        KnowledgeRetrievalSettingsResponse settings = knowledgeBaseService.getRetrievalSettings(knowledgeBaseId);
        String query = request.query().trim();
        ResolvedEmbeddingModel embeddingModel = embeddingModelProvider.get();
        chunkStore.ensureIndex(embeddingModel.dimensions());
        float[] queryVector = embedQuery(query, embeddingModel);
        KnowledgeChunkSearchResults candidates = chunkStore.search(
                knowledgeBaseId,
                embeddingModel.configId(),
                embeddingModel.modelName(),
                query,
                queryVector,
                settings.candidateLimit(),
                settings.candidateLimit() * VECTOR_CANDIDATE_MULTIPLIER,
                settings.vectorMinSimilarity() == null
                        ? null
                        : settings.vectorMinSimilarity().doubleValue()
        );
        List<KnowledgeSearchResultResponse> results = fuse(candidates, settings.resultLimit());
        return new KnowledgeSearchResponse(
                knowledgeBaseId,
                query,
                settings.candidateLimit(),
                settings.vectorMinSimilarity(),
                settings.resultLimit(),
                candidates.bm25Hits().size(),
                candidates.vectorHits().size(),
                results
        );
    }

    /**
     * 调用默认向量模型生成查询向量，并拒绝与索引维度不一致的响应。
     */
    private float[] embedQuery(String query, ResolvedEmbeddingModel embeddingModel) {
        try {
            float[] vector = embeddingModel.embeddingModel().embed(query);
            if (vector == null || vector.length != embeddingModel.dimensions()) {
                throw new KnowledgeRetrievalException("query embedding dimensions do not match the model configuration");
            }
            return vector;
        } catch (ModelConfigConflictException | KnowledgeRetrievalException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalException("failed to generate query embedding", exception);
        }
    }

    /**
     * 按各路名次累加倒数排名分数，对相同 Chunk 去重后返回配置数量。
     */
    private List<KnowledgeSearchResultResponse> fuse(KnowledgeChunkSearchResults candidates, int resultLimit) {
        Map<String, SearchAccumulator> accumulators = new LinkedHashMap<>();
        addBm25Ranks(accumulators, candidates.bm25Hits());
        addVectorRanks(accumulators, candidates.vectorHits());
        List<SearchAccumulator> ranked = accumulators.values().stream()
                .sorted(Comparator.comparingDouble((SearchAccumulator value) -> value.rrfScore)
                        .reversed()
                        .thenComparing(value -> value.hit.chunkId()))
                .limit(resultLimit)
                .toList();
        List<KnowledgeSearchResultResponse> results = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            SearchAccumulator value = ranked.get(index);
            results.add(new KnowledgeSearchResultResponse(
                    index + 1,
                    value.hit.documentId(),
                    value.hit.chunkSequence(),
                    value.hit.originalName(),
                    value.hit.content(),
                    value.rrfScore,
                    value.bm25Rank,
                    value.bm25Score,
                    value.vectorRank,
                    value.vectorScore
            ));
        }
        return List.copyOf(results);
    }

    /**
     * 将 BM25 候选的名次和原始分数加入融合容器。
     */
    private void addBm25Ranks(Map<String, SearchAccumulator> accumulators, List<KnowledgeChunkSearchHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            int rank = index + 1;
            KnowledgeChunkSearchHit hit = hits.get(index);
            SearchAccumulator value = accumulators.computeIfAbsent(hit.chunkId(), ignored -> new SearchAccumulator(hit));
            value.bm25Rank = rank;
            value.bm25Score = hit.score();
            value.rrfScore += reciprocalRank(rank);
        }
    }

    /**
     * 将向量候选的名次和原始分数加入融合容器。
     */
    private void addVectorRanks(Map<String, SearchAccumulator> accumulators, List<KnowledgeChunkSearchHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            int rank = index + 1;
            KnowledgeChunkSearchHit hit = hits.get(index);
            SearchAccumulator value = accumulators.computeIfAbsent(hit.chunkId(), ignored -> new SearchAccumulator(hit));
            value.vectorRank = rank;
            value.vectorScore = hit.score();
            value.rrfScore += reciprocalRank(rank);
        }
    }

    /**
     * 使用固定常数计算一条候选在某一路中的 RRF 分值。
     */
    private double reciprocalRank(int rank) {
        return 1.0D / (RRF_RANK_CONSTANT + rank);
    }

    private static final class SearchAccumulator {

        private final KnowledgeChunkSearchHit hit;
        private double rrfScore;
        private Integer bm25Rank;
        private Double bm25Score;
        private Integer vectorRank;
        private Double vectorScore;

        /**
         * 以首次出现的 Chunk 保存公共展示字段，后续只合并两路排名和分数。
         */
        private SearchAccumulator(KnowledgeChunkSearchHit hit) {
            this.hit = hit;
        }
    }
}
