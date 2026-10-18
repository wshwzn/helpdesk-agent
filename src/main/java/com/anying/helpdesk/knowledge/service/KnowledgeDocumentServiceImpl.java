package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
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
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeChunkStore chunkStore;
    private final RagEvaluationService ragEvaluationService;

    /**
     * 完成文件校验、哈希计算、对象上传和文档元数据状态推进。
     */
    @Override
    public KnowledgeDocumentResponse upload(Long knowledgeBaseId, MultipartFile file, Long adminId) {
        byte[] content = readAndValidate(file);
        String originalName = normalizeFilename(file.getOriginalFilename());
        String extension = resolveExtension(originalName);
        String objectKey = buildObjectKey(knowledgeBaseId, extension);
        KnowledgeDocument document = persistenceService.createUploading(
                knowledgeBaseId,
                originalName,
                objectStorageService.provider(),
                objectStorageService.bucketName(),
                objectKey,
                CONTENT_TYPES.get(extension),
                extension,
                content.length,
                sha256(content),
                adminId
        );
        try {
            objectStorageService.upload(
                    objectKey,
                    new ByteArrayInputStream(content),
                    content.length,
                    CONTENT_TYPES.get(extension)
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
    public void delete(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING) {
            throw new KnowledgeDocumentConflictException("processing document cannot be deleted");
        }
        if (document.getPublicationStatus() == KnowledgePublicationStatus.PUBLISHED) {
            throw new KnowledgeDocumentConflictException("withdraw published documents before deleting");
        }
        ragEvaluationService.requireDocumentNotReferenced(documentId);
        requireCurrentStorageProvider(document);
        if (document.getStatus() == KnowledgeDocumentStatus.READY) {
            chunkStore.deleteDocument(documentId);
        }
        objectStorageService.delete(document.getBucketName(), document.getObjectKey());
        persistenceService.delete(knowledgeBaseId, documentId);
    }

    /**
     * 读取上传内容并执行空文件和大小限制校验。
     */
    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidKnowledgeDocumentException("document file must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidKnowledgeDocumentException("document file must not exceed 20 MB");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new InvalidKnowledgeDocumentException("failed to read uploaded document");
        }
    }

    /**
     * 清理客户端路径信息，只保留安全的原始文件名。
     */
    private String normalizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidKnowledgeDocumentException("document filename must not be blank");
        }
        String normalized = originalFilename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new InvalidKnowledgeDocumentException("document filename is invalid or too long");
        }
        return normalized;
    }

    /**
     * 提取并校验允许上传的文档扩展名。
     */
    private String resolveExtension(String filename) {
        int separator = filename.lastIndexOf('.');
        String extension = separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidKnowledgeDocumentException("only txt, md, pdf, and docx documents are supported");
        }
        return extension;
    }

    /**
     * 生成与原始文件名解耦且按知识库分层的唯一对象键。
     */
    private String buildObjectKey(Long knowledgeBaseId, String extension) {
        return "knowledge-bases/" + knowledgeBaseId + "/documents/" + UUID.randomUUID() + "." + extension;
    }

    /**
     * 计算文件内容哈希，供去重分析和完整性校验使用。
     */
    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
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
