package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentParsingException;
import com.anying.helpdesk.knowledge.exception.KnowledgeIndexException;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.knowledge.index.IndexedKnowledgeChunk;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.knowledge.processing.DocumentChunk;
import com.anying.helpdesk.knowledge.processing.KnowledgeDocumentParser;
import com.anying.helpdesk.knowledge.processing.KnowledgeProcessingProperties;
import com.anying.helpdesk.knowledge.processing.KnowledgeTextChunker;
import com.anying.helpdesk.knowledge.processing.ParsedDocument;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import com.anying.helpdesk.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeDocumentIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentIndexWorker.class);

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeDocumentParser documentParser;
    private final KnowledgeTextChunker textChunker;
    private final KnowledgeProcessingProperties processingProperties;
    private final DefaultEmbeddingModelProvider embeddingModelProvider;
    private final KnowledgeChunkStore chunkStore;

    /**
     * 在后台完成下载、解析、分块、向量化和 ES 写入，并最终推进文档状态。
     */
    @Async("knowledgeIndexExecutor")
    public void index(Long knowledgeBaseId, Long documentId) {
        try {
            KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
            requireCurrentStorageProvider(document);
            List<DocumentChunk> chunks = parseChunks(document);
            if (chunks.isEmpty()) {
                throw new KnowledgeDocumentParsingException("document contains no indexable text");
            }
            ResolvedEmbeddingModel embeddingModel = embeddingModelProvider.get();
            List<float[]> vectors = embed(chunks, embeddingModel);
            List<IndexedKnowledgeChunk> indexedChunks = toIndexedChunks(document, chunks, vectors, embeddingModel);
            chunkStore.replaceDocumentChunks(documentId, embeddingModel.dimensions(), indexedChunks);
            persistenceService.markReady(documentId, indexedChunks.size());
        } catch (RuntimeException exception) {
            compensateFailedIndex(documentId);
            markFailed(documentId, failureCode(exception));
            log.warn(
                    "Knowledge document indexing failed for document {} ({})",
                    documentId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    /**
     * 从对象存储读取文档并生成规范化 Chunk。
     */
    private List<DocumentChunk> parseChunks(KnowledgeDocument document) {
        try (InputStream inputStream = objectStorageService.download(
                document.getBucketName(),
                document.getObjectKey()
        )) {
            ParsedDocument parsedDocument = documentParser.parse(
                    inputStream,
                    document.getOriginalName(),
                    document.getContentType()
            );
            return textChunker.chunk(parsedDocument.text());
        } catch (IOException exception) {
            throw new KnowledgeStorageException("failed to close knowledge document stream", exception);
        }
    }

    /**
     * 分批调用 Embedding 模型，并校验数量和每条向量维度。
     */
    private List<float[]> embed(List<DocumentChunk> chunks, ResolvedEmbeddingModel embeddingModel) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        int batchSize = processingProperties.getEmbeddingBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<String> contents = chunks.subList(start, end)
                    .stream()
                    .map(DocumentChunk::content)
                    .toList();
            List<float[]> batchVectors = embeddingModel.embeddingModel().embed(contents);
            if (batchVectors.size() != contents.size()) {
                throw new IllegalStateException("embedding response count does not match the request");
            }
            for (float[] vector : batchVectors) {
                if (vector == null || vector.length != embeddingModel.dimensions()) {
                    throw new IllegalStateException("embedding dimensions do not match the model configuration");
                }
                vectors.add(vector);
            }
        }
        return List.copyOf(vectors);
    }

    /**
     * 将文本块、向量和来源元数据合并为 ES 索引文档。
     */
    private List<IndexedKnowledgeChunk> toIndexedChunks(
            KnowledgeDocument document,
            List<DocumentChunk> chunks,
            List<float[]> vectors,
            ResolvedEmbeddingModel embeddingModel
    ) {
        Instant indexedAt = Instant.now();
        List<IndexedKnowledgeChunk> indexedChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            indexedChunks.add(new IndexedKnowledgeChunk(
                    document.getKnowledgeBaseId(),
                    document.getId(),
                    chunk.sequence(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    chunk.content(),
                    vectors.get(index),
                    document.getOriginalName(),
                    embeddingModel.configId(),
                    embeddingModel.modelName(),
                    indexedAt,
                    KnowledgeDocumentMetadata.from(document)
            ));
        }
        return List.copyOf(indexedChunks);
    }

    /**
     * 防止当前存储配置读取由其他提供方保存的历史对象。
     */
    private void requireCurrentStorageProvider(KnowledgeDocument document) {
        if (!objectStorageService.provider().equals(document.getStorageProvider())) {
            throw new KnowledgeStorageException("stored document belongs to another storage provider");
        }
    }

    /**
     * 后台任务失败时尽力移除可能已部分写入的 Chunk。
     */
    private void compensateFailedIndex(Long documentId) {
        try {
            chunkStore.deleteDocument(documentId);
        } catch (RuntimeException cleanupException) {
            log.error(
                    "Failed to clean Elasticsearch chunks for document {} ({})",
                    documentId,
                    cleanupException.getClass().getSimpleName()
            );
        }
    }

    /**
     * 后台任务失败时尽力将文档状态推进为 FAILED。
     */
    private void markFailed(Long documentId, String errorCode) {
        try {
            persistenceService.markIndexingFailed(documentId, errorCode);
        } catch (RuntimeException stateException) {
            log.error(
                    "Failed to mark knowledge document {} as failed ({})",
                    documentId,
                    stateException.getClass().getSimpleName()
            );
        }
    }

    /**
     * 将内部异常分类为固定错误码，避免保存厂商响应或凭据。
     */
    private String failureCode(RuntimeException exception) {
        if (exception instanceof KnowledgeDocumentParsingException) {
            return "DOCUMENT_PARSE_FAILED";
        }
        if (exception instanceof KnowledgeIndexException) {
            return "ELASTICSEARCH_INDEX_FAILED";
        }
        if (exception instanceof KnowledgeStorageException) {
            return "STORAGE_DOWNLOAD_FAILED";
        }
        if (exception instanceof ModelConfigConflictException) {
            return "EMBEDDING_MODEL_UNAVAILABLE";
        }
        return "EMBEDDING_FAILED";
    }
}
