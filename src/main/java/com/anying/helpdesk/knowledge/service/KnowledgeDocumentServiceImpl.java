package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.knowledge.evaluation.service.RagEvaluationService;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeChunkStore chunkStore;
    private final RagEvaluationService ragEvaluationService;
    private final KnowledgeIndexTaskStore taskStore;
    private final KnowledgeUploadSupport uploadSupport;

    /**
     * 完成文件校验、哈希计算、对象上传和文档元数据状态推进。
     */
    @Override
    public KnowledgeDocumentResponse upload(Long knowledgeBaseId, MultipartFile file, Long adminId) {
        KnowledgeUploadFile prepared = uploadSupport.prepare(file);
        byte[] content = prepared.content();
        String objectKey = uploadSupport.objectKey(knowledgeBaseId, prepared.extension());
        KnowledgeDocument document = persistenceService.createUploading(
                knowledgeBaseId,
                prepared.originalName(),
                objectStorageService.provider(),
                objectStorageService.bucketName(),
                objectKey,
                prepared.contentType(),
                prepared.extension(),
                content.length,
                prepared.sha256(),
                adminId
        );
        try {
            objectStorageService.upload(
                    objectKey,
                    new ByteArrayInputStream(content),
                    content.length,
                    prepared.contentType()
            );
        } catch (RuntimeException exception) {
            persistenceService.markFailed(document.getId(), "STORAGE_UPLOAD_FAILED");
            throw exception;
        }
        try {
            return KnowledgeDocumentResponse.from(persistenceService.markPending(document.getId()));
        } catch (RuntimeException exception) {
            compensateUploadedObject(document);
            persistenceService.markFailed(document.getId(), "DOCUMENT_STATE_UPDATE_FAILED");
            throw exception;
        }
    }

    /**
     * 分页查询知识库文档，具体归属校验由持久化服务完成。
     */
    @Override
    public PageResponse<KnowledgeDocumentResponse> page(Long knowledgeBaseId, long page, long size) {
        return persistenceService.page(knowledgeBaseId, page, size);
    }

    /**
     * 查询指定知识库中的单个文档，并隐藏底层对象存储位置。
     */
    @Override
    public KnowledgeDocumentResponse get(Long knowledgeBaseId, Long documentId) {
        return KnowledgeDocumentResponse.from(persistenceService.get(knowledgeBaseId, documentId));
    }

    /**
     * 打开原始文档读取流，供受保护的管理接口在线预览或下载。
     */
    @Override
    public KnowledgeDocumentContent openContent(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        requireCurrentStorageProvider(document);
        return new KnowledgeDocumentContent(
                document.getOriginalName(),
                document.getContentType(),
                document.getSizeBytes(),
                objectStorageService.download(document.getBucketName(), document.getObjectKey())
        );
    }

    /**
     * 先删除对象存储内容，再移除元数据，避免数据库指向不存在的对象。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument preview = persistenceService.get(knowledgeBaseId, documentId);
        if (preview.getPredecessorDocumentId() != null) {
            persistenceService.lock(knowledgeBaseId, preview.getPredecessorDocumentId());
        }
        KnowledgeDocument document = persistenceService.lock(knowledgeBaseId, documentId);
        if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING
                || document.getStatus() == KnowledgeDocumentStatus.UPLOADING) {
            throw new KnowledgeDocumentConflictException("uploading or processing document cannot be deleted");
        }
        if (document.getPublicationStatus() == KnowledgePublicationStatus.PUBLISHED) {
            throw new KnowledgeDocumentConflictException("withdraw published documents before deleting");
        }
        ragEvaluationService.requireDocumentNotReferenced(documentId);
        persistenceService.requireNoRevisionHistory(document);
        requireCurrentStorageProvider(document);
        if (document.getStatus() == KnowledgeDocumentStatus.READY || document.getActiveIndexBuildId() != null
                || taskStore.find(documentId) != null) {
            chunkStore.deleteDocument(documentId);
        }
        objectStorageService.delete(document.getBucketName(), document.getObjectKey());
        persistenceService.delete(knowledgeBaseId, documentId);
    }

    /**
     * 防止当前配置误删由其他存储提供方保存的历史对象。
     */
    private void requireCurrentStorageProvider(KnowledgeDocument document) {
        if (!objectStorageService.provider().equals(document.getStorageProvider())) {
            throw new KnowledgeStorageException("stored document belongs to another storage provider");
        }
    }

    /**
     * 状态推进失败时尽力删除已经上传的对象，避免产生无主文件。
     */
    private void compensateUploadedObject(KnowledgeDocument document) {
        try {
            objectStorageService.delete(document.getBucketName(), document.getObjectKey());
        } catch (RuntimeException cleanupException) {
            log.error("Failed to compensate uploaded knowledge document {}", document.getId());
        }
    }
}
