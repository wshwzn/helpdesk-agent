package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRevisionUploadResponse;
import com.anying.helpdesk.knowledge.dto.UploadKnowledgeRevisionRequest;
import com.anying.helpdesk.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentRevisionService {
    private final KnowledgeUploadSupport uploads;
    private final KnowledgeRevisionPersistenceService revisions;
    private final ObjectStorageService storage;

    /** 创建独立候选并自动入队；完全相同的重复请求复用结果，不重复上传或向量化。 */
    public KnowledgeRevisionUploadResponse upload(Long baseId, Long predecessorId, MultipartFile file,
                                                 UploadKnowledgeRevisionRequest request, Long adminId) {
        KnowledgeUploadFile prepared = uploads.prepare(file);
        KnowledgeRevisionPreparation result = revisions.prepare(baseId, predecessorId, request, prepared,
                storage.provider(), storage.bucketName(), uploads.objectKey(baseId, prepared.extension()), adminId);
        if (!"CREATED".equals(result.outcome())) {
            return new KnowledgeRevisionUploadResponse(result.outcome(), KnowledgeDocumentResponse.from(result.document()));
        }
        Long id = result.document().getId();
        try {
            storage.upload(result.document().getObjectKey(), new ByteArrayInputStream(prepared.content()),
                    prepared.content().length, prepared.contentType());
        } catch (RuntimeException exception) {
            revisions.failUpload(id, "STORAGE_UPLOAD_FAILED");
            throw exception;
        }
        try {
            return new KnowledgeRevisionUploadResponse("CREATED", revisions.finishUpload(baseId, id));
        } catch (RuntimeException exception) {
            // 保留已上传对象供排查或显式重试，不能误删旧版文件。
            revisions.failUpload(id, "DOCUMENT_STATE_UPDATE_FAILED");
            throw exception;
        }
    }
}
