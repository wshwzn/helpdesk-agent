package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.DocumentChunkPreviewResponse;
import com.anying.helpdesk.knowledge.dto.DocumentChunkResponse;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.knowledge.processing.DocumentChunk;
import com.anying.helpdesk.knowledge.processing.KnowledgeDocumentParser;
import com.anying.helpdesk.knowledge.processing.KnowledgeProcessingProperties;
import com.anying.helpdesk.knowledge.processing.KnowledgeTextChunker;
import com.anying.helpdesk.knowledge.processing.ParsedDocument;
import com.anying.helpdesk.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentPreviewServiceImpl implements KnowledgeDocumentPreviewService {

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeDocumentParser documentParser;
    private final KnowledgeTextChunker textChunker;
    private final KnowledgeProcessingProperties properties;

    /**
     * 从对象存储读取原文，执行解析和分块后返回指定页的预览数据。
     */
    @Override
    public DocumentChunkPreviewResponse preview(
            Long knowledgeBaseId,
            Long documentId,
            long page,
            long size
    ) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        requireCurrentStorageProvider(document);
        ParsedDocument parsedDocument;
        try (InputStream inputStream = objectStorageService.download(
                document.getBucketName(),
                document.getObjectKey()
        )) {
            parsedDocument = documentParser.parse(
                    inputStream,
                    document.getOriginalName(),
                    document.getContentType()
            );
        } catch (IOException exception) {
            throw new KnowledgeStorageException("failed to close knowledge document stream", exception);
        }
        String normalizedText = textChunker.normalize(parsedDocument.text());
        List<DocumentChunk> allChunks = textChunker.chunk(normalizedText);
        long totalChunks = allChunks.size();
        long pages = totalChunks == 0 ? 0 : (totalChunks + size - 1) / size;
        int fromIndex = calculateFromIndex(page, size, allChunks.size());
        int toIndex = (int) Math.min((long) fromIndex + size, allChunks.size());
        List<DocumentChunkResponse> chunks = allChunks.subList(fromIndex, toIndex)
                .stream()
                .map(DocumentChunkResponse::from)
                .toList();
        return new DocumentChunkPreviewResponse(
                document.getId(),
                document.getOriginalName(),
                parsedDocument.detectedContentType(),
                normalizedText.length(),
                properties.getChunkSize(),
                properties.getChunkOverlap(),
                totalChunks,
                page,
                size,
                pages,
                chunks
        );
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
     * 安全计算分页起始位置，超出末页时返回空列表起点。
     */
    private int calculateFromIndex(long page, long size, int total) {
        if (page - 1 > Long.MAX_VALUE / size) {
            return total;
        }
        return (int) Math.min((page - 1) * size, total);
    }
}
