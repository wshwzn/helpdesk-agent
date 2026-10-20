package com.anying.helpdesk.knowledge.source;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.UploadKnowledgeRevisionRequest;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.anying.helpdesk.knowledge.service.*;
import com.anying.helpdesk.storage.ObjectStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeSourceImportService {
    private final KnowledgeSourceStore sources;
    private final KnowledgeDocumentPersistenceService documents;
    private final KnowledgeDocumentMapper mapper;
    private final KnowledgeRevisionPersistenceService revisions;
    private final ObjectStorageService storage;
    private final JdbcTemplate jdbc;
    private final Clock knowledgeClock;

    /** 快照完成后原子创建草稿或 B2 候选、B1 任务和来源映射，失败整体回滚。 */
    @Transactional
    public String accept(KnowledgeSourceClaim claim, Long itemId, String fingerprint, KnowledgeUploadFile file,
                         SourceBusinessMetadata metadata, String snapshotKey) {
        sources.fence(claim);
        lockBase(claim.source().knowledgeBaseId());
        var item = sources.item(claim.source().id(), itemId);
        Long baseId = claim.source().knowledgeBaseId();
        var plan = plan(claim, item, file, metadata);
        if (plan.unchanged()) {
            sources.accept(claim, itemId, item.lastDocumentId(), fingerprint);
            return "UNCHANGED";
        }
        KnowledgeDocument predecessor = plan.predecessor();
        KnowledgeDocument candidate;
        if (predecessor == null) {
            candidate = documents.createUploading(baseId, file.originalName(), storage.provider(), storage.bucketName(),
                    snapshotKey, file.contentType(), file.extension(), file.content().length, file.sha256(), claim.source().createdBy());
            candidate.setSourceKey(metadata.sourceKey());
            candidate.setApplicabilityScope(metadata.scope());
            candidate.setBusinessVersion(metadata.businessVersion());
            candidate.setValidFrom(metadata.validFrom());
            candidate.setValidUntil(metadata.validUntil());
        } else {
            var prepared = revisions.prepare(baseId, predecessor.getId(),
                    new UploadKnowledgeRevisionRequest(predecessor.getVersion(), metadata.businessVersion(), metadata.validFrom(), metadata.validUntil()),
                    file, storage.provider(), storage.bucketName(), snapshotKey, claim.source().createdBy());
            if (!"CREATED".equals(prepared.outcome())) throw new KnowledgeDocumentConflictException("candidate identity was already reserved");
            candidate = prepared.document();
        }
        candidate.setSourceItemId(itemId);
        if (mapper.updateById(candidate) != 1) throw new KnowledgeDocumentConflictException("source document changed concurrently");
        revisions.finishUpload(baseId, candidate.getId());
        sources.accept(claim, itemId, candidate.getId(), fingerprint);
        return item.lastDocumentId() == null ? "ADDED" : "UPDATED";
    }

    /** 提前排除重复或冲突，避免周期扫描为相同内容和已知冲突不断上传无用快照。 */
    @Transactional
    public boolean needsSnapshot(KnowledgeSourceClaim claim, Long itemId, String fingerprint,
                                 KnowledgeUploadFile file, SourceBusinessMetadata metadata) {
        sources.fence(claim);
        lockBase(claim.source().knowledgeBaseId());
        var item = sources.item(claim.source().id(), itemId);
        var plan = plan(claim, item, file, metadata);
        if (!plan.unchanged()) return true;
        sources.accept(claim, itemId, item.lastDocumentId(), fingerprint);
        return false;
    }

    private record ImportPlan(boolean unchanged, KnowledgeDocument predecessor) { }

    /** 序列化不同来源对同一业务身份的首次认领，锁后才读取映射以免使用旧事务快照。 */
    private void lockBase(Long baseId) {
        jdbc.queryForObject("SELECT id FROM knowledge_bases WHERE id = ? FOR UPDATE", Long.class, baseId);
    }

    /** 快照前检查一次，提交时再检查一次，拒绝待审核覆盖、身份变化和历史版本回退。 */
    private ImportPlan plan(KnowledgeSourceClaim claim, KnowledgeSourceItem item, KnowledgeUploadFile file,
                            SourceBusinessMetadata metadata) {
        Long baseId = claim.source().knowledgeBaseId();
        Long itemId = item.id();
        KnowledgeDocument last = item.lastDocumentId() == null ? null : documents.get(baseId, item.lastDocumentId());
        if (last != null) {
            if (!Objects.equals(last.getSourceKey(), metadata.sourceKey())
                    || !Objects.equals(last.getApplicabilityScope(), metadata.scope())) {
                throw new KnowledgeDocumentConflictException("source key and scope are immutable for an external object");
            }
            if (samePayload(last, file, metadata)) {
                return new ImportPlan(true, null);
            }
            if (last.getPublicationStatus() == KnowledgePublicationStatus.DRAFT) {
                throw new KnowledgeDocumentConflictException("review or reject the pending candidate before importing another version");
            }
        }
        if (mapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId)
                .eq(KnowledgeDocument::getSourceKey, metadata.sourceKey())
                .eq(KnowledgeDocument::getApplicabilityScope, metadata.scope())
                .and(query -> query.eq(KnowledgeDocument::getBusinessVersion, metadata.businessVersion())
                        .or().isNull(KnowledgeDocument::getSourceItemId)
                        .or().ne(KnowledgeDocument::getSourceItemId, itemId))) > 0) {
            throw new KnowledgeDocumentConflictException("source identity or business version already exists; do not overwrite or roll back a version");
        }
        KnowledgeDocument predecessor = predecessor(baseId, itemId);
        if (predecessor == null) {
            long history = mapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getSourceItemId, itemId)
                    .and(query -> query.eq(KnowledgeDocument::getPublicationStatus, KnowledgePublicationStatus.PUBLISHED)
                            .or().isNotNull(KnowledgeDocument::getActivatedAt)));
            if (history > 0) throw new KnowledgeDocumentConflictException("external source has no replaceable active predecessor");
        }
        return new ImportPlan(false, predecessor);
    }

    /** 查找同一来源对象当前可接替的版本，未到期的未来交接不能被新的扫描覆盖。 */
    private KnowledgeDocument predecessor(Long baseId, Long itemId) {
        var now = LocalDateTime.now(knowledgeClock);
        var active = mapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId).eq(KnowledgeDocument::getSourceItemId, itemId)
                .eq(KnowledgeDocument::getPublicationStatus, KnowledgePublicationStatus.PUBLISHED)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.READY)
                .le(KnowledgeDocument::getValidFrom, now)
                .and(q -> q.isNull(KnowledgeDocument::getValidUntil).or().gt(KnowledgeDocument::getValidUntil, now))
                .and(q -> q.isNull(KnowledgeDocument::getActivatedAt).or().le(KnowledgeDocument::getActivatedAt, now))
                .isNull(KnowledgeDocument::getSuccessorDocumentId));
        if (active.size() > 1) throw new KnowledgeDocumentConflictException("external source has conflicting active documents");
        return active.isEmpty() ? null : active.getFirst();
    }

    /** 内容及全部业务元数据都相同时只刷新观察指纹，不重新向量化。 */
    private boolean samePayload(KnowledgeDocument document, KnowledgeUploadFile file, SourceBusinessMetadata metadata) {
        return Objects.equals(document.getSha256(), file.sha256())
                && Objects.equals(document.getFileExtension(), file.extension())
                && Objects.equals(document.getBusinessVersion(), metadata.businessVersion())
                && Objects.equals(document.getValidFrom(), metadata.validFrom())
                && Objects.equals(document.getValidUntil(), metadata.validUntil());
    }

    /** 管理员拒绝尚未发布且已结束处理的候选，后续来源的新业务版本可重新进入审核。 */
    @Transactional
    public void reject(Long baseId, Long sourceId, Long itemId, Integer version) {
        sources.get(baseId, sourceId);
        jdbc.queryForObject("SELECT id FROM knowledge_sources WHERE id = ? FOR UPDATE", Long.class, sourceId);
        var item = sources.item(sourceId, itemId);
        if (item.lastDocumentId() == null) throw new KnowledgeDocumentConflictException("source item has no candidate");
        var document = documents.lock(baseId, item.lastDocumentId());
        if (!Objects.equals(version, document.getVersion()) || document.getPublicationStatus() != KnowledgePublicationStatus.DRAFT
                || document.getStatus() == KnowledgeDocumentStatus.PROCESSING || document.getStatus() == KnowledgeDocumentStatus.UPLOADING) {
            throw new KnowledgeDocumentConflictException("only an idle current draft can be rejected");
        }
        document.setPublicationStatus(KnowledgePublicationStatus.WITHDRAWN);
        document.setUpdatedAt(LocalDateTime.now(knowledgeClock));
        if (mapper.updateById(document) != 1) throw new KnowledgeDocumentConflictException("candidate changed concurrently");
    }
}
