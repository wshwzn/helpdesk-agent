package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.KnowledgeRetrievalException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentEligibilityService {

    private static final int MAX_ELIGIBLE_DOCUMENTS = 10000;
    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 从 MySQL 筛选已发布、索引就绪且时间和范围适用的文档，供 ES 在召回前限制候选。
     */
    @Transactional(readOnly = true)
    public Map<Long, KnowledgeDocument> findEligible(Long knowledgeBaseId, String scope, LocalDateTime at) {
        if (scope == null || !scope.matches("[A-Z][A-Z0-9_-]{0,39}")) {
            throw new InvalidKnowledgeDocumentException("invalid applicability scope");
        }
        List<KnowledgeDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getPublicationStatus, KnowledgePublicationStatus.PUBLISHED)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.READY)
                .isNotNull(KnowledgeDocument::getSourceKey)
                .isNotNull(KnowledgeDocument::getBusinessVersion)
                .le(KnowledgeDocument::getValidFrom, at)
                .and(query -> query.isNull(KnowledgeDocument::getValidUntil).or().gt(KnowledgeDocument::getValidUntil, at))
                .in(KnowledgeDocument::getApplicabilityScope, List.of("ALL", scope))
                .orderByAsc(KnowledgeDocument::getId)
                .last("LIMIT " + (MAX_ELIGIBLE_DOCUMENTS + 1)));
        if (documents.size() > MAX_ELIGIBLE_DOCUMENTS) {
            throw new KnowledgeRetrievalException("too many eligible documents; narrow the knowledge base before searching");
        }
        Map<Long, KnowledgeDocument> result = new LinkedHashMap<>();
        documents.forEach(document -> result.put(document.getId(), document));
        return result;
    }
}
