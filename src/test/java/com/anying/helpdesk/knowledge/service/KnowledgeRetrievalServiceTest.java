package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * 验证两路共有的 Chunk 会累加 RRF 分数，并按融合分数而非原始分数排序。
     */
    @Test
    @DisplayName("BM25 与 KNN 候选按标准 RRF 去重融合")
    void fusesBm25AndVectorCandidatesWithRrf() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                knowledgeBaseService,
                embeddingModelProvider,
                chunkStore
        );
        float[] queryVector = new float[]{0.1F, 0.2F, 0.3F};
        when(embeddingModelProvider.get()).thenReturn(new ResolvedEmbeddingModel(
                8L,
                "embedding-model",
                3,
                embeddingModel
        ));
        when(embeddingModel.embed("如何登录")).thenReturn(queryVector);
        KnowledgeChunkSearchHit chunkA = hit("3-1", 1, "登录说明", 8.8D);
        KnowledgeChunkSearchHit chunkB = hit("3-2", 2, "密码帮助", 7.7D);
        KnowledgeChunkSearchHit chunkC = hit("3-3", 3, "账号帮助", 0.7D);
        when(chunkStore.search(3L, 8L, "embedding-model", "如何登录", queryVector, 50, 100))
                .thenReturn(new KnowledgeChunkSearchResults(
                        List.of(chunkA, chunkB),
                        List.of(
                                withScore(chunkB, 0.95D),
                                chunkC,
                                withScore(chunkA, 0.65D)
                        )
                ));

        KnowledgeSearchResponse response = service.search(3L, new KnowledgeSearchRequest("  如何登录  ", 2));

        assertThat(response.bm25CandidateCount()).isEqualTo(2);
        assertThat(response.vectorCandidateCount()).isEqualTo(3);
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).chunkSequence()).isEqualTo(2);
        assertThat(response.results().get(0).bm25Rank()).isEqualTo(2);
        assertThat(response.results().get(0).vectorRank()).isEqualTo(1);
        assertThat(response.results().get(1).chunkSequence()).isEqualTo(1);
        verify(chunkStore).ensureIndex(3);
        verify(knowledgeBaseService).getById(3L);
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
