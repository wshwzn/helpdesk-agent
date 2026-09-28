package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;

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
     * 删除没有文档的知识库。
     */
    void delete(Long id);
}
