package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeRetrievalSettingsRequest;

import java.util.List;

public interface KnowledgeBaseService {

    /**
     * 创建一个由管理员维护的知识库。
     */
    KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request, Long adminId);

    /**
     * 查询全部知识库及其文档数量。
     */
    List<KnowledgeBaseResponse> list();

    /**
     * 查询指定知识库详情。
     */
    KnowledgeBaseResponse getById(Long id);

    /**
     * 读取指定知识库的检索参数，不执行文档数量统计。
     */
    KnowledgeRetrievalSettingsResponse getRetrievalSettings(Long id);

    /**
     * 更新指定知识库的查询时检索参数。
     */
    KnowledgeRetrievalSettingsResponse updateRetrievalSettings(
            Long id,
            UpdateKnowledgeRetrievalSettingsRequest request
    );

    /**
     * 删除没有文档的知识库。
     */
    void delete(Long id);
}
