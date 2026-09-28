package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.domain.KnowledgeBase;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseNotFoundException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentNotFoundException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeBaseMapper;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentPersistenceService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 在上传对象前创建 UPLOADING 元数据，便于失败后审计。
     */
    @Transactional
    public KnowledgeDocument createUploading(
            Long knowledgeBaseId,
            String originalName,
            String storageProvider,
            String bucketName,
            String objectKey,
            String contentType,
            String fileExtension,
            long sizeBytes,
            String sha256,
            Long adminId
    ) {
        requireKnowledgeBase(knowledgeBaseId);
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalName(originalName);
        document.setStorageProvider(storageProvider);
        document.setBucketName(bucketName);
        document.setObjectKey(objectKey);
        document.setContentType(contentType);
        document.setFileExtension(fileExtension);
        document.setSizeBytes(sizeBytes);
        document.setSha256(sha256);
        document.setStatus(KnowledgeDocumentStatus.UPLOADING);
        document.setChunkCount(0);
        document.setCreatedBy(adminId);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        knowledgeDocumentMapper.insert(document);
        return document;
    }

    /**
     * 将对象上传成功的文档推进到等待解析状态。
     */
    @Transactional
    public KnowledgeDocument markPending(Long documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        document.setStatus(KnowledgeDocumentStatus.PENDING);
        document.setErrorCode(null);
        document.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(document);
        return document;
    }

    /**
     * 记录上传或状态推进失败的脱敏错误码。
     */
    @Transactional
    public void markFailed(Long documentId, String errorCode) {
        KnowledgeDocument document = requireDocument(documentId);
        document.setStatus(KnowledgeDocumentStatus.FAILED);
        document.setErrorCode(errorCode);
        document.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(document);
    }

    /**
     * 校验文档可处理后推进到 PROCESSING；重复触发的并发请求只允许一个成功。
     */
    @Transactional
    public KnowledgeDocument markProcessing(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = get(knowledgeBaseId, documentId);
        if (document.getStatus() == KnowledgeDocumentStatus.UPLOADING) {
            throw new KnowledgeDocumentConflictException("uploading document cannot be indexed");
        }
        if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING) {
            throw new KnowledgeDocumentConflictException("knowledge document is already being processed");
        }
        document.setStatus(KnowledgeDocumentStatus.PROCESSING);
        document.setChunkCount(0);
        document.setErrorCode(null);
        document.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(document);
        return document;
    }

    /**
     * 将处理中的文档标记为可检索，并保存 ES 中的有效 Chunk 数量。
     */
    @Transactional
    public void markReady(Long documentId, int chunkCount) {
        KnowledgeDocument document = requireProcessingDocument(documentId);
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setChunkCount(chunkCount);
        document.setErrorCode(null);
        document.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(document);
    }

    /**
     * 将处理中的文档标记为失败，并记录固定脱敏错误码。
     */
    @Transactional
    public void markIndexingFailed(Long documentId, String errorCode) {
        KnowledgeDocument document = requireProcessingDocument(documentId);
        document.setStatus(KnowledgeDocumentStatus.FAILED);
        document.setChunkCount(0);
        document.setErrorCode(errorCode);
        document.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(document);
    }

    /**
     * 分页读取指定知识库的文档，并按新到旧排序。
     */
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeDocumentResponse> page(Long knowledgeBaseId, long page, long size) {
        requireKnowledgeBase(knowledgeBaseId);
        Page<KnowledgeDocument> result = knowledgeDocumentMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(KnowledgeDocument::getCreatedAt)
        );
        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords().stream().map(KnowledgeDocumentResponse::from).toList()
        );
    }

    /**
     * 查询属于指定知识库的文档实体，避免跨知识库误操作。
     */
    @Transactional(readOnly = true)
    public KnowledgeDocument get(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId));
        if (document == null) {
            throw new KnowledgeDocumentNotFoundException(knowledgeBaseId, documentId);
        }
        return document;
    }

    /**
     * 在对象删除成功后移除对应文档元数据。
     */
    @Transactional
    public void delete(Long knowledgeBaseId, Long documentId) {
        get(knowledgeBaseId, documentId);
        knowledgeDocumentMapper.deleteById(documentId);
    }

    /**
     * 校验知识库存在，文档元数据不能引用不存在的知识库。
     */
    private void requireKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseMapper.selectById(knowledgeBaseId) == null) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }
    }

    /**
     * 查询文档实体，不存在时抛出统一异常。
     */
    private KnowledgeDocument requireDocument(Long documentId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null) {
            throw new KnowledgeDocumentNotFoundException(-1L, documentId);
        }
        return document;
    }

    /**
     * 查询并校验处于 PROCESSING 状态的文档，避免过期后台任务覆盖新状态。
     */
    private KnowledgeDocument requireProcessingDocument(Long documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getStatus() != KnowledgeDocumentStatus.PROCESSING) {
            throw new KnowledgeDocumentConflictException("knowledge document is not being processed");
        }
        return document;
    }

    /**
     * 使用版本号推进文档状态，防止并发任务互相覆盖。
     */
    private void updateWithOptimisticLock(KnowledgeDocument document) {
        if (knowledgeDocumentMapper.updateById(document) == 0) {
            throw new KnowledgeDocumentConflictException("knowledge document was updated concurrently");
        }
    }
}
