package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentService {

    /**
     * 校验并上传一份知识库原始文档。
     */
    KnowledgeDocumentResponse upload(Long knowledgeBaseId, MultipartFile file, Long adminId);

    /**
     * 分页查询指定知识库的文档元数据。
     */
    PageResponse<KnowledgeDocumentResponse> page(Long knowledgeBaseId, long page, long size);

    /**
     * 查询指定知识库中的单个文档元数据。
     */
    KnowledgeDocumentResponse get(Long knowledgeBaseId, Long documentId);

    /**
     * 校验文档归属和存储提供方后打开原始文件读取流。
     */
    KnowledgeDocumentContent openContent(Long knowledgeBaseId, Long documentId);

    /**
     * 删除文档对象及其元数据记录。
     */
    void delete(Long knowledgeBaseId, Long documentId);
}
