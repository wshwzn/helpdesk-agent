package com.anying.helpdesk.knowledge.source;

import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.service.KnowledgeUploadSupport;
import com.anying.helpdesk.storage.ObjectStorageService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Service
@RequiredArgsConstructor
public class KnowledgeSourceSyncService {
    private final KnowledgeSourceStore sources;
    private final KnowledgeSourceImportService imports;
    private final ObjectProvider<OssSourceGateway> gatewayProvider;
    private final ObjectStorageService storage;
    private final KnowledgeUploadSupport uploads;
    private final KnowledgeSourceProperties properties;
    private final Validator validator;

    /** 手动和定时共用扫描流程，先取得完整清单，单项错误不阻止其他对象导入。 */
    public KnowledgeSource scan(Long baseId, Long sourceId) {
        var claim = sources.claim(baseId, sourceId);
        try {
            var gateway = gatewayProvider.getIfAvailable();
            if (gateway == null || !"aliyun-oss".equals(storage.provider())
                    || !storage.bucketName().equals(claim.source().bucketName())) {
                throw new KnowledgeDocumentConflictException("configured OSS bucket no longer matches this source");
            }
            var keys = gateway.list(claim.source().objectPrefix(), properties.getMaxObjects());
            int added = 0, updated = 0, unchanged = 0, failed = 0;
            for (String key : keys) {
                sources.renew(claim);
                if (!key.startsWith(claim.source().objectPrefix()) || key.length() > 1024) {
                    throw new KnowledgeDocumentConflictException("source listing escaped the configured prefix");
                }
                var item = sources.seen(claim, key);
                try {
                    String outcome = syncItem(gateway, claim, item);
                    switch (outcome) {
                        case "ADDED" -> added++;
                        case "UPDATED" -> updated++;
                        default -> unchanged++;
                    }
                } catch (RuntimeException exception) {
                    String code = exception instanceof InvalidKnowledgeDocumentException ? "SOURCE_METADATA_OR_FILE_INVALID"
                            : exception instanceof KnowledgeDocumentConflictException || exception instanceof DataIntegrityViolationException
                            ? "SOURCE_VERSION_CONFLICT" : "SOURCE_OBJECT_FAILED";
                    sources.error(claim, item.id(), code);
                    failed++;
                }
            }
            return sources.finish(claim, added, updated, unchanged, failed);
        } catch (RuntimeException exception) {
            sources.fail(claim);
            throw exception;
        }
    }

    /** 未变文件只读头；变化文件保存独立快照，随后由短事务提交候选及任务。 */
    private String syncItem(OssSourceGateway gateway, KnowledgeSourceClaim claim, KnowledgeSourceItem item) {
        var head = gateway.head(item.objectKey());
        String fingerprint = head.fingerprint();
        if (fingerprint.equals(item.acceptedFingerprint())) {
            sources.unchanged(claim, item);
            return "UNCHANGED";
        }
        var metadata = SourceBusinessMetadata.parse(head.metadata(), validator);
        var file = uploads.prepare(item.objectKey(), gateway.read(item.objectKey(), head));
        sources.renew(claim);
        if (!imports.needsSnapshot(claim, item.id(), fingerprint, file, metadata)) return "UNCHANGED";
        String snapshotKey = uploads.objectKey(claim.source().knowledgeBaseId(), file.extension());
        storage.upload(snapshotKey, new ByteArrayInputStream(file.content()), file.content().length, file.contentType());
        // 不在提交结果不确定时删快照；孤立对象留给独立清理，避免误删已提交候选。
        return imports.accept(claim, item.id(), fingerprint, file, metadata, snapshotKey);
    }
}
