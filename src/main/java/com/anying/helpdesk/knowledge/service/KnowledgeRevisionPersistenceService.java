package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.UploadKnowledgeRevisionRequest;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeRevisionPersistenceService {
    private final KnowledgeDocumentPersistenceService documents;
    private final KnowledgeDocumentMapper mapper;
    private final KnowledgeDocumentIndexingService indexing;
    private final Clock knowledgeClock;

    /** 锁定旧版后识别重复或预留新候选，串行化同一旧版的并发上传且不在事务内访问 OSS。 */
    @Transactional
    public KnowledgeRevisionPreparation prepare(Long baseId, Long predecessorId, UploadKnowledgeRevisionRequest request,
            KnowledgeUploadFile file, String provider, String bucket, String objectKey, Long adminId) {
        if (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom())) {
            throw new InvalidKnowledgeDocumentException("validUntil must be later than validFrom");
        }
        KnowledgeDocument old = documents.lock(baseId, predecessorId);
        if (old.getSourceKey() == null || old.getBusinessVersion() == null) {
            throw new KnowledgeDocumentConflictException("predecessor business metadata is missing");
        }
        if (samePayload(old, file, request)) {
            return new KnowledgeRevisionPreparation("UNCHANGED", old);
        }
        KnowledgeDocument existing = mapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId)
                .eq(KnowledgeDocument::getSourceKey, old.getSourceKey())
                .eq(KnowledgeDocument::getApplicabilityScope, old.getApplicabilityScope())
                .eq(KnowledgeDocument::getBusinessVersion, request.businessVersion()));
        if (existing != null) {
            if (Objects.equals(existing.getPredecessorDocumentId(), predecessorId) && samePayload(existing, file, request)) {
                return new KnowledgeRevisionPreparation("EXISTING", existing);
            }
            throw new KnowledgeDocumentConflictException("business version already exists with different content or relationship");
        }
        if (!Objects.equals(old.getVersion(), request.predecessorVersion())) {
            throw new KnowledgeDocumentConflictException("predecessor was updated concurrently");
        }
        KnowledgeRevisionRules.requireReplaceable(old, LocalDateTime.now(knowledgeClock));
        KnowledgeDocument candidate = documents.createUploading(baseId, file.originalName(), provider, bucket, objectKey,
                file.contentType(), file.extension(), file.content().length, file.sha256(), adminId);
        candidate.setPredecessorDocumentId(predecessorId);
        candidate.setSourceKey(old.getSourceKey());
        candidate.setApplicabilityScope(old.getApplicabilityScope());
        candidate.setBusinessVersion(request.businessVersion());
        candidate.setValidFrom(request.validFrom());
        candidate.setValidUntil(request.validUntil());
        try {
            if (mapper.updateById(candidate) != 1) {
                throw new KnowledgeDocumentConflictException("candidate was updated concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeDocumentConflictException("business version was reserved concurrently; reload and retry");
        }
        return new KnowledgeRevisionPreparation("CREATED", candidate);
    }

    /** 对象上传完成后原子转为待处理并入队，新版复用持久化索引任务。 */
    @Transactional
    public KnowledgeDocumentResponse finishUpload(Long baseId, Long id) {
        KnowledgeDocument candidate = documents.lock(baseId, id);
        if (candidate.getStatus() != KnowledgeDocumentStatus.UPLOADING) {
            throw new KnowledgeDocumentConflictException("candidate upload state changed");
        }
        documents.markPending(id);
        return indexing.start(baseId, id);
    }

    /** 只补偿仍在上传中的候选，避免提交结果不确定时把已经入队的文档改成失败。 */
    @Transactional
    public void failUpload(Long id, String code) {
        mapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, id).eq(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.UPLOADING)
                .set(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.FAILED)
                .set(KnowledgeDocument::getErrorCode, code)
                .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now(knowledgeClock))
                .setSql("version = version + 1"));
    }

    /** 内容、解析类型和业务元数据均相同才视为无变化，文件名本身不决定版本。 */
    private boolean samePayload(KnowledgeDocument document, KnowledgeUploadFile file, UploadKnowledgeRevisionRequest request) {
        return Objects.equals(document.getSha256(), file.sha256())
                && Objects.equals(document.getFileExtension(), file.extension())
                && Objects.equals(document.getBusinessVersion(), request.businessVersion())
                && Objects.equals(document.getValidFrom(), request.validFrom())
                && Objects.equals(document.getValidUntil(), request.validUntil());
    }
}
