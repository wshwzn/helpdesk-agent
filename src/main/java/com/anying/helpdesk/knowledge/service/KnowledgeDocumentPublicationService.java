package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.dto.ApproveKnowledgeRevisionRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeRevisionApprovalResponse;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentPublicationService {

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final KnowledgeDocumentMapper documentMapper;
    private final Clock knowledgeClock;

    /**
     * 配置草稿的来源、业务版本及适用条件，并要求重新索引以刷新 Chunk 元数据快照。
     */
    @Transactional
    public KnowledgeDocumentResponse updateMetadata(Long knowledgeBaseId, Long documentId,
                                                   UpdateKnowledgeDocumentMetadataRequest request) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        requireVersion(document, request.version());
        if (document.getPredecessorDocumentId() != null) {
            throw new KnowledgeDocumentConflictException("revision metadata is fixed at upload; create a new candidate to change it");
        }
        if (document.getPublicationStatus() != KnowledgePublicationStatus.DRAFT
                || document.getStatus() == KnowledgeDocumentStatus.UPLOADING
                || document.getStatus() == KnowledgeDocumentStatus.PROCESSING) {
            throw new KnowledgeDocumentConflictException("only idle draft documents can change metadata");
        }
        if (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom())) {
            throw new InvalidKnowledgeDocumentException("validUntil must be later than validFrom");
        }
        // 显式 SET 空截止时间，避免 MyBatis-Plus 默认忽略 null 而保留旧值。
        LambdaUpdateWrapper<KnowledgeDocument> update = new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getVersion, request.version())
                .set(KnowledgeDocument::getSourceKey, request.sourceKey())
                .set(KnowledgeDocument::getBusinessVersion, request.businessVersion())
                .set(KnowledgeDocument::getApplicabilityScope, request.applicabilityScope())
                .set(KnowledgeDocument::getValidFrom, request.validFrom())
                .set(KnowledgeDocument::getValidUntil, request.validUntil())
                .set(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.PENDING)
                .set(KnowledgeDocument::getChunkCount, 0)
                .set(KnowledgeDocument::getErrorCode, null)
                .set(KnowledgeDocument::getVersion, request.version() + 1)
                .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now());
        try {
            if (documentMapper.update(null, update) != 1) {
                throw new KnowledgeDocumentConflictException("knowledge document was updated concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeDocumentConflictException("source, business version and scope already exist in this knowledge base");
        }
        return KnowledgeDocumentResponse.from(persistenceService.get(knowledgeBaseId, documentId));
    }

    /**
     * 发布已完成索引和元数据配置的草稿；是否当前可用仍由业务有效期决定。
     */
    @Transactional
    public KnowledgeDocumentResponse publish(Long knowledgeBaseId, Long documentId, Integer version) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        requireVersion(document, version);
        if (document.getPredecessorDocumentId() != null) {
            throw new KnowledgeDocumentConflictException("revision candidates must be approved through the replacement endpoint");
        }
        if (document.getPublicationStatus() != KnowledgePublicationStatus.DRAFT
                || document.getStatus() != KnowledgeDocumentStatus.READY) {
            throw new KnowledgeDocumentConflictException("only READY draft documents can be published");
        }
        if (document.getSourceKey() == null || document.getBusinessVersion() == null || document.getValidFrom() == null) {
            throw new KnowledgeDocumentConflictException("configure business metadata and reindex before publishing");
        }
        return changePublication(document, KnowledgePublicationStatus.PUBLISHED);
    }

    /**
     * 撤回已发布文档，使后续检索立即排除其所有 ES 片段。
     */
    @Transactional
    public KnowledgeDocumentResponse withdraw(Long knowledgeBaseId, Long documentId, Integer version) {
        KnowledgeDocument document = persistenceService.get(knowledgeBaseId, documentId);
        KnowledgeDocument predecessor = document.getPredecessorDocumentId() == null ? null
                : persistenceService.lock(knowledgeBaseId, document.getPredecessorDocumentId());
        document = persistenceService.lock(knowledgeBaseId, documentId);
        requireVersion(document, version);
        if (document.getPublicationStatus() != KnowledgePublicationStatus.PUBLISHED) {
            throw new KnowledgeDocumentConflictException("only published documents can be withdrawn");
        }
        LocalDateTime now = LocalDateTime.now(knowledgeClock);
        if (document.getSupersededAt() != null && document.getSupersededAt().isAfter(now)) {
            throw new KnowledgeDocumentConflictException("cancel the scheduled successor before withdrawing its predecessor");
        }
        if (predecessor != null && Objects.equals(predecessor.getSuccessorDocumentId(), documentId)
                && document.getActivatedAt() != null && document.getActivatedAt().isAfter(now)) {
            // 待生效新版撤回时取消旧版截止边界；已生效后禁止静默恢复旧制度。
            int changed = documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, predecessor.getId())
                    .eq(KnowledgeDocument::getVersion, predecessor.getVersion())
                    .set(KnowledgeDocument::getSuccessorDocumentId, null)
                    .set(KnowledgeDocument::getSupersededAt, null)
                    .set(KnowledgeDocument::getUpdatedAt, now)
                    .set(KnowledgeDocument::getVersion, predecessor.getVersion() + 1));
            if (changed != 1) {
                throw new KnowledgeDocumentConflictException("predecessor was updated concurrently");
            }
        }
        return changePublication(document, KnowledgePublicationStatus.WITHDRAWN);
    }

    /** 同时审核双方版本，在一个事务中安排旧版退出和新版启用，未来交接无需定时改状态。 */
    @Transactional
    public KnowledgeRevisionApprovalResponse approveRevision(Long baseId, Long candidateId,
                                                             ApproveKnowledgeRevisionRequest request) {
        KnowledgeDocument preview = persistenceService.get(baseId, candidateId);
        if (preview.getPredecessorDocumentId() == null) {
            throw new KnowledgeDocumentConflictException("document is not a revision candidate");
        }
        KnowledgeDocument old = persistenceService.lock(baseId, preview.getPredecessorDocumentId());
        KnowledgeDocument candidate = persistenceService.lock(baseId, candidateId);
        requireVersion(old, request.predecessorVersion());
        requireVersion(candidate, request.version());
        LocalDateTime now = LocalDateTime.now(knowledgeClock).truncatedTo(ChronoUnit.MICROS);
        KnowledgeRevisionRules.requireReplaceable(old, now);
        if (candidate.getPublicationStatus() != KnowledgePublicationStatus.DRAFT
                || candidate.getStatus() != KnowledgeDocumentStatus.READY
                || candidate.getValidFrom() == null
                || !Objects.equals(candidate.getSourceKey(), old.getSourceKey())
                || !Objects.equals(candidate.getApplicabilityScope(), old.getApplicabilityScope())) {
            throw new KnowledgeDocumentConflictException("revision must be READY with matching source and scope");
        }
        LocalDateTime effectiveAt = candidate.getValidFrom().isAfter(now) ? candidate.getValidFrom() : now;
        if ((old.getValidUntil() != null && old.getValidUntil().isBefore(effectiveAt))
                || (candidate.getValidUntil() != null && !candidate.getValidUntil().isAfter(effectiveAt))) {
            throw new KnowledgeDocumentConflictException("revision validity would create a gap or is already expired");
        }
        old.setSuccessorDocumentId(candidateId);
        old.setSupersededAt(effectiveAt);
        old.setUpdatedAt(now);
        if (documentMapper.updateById(old) != 1) {
            throw new KnowledgeDocumentConflictException("predecessor was updated concurrently");
        }
        candidate.setActivatedAt(effectiveAt);
        KnowledgeDocumentResponse published = changePublication(candidate, KnowledgePublicationStatus.PUBLISHED);
        return new KnowledgeRevisionApprovalResponse(KnowledgeDocumentResponse.from(old), published, effectiveAt);
    }

    /**
     * 使用乐观锁提交发布状态，避免发布、撤回与其他更新互相覆盖。
     */
    private KnowledgeDocumentResponse changePublication(KnowledgeDocument document, KnowledgePublicationStatus status) {
        document.setPublicationStatus(status);
        document.setUpdatedAt(LocalDateTime.now());
        if (documentMapper.updateById(document) != 1) {
            throw new KnowledgeDocumentConflictException("knowledge document was updated concurrently");
        }
        return KnowledgeDocumentResponse.from(document);
    }

    /**
     * 拒绝基于旧页面版本提交的发布管理操作。
     */
    private void requireVersion(KnowledgeDocument document, Integer version) {
        if (!Objects.equals(document.getVersion(), version)) {
            throw new KnowledgeDocumentConflictException("knowledge document was updated concurrently");
        }
    }
}
