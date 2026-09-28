package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeBase;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeRetrievalSettingsRequest;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeRetrievalSettingsException;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseNotFoundException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeBaseMapper;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 创建知识库，并将名称首尾空白清理后保存。
     */
    @Override
    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName(request.name().trim());
        knowledgeBase.setDescription(normalizeOptionalText(request.description()));
        knowledgeBase.setCreatedBy(adminId);
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        try {
            knowledgeBaseMapper.insert(knowledgeBase);
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeBaseConflictException("knowledge base name already exists");
        }
        return KnowledgeBaseResponse.from(knowledgeBase, 0);
    }

    /**
     * 按最近更新时间查询知识库，并统计每个知识库的文档数。
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .orderByDesc(KnowledgeBase::getUpdatedAt))
                .stream()
                .map(knowledgeBase -> KnowledgeBaseResponse.from(
                        knowledgeBase,
                        countDocuments(knowledgeBase.getId())
                ))
                .toList();
    }

    /**
     * 查询指定知识库及其当前文档数。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getById(Long id) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        return KnowledgeBaseResponse.from(knowledgeBase, countDocuments(id));
    }

    /**
     * 读取查询热路径所需的检索参数，避免额外统计知识库文档数量。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeRetrievalSettingsResponse getRetrievalSettings(Long id) {
        return KnowledgeRetrievalSettingsResponse.from(requireKnowledgeBase(id));
    }

    /**
     * 校验三个检索参数的关系，并通过乐观锁保存知识库级配置。
     */
    @Override
    @Transactional
    public KnowledgeRetrievalSettingsResponse updateRetrievalSettings(
            Long id,
            UpdateKnowledgeRetrievalSettingsRequest request
    ) {
        if (request.resultLimit() > request.candidateLimit()) {
            throw new InvalidKnowledgeRetrievalSettingsException(
                    "resultLimit must not exceed candidateLimit"
            );
        }
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        knowledgeBase.setRetrievalCandidateLimit(request.candidateLimit());
        knowledgeBase.setVectorMinSimilarity(request.vectorMinSimilarity());
        knowledgeBase.setRetrievalResultLimit(request.resultLimit());
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        if (knowledgeBaseMapper.updateById(knowledgeBase) == 0) {
            throw new KnowledgeBaseConflictException("knowledge base was updated concurrently");
        }
        return KnowledgeRetrievalSettingsResponse.from(knowledgeBase);
    }

    /**
     * 仅允许删除不含文档的知识库，避免产生孤立对象。
     */
    @Override
    @Transactional
    public void delete(Long id) {
        requireKnowledgeBase(id);
        if (countDocuments(id) > 0) {
            throw new KnowledgeBaseConflictException("knowledge base must be empty before deletion");
        }
        knowledgeBaseMapper.deleteById(id);
    }

    /**
     * 返回指定知识库实体，不存在时抛出统一异常。
     */
    private KnowledgeBase requireKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null) {
            throw new KnowledgeBaseNotFoundException(id);
        }
        return knowledgeBase;
    }

    /**
     * 统计指定知识库下的全部文档元数据记录。
     */
    private long countDocuments(Long knowledgeBaseId) {
        return knowledgeDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId));
    }

    /**
     * 将可选说明的空白值转换为空值，避免保存无意义字符串。
     */
    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
