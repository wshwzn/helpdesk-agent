package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.exception.KnowledgeRetrievalException;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchHit;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchResults;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.knowledge.index.KnowledgeIndexSelection;
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
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int VECTOR_CANDIDATE_MULTIPLIER = 2;
    private static final int RRF_RANK_CONSTANT = 60;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DefaultEmbeddingModelProvider embeddingModelProvider;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentEligibilityService eligibilityService;
    private final Clock knowledgeClock;

    /**
     * 为查询生成向量，执行 BM25 与 KNN 双路召回，并使用标准 RRF 融合结果。
     */
    public KnowledgeSearchResponse search(Long knowledgeBaseId, KnowledgeSearchRequest request) {
        KnowledgeRetrievalSettingsResponse settings = knowledgeBaseService.getRetrievalSettings(knowledgeBaseId);
        return search(knowledgeBaseId, request, settings);
    }

    /** 返回完整双路候选并集供管理员评测，保留生产检索的准入条件与 RRF 排序。 */
    public KnowledgeSearchResponse searchCandidates(Long knowledgeBaseId, KnowledgeSearchRequest request) {
        var settings = knowledgeBaseService.getRetrievalSettings(knowledgeBaseId);
        return search(knowledgeBaseId, request, new KnowledgeRetrievalSettingsResponse(knowledgeBaseId,
                settings.candidateLimit(), settings.vectorMinSimilarity(), settings.candidateLimit() * 2,
                settings.version()));
    }

    /**
     * 使用调用方提供的固定参数快照执行检索，保证批量评测期间口径一致。
     */
    public KnowledgeSearchResponse search(
            Long knowledgeBaseId,
            KnowledgeSearchRequest request,
            KnowledgeRetrievalSettingsResponse settings
    ) {
        String query = request.query().trim();
        Map<Long, KnowledgeDocument> eligible = new LinkedHashMap<>(eligible(knowledgeBaseId, request));
        if (eligible.isEmpty()) {
            return new KnowledgeSearchResponse(knowledgeBaseId, query, settings.candidateLimit(),
                    settings.vectorMinSimilarity(), settings.resultLimit(), 0, 0, List.of());
        }
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
                settings.vectorMinSimilarity().doubleValue(),
                eligible.values().stream().map(document -> new KnowledgeIndexSelection(
                        document.getId(), document.getActiveIndexBuildId())).toList()
        );
        // 网络调用期间可能发生撤回或到期；只允许初次准入且复核仍有效的文档进入最终证据。
        Map<Long, KnowledgeDocument> current = eligible(knowledgeBaseId, request);
        eligible.keySet().retainAll(current.keySet());
        KnowledgeChunkSearchResults verified = new KnowledgeChunkSearchResults(
                candidates.bm25Hits().stream().filter(hit -> eligible.containsKey(hit.documentId())).toList(),
                candidates.vectorHits().stream().filter(hit -> eligible.containsKey(hit.documentId())).toList());
        List<KnowledgeSearchResultResponse> results = fuse(verified, settings.resultLimit(), eligible);
        return new KnowledgeSearchResponse(
                knowledgeBaseId,
                query,
                settings.candidateLimit(),
                settings.vectorMinSimilarity(),
                settings.resultLimit(),
                verified.bm25Hits().size(),
                verified.vectorHits().size(),
                results
        );
    }

    /** 多范围只扩大一次候选准入集合，不重复生成查询向量或执行各地区完整检索。 */
    private Map<Long, KnowledgeDocument> eligible(Long baseId, KnowledgeSearchRequest request) {
        return request.applicabilityScopes().size() == 1
                ? eligibilityService.findEligible(baseId, request.applicabilityScope(), LocalDateTime.now(knowledgeClock))
                : eligibilityService.findEligibleScopes(baseId, request.applicabilityScopes(), LocalDateTime.now(knowledgeClock));
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
    private List<KnowledgeSearchResultResponse> fuse(KnowledgeChunkSearchResults candidates, int resultLimit,
                                                   Map<Long, KnowledgeDocument> documents) {
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
                    value.vectorScore,
                    KnowledgeDocumentMetadata.from(documents.get(value.hit.documentId()))
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
