package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchHit;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkSearchResults;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private DefaultEmbeddingModelProvider embeddingModelProvider;

    @Mock
    private KnowledgeChunkStore chunkStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private KnowledgeDocumentEligibilityService eligibilityService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /**
     * 验证两路共有的 Chunk 会累加 RRF 分数，并按融合分数而非原始分数排序。
     */
    @Test
    @DisplayName("BM25 与 KNN 候选按标准 RRF 去重融合")
    void fusesBm25AndVectorCandidatesWithRrf() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                knowledgeBaseService,
                embeddingModelProvider,
                chunkStore,
                eligibilityService,
                clock
        );
        KnowledgeDocument document = document(3L);
        when(eligibilityService.findEligible(eq(3L), eq("ALL"), any())).thenReturn(Map.of(3L, document));
        float[] queryVector = new float[]{0.1F, 0.2F, 0.3F};
        when(embeddingModelProvider.get()).thenReturn(new ResolvedEmbeddingModel(
                8L,
                "embedding-model",
                3,
                embeddingModel
        ));
        when(embeddingModel.embed("如何登录")).thenReturn(queryVector);
        when(knowledgeBaseService.getRetrievalSettings(3L)).thenReturn(new KnowledgeRetrievalSettingsResponse(
                3L,
                40,
                new BigDecimal("0.650"),
                3,
                0
        ));
        KnowledgeChunkSearchHit chunkA = hit("3-1", 1, "登录说明", 8.8D);
        KnowledgeChunkSearchHit chunkB = hit("3-2", 2, "密码帮助", 7.7D);
        KnowledgeChunkSearchHit chunkC = hit("3-3", 3, "账号帮助", 0.7D);
        when(chunkStore.search(3L, 8L, "embedding-model", "如何登录", queryVector, 40, 80, 0.65D, List.of(3L)))
                .thenReturn(new KnowledgeChunkSearchResults(
                        List.of(chunkA, chunkB),
                        List.of(
                                withScore(chunkB, 0.95D),
                                chunkC,
                                withScore(chunkA, 0.65D)
                        )
                ));

        KnowledgeSearchResponse response = service.search(3L, new KnowledgeSearchRequest("  如何登录  "));

        assertThat(response.candidateLimit()).isEqualTo(40);
        assertThat(response.vectorMinSimilarity()).isEqualByComparingTo("0.650");
        assertThat(response.resultLimit()).isEqualTo(3);
        assertThat(response.bm25CandidateCount()).isEqualTo(2);
        assertThat(response.vectorCandidateCount()).isEqualTo(3);
        assertThat(response.results()).hasSize(3);
        assertThat(response.results().get(0).chunkSequence()).isEqualTo(2);
        assertThat(response.results().get(0).bm25Rank()).isEqualTo(2);
        assertThat(response.results().get(0).vectorRank()).isEqualTo(1);
        assertThat(response.results().get(1).chunkSequence()).isEqualTo(1);
        verify(chunkStore).ensureIndex(3);
        verify(knowledgeBaseService).getRetrievalSettings(3L);
        assertThat(response.results().getFirst().metadata().businessVersion()).isEqualTo("v2");
    }

    /**
     * 没有有效文档时提前返回，不调用付费向量模型，也不向 ES 发送无限制查询。
     */
    @Test
    @DisplayName("无可用文档时跳过向量模型与 ES")
    void skipsExternalCallsWhenNoDocumentIsEligible() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                knowledgeBaseService, embeddingModelProvider, chunkStore, eligibilityService, clock);
        when(eligibilityService.findEligible(3L, "ALL", LocalDateTime.now(clock))).thenReturn(Map.of());
        KnowledgeSearchResponse response = service.search(3L, new KnowledgeSearchRequest("差旅标准"),
                new KnowledgeRetrievalSettingsResponse(3L, 50, BigDecimal.ZERO, 5, 0));
        assertThat(response.results()).isEmpty();
        verifyNoInteractions(embeddingModelProvider, chunkStore);
    }

    /**
     * 验证网络调用期间撤回的文档以及 ES 意外返回的非准入文档都不会进入模型证据。
     */
    @Test
    @DisplayName("检索完成前复核撤回状态并剔除准入范围外片段")
    void rechecksEligibilityAfterExternalCalls() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                knowledgeBaseService, embeddingModelProvider, chunkStore, eligibilityService, clock);
        when(eligibilityService.findEligible(eq(3L), eq("CN_SH"), any()))
                .thenReturn(Map.of(3L, document(3L)), Map.of(9L, document(9L)));
        float[] vector = {1F, 0F};
        when(embeddingModelProvider.get()).thenReturn(new ResolvedEmbeddingModel(8L, "embedding-model", 2, embeddingModel));
        when(embeddingModel.embed("差旅标准")).thenReturn(vector);
        KnowledgeChunkSearchHit withdrawn = hit("3-1", 1, "旧标准", 10);
        KnowledgeChunkSearchHit unexpected = new KnowledgeChunkSearchHit("9-1", 9L, 1, "new.md", "新标准", 9);
        when(chunkStore.search(3L, 8L, "embedding-model", "差旅标准", vector, 50, 100, 0, List.of(3L)))
                .thenReturn(new KnowledgeChunkSearchResults(List.of(withdrawn, unexpected), List.of(withdrawn)));
        KnowledgeSearchResponse response = service.search(3L, new KnowledgeSearchRequest("差旅标准", "CN_SH"),
                new KnowledgeRetrievalSettingsResponse(3L, 50, BigDecimal.ZERO, 5, 0));
        assertThat(response.results()).isEmpty();
        assertThat(response.bm25CandidateCount()).isZero();
    }

    /**
     * 构造用于验证引用版本的权威元数据快照。
     */
    private KnowledgeDocument document(Long id) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setSourceKey("travel-policy");
        document.setBusinessVersion("v2");
        document.setApplicabilityScope("ALL");
        document.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        return document;
    }

    /**
     * 构造包含固定文档元数据的测试命中。
     */
    private KnowledgeChunkSearchHit hit(String chunkId, int sequence, String content, double score) {
        return new KnowledgeChunkSearchHit(chunkId, 3L, sequence, "guide.md", content, score);
    }

    /**
     * 复用 Chunk 展示字段并替换另一检索通道的原始分数。
     */
    private KnowledgeChunkSearchHit withScore(KnowledgeChunkSearchHit hit, double score) {
        return new KnowledgeChunkSearchHit(
                hit.chunkId(),
                hit.documentId(),
                hit.chunkSequence(),
                hit.originalName(),
                hit.content(),
                score
        );
    }
}
