package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.index.IndexedKnowledgeChunk;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.knowledge.processing.DocumentChunk;
import com.anying.helpdesk.knowledge.processing.KnowledgeDocumentParser;
import com.anying.helpdesk.knowledge.processing.KnowledgeProcessingProperties;
import com.anying.helpdesk.knowledge.processing.KnowledgeTextChunker;
import com.anying.helpdesk.knowledge.processing.ParsedDocument;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import com.anying.helpdesk.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentIndexWorkerTest {

    @Mock
    private KnowledgeDocumentPersistenceService persistenceService;
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private KnowledgeDocumentParser documentParser;
    @Mock
    private KnowledgeTextChunker textChunker;
    @Mock
    private DefaultEmbeddingModelProvider embeddingModelProvider;
    @Mock
    private KnowledgeChunkStore chunkStore;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private KnowledgeIndexTaskStore taskStore;

    private final KnowledgeIndexClaim claim = new KnowledgeIndexClaim(3L, 7L, "build-one");

    private KnowledgeDocumentIndexWorker worker;
    private KnowledgeDocument document;

    /**
     * 为每个用例准备一份处理中的文档和小批量配置。
     */
    @BeforeEach
    void setUp() {
        KnowledgeProcessingProperties properties = new KnowledgeProcessingProperties();
        properties.setEmbeddingBatchSize(2);
        worker = new KnowledgeDocumentIndexWorker(
                persistenceService,
                objectStorageService,
                documentParser,
                textChunker,
                properties,
                embeddingModelProvider,
                chunkStore,
                taskStore
        );
        document = new KnowledgeDocument();
        document.setId(7L);
        document.setKnowledgeBaseId(3L);
        document.setOriginalName("guide.md");
        document.setStorageProvider("local");
        document.setBucketName("local");
        document.setObjectKey("knowledge/guide.md");
        document.setContentType("text/markdown");
    }

    @Test
    @DisplayName("索引成功后批量写入 Chunk 并标记文档就绪")
    void indexesChunksAndMarksDocumentReady() {
        when(taskStore.owns(claim)).thenReturn(true);
        List<DocumentChunk> chunks = List.of(
                new DocumentChunk(1, 0, 5, "第一段"),
                new DocumentChunk(2, 4, 9, "第二段")
        );
        when(persistenceService.get(3L, 7L)).thenReturn(document);
        when(objectStorageService.provider()).thenReturn("local");
        when(objectStorageService.download("local", "knowledge/guide.md"))
                .thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(documentParser.parse(anyInputStream(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ParsedDocument("content", "text/markdown"));
        when(textChunker.chunk("content")).thenReturn(chunks);
        when(embeddingModelProvider.get()).thenReturn(
                new ResolvedEmbeddingModel(9L, "embedding-model", 3, embeddingModel)
        );
        when(embeddingModel.embed(anyList())).thenReturn(List.of(
                new float[]{1F, 0F, 0F},
                new float[]{0F, 1F, 0F}
        ));

        worker.index(claim);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IndexedKnowledgeChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkStore).writeBuildChunks(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("build-one"),
                org.mockito.ArgumentMatchers.eq(3), chunksCaptor.capture());
        assertThat(chunksCaptor.getValue()).hasSize(2);
        assertThat(chunksCaptor.getValue().getFirst().content()).isEqualTo("第一段");
        verify(taskStore).complete(claim, 2);
    }

    @Test
    @DisplayName("向量维度不匹配时记录任务失败且不删除其他批次")
    void rejectsUnexpectedEmbeddingDimensions() {
        when(taskStore.owns(claim)).thenReturn(true);
        when(persistenceService.get(3L, 7L)).thenReturn(document);
        when(objectStorageService.provider()).thenReturn("local");
        when(objectStorageService.download("local", "knowledge/guide.md"))
                .thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(documentParser.parse(anyInputStream(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ParsedDocument("content", "text/markdown"));
        when(textChunker.chunk("content")).thenReturn(List.of(new DocumentChunk(1, 0, 7, "content")));
        when(embeddingModelProvider.get()).thenReturn(
                new ResolvedEmbeddingModel(9L, "embedding-model", 3, embeddingModel)
        );
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[]{1F, 0F}));

        worker.index(claim);

        verify(chunkStore, never()).deleteDocument(7L);
        verify(chunkStore, never()).writeBuildChunks(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                anyList()
        );
        verify(taskStore).fail(claim, "EMBEDDING_FAILED", true);
    }

    /** 失去租约后不再下载文件或继续外部模型调用。 */
    @Test
    @DisplayName("过期工作者在执行前停止且不覆盖任务状态")
    void stopsWithoutOwnership() {
        worker.index(claim);
        org.mockito.Mockito.verifyNoInteractions(objectStorageService, embeddingModelProvider, chunkStore);
        verify(taskStore, never()).fail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * 匹配解析器使用的任意输入流参数。
     */
    private java.io.InputStream anyInputStream() {
        return org.mockito.ArgumentMatchers.any(java.io.InputStream.class);
    }
}
