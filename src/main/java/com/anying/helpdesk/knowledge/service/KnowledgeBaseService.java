package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeRetrievalSettingsRequest;

import java.util.List;
import java.util.Optional;

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
     * 查询客户对话当前使用的默认知识库；尚未配置时返回空。
     */
    Optional<KnowledgeBaseResponse> findDefault();

    /**
     * 将指定知识库设为唯一默认项，供客户对话中的 RAG 工具使用。
     */
    KnowledgeBaseResponse setDefault(Long id);

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
