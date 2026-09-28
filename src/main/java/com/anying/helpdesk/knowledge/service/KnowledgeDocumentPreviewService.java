package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.DocumentChunkPreviewResponse;

public interface KnowledgeDocumentPreviewService {

    /**
     * 下载、解析并分页返回文档分块预览，不修改文档处理状态。
     */
    DocumentChunkPreviewResponse preview(Long knowledgeBaseId, Long documentId, long page, long size);
}
