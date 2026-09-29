package com.anying.helpdesk.knowledge.evaluation.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationCase;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationCaseDocument;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationRun;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationRunResult;
import com.anying.helpdesk.knowledge.evaluation.dto.CreateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationCaseResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagExpectedDocumentResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.UpdateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.exception.RagEvaluationConflictException;
import com.anying.helpdesk.knowledge.evaluation.exception.RagEvaluationNotFoundException;
import com.anying.helpdesk.knowledge.evaluation.mapper.RagEvaluationCaseDocumentMapper;
import com.anying.helpdesk.knowledge.evaluation.mapper.RagEvaluationCaseMapper;
import com.anying.helpdesk.knowledge.evaluation.mapper.RagEvaluationRunMapper;
import com.anying.helpdesk.knowledge.evaluation.mapper.RagEvaluationRunResultMapper;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagEvaluationPersistenceService {

    private final RagEvaluationCaseMapper caseMapper;
    private final RagEvaluationCaseDocumentMapper caseDocumentMapper;
    private final RagEvaluationRunMapper runMapper;
    private final RagEvaluationRunResultMapper runResultMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建包含一个或多个期望文档的评测用例。
     */
    @Transactional
    public RagEvaluationCaseResponse createCase(
            Long knowledgeBaseId,
            CreateRagEvaluationCaseRequest request,
            Long adminId
    ) {
        knowledgeBaseService.getById(knowledgeBaseId);
        List<KnowledgeDocument> expectedDocuments = requireReadyDocuments(
                knowledgeBaseId,
                request.expectedDocumentIds()
        );
        LocalDateTime now = LocalDateTime.now();
        RagEvaluationCase evaluationCase = new RagEvaluationCase();
        evaluationCase.setKnowledgeBaseId(knowledgeBaseId);
        evaluationCase.setQuestion(request.question().trim());
        evaluationCase.setCreatedBy(adminId);
        evaluationCase.setCreatedAt(now);
        evaluationCase.setUpdatedAt(now);
        caseMapper.insert(evaluationCase);
        replaceExpectedDocuments(evaluationCase.getId(), expectedDocuments, now);
        return toCaseResponse(requireCase(knowledgeBaseId, evaluationCase.getId()), expectedDocuments);
    }

    /**
     * 修改评测问题及期望文档，并使用版本号避免覆盖并发编辑。
     */
    @Transactional
    public RagEvaluationCaseResponse updateCase(
            Long knowledgeBaseId,
            Long caseId,
            UpdateRagEvaluationCaseRequest request
    ) {
        RagEvaluationCase evaluationCase = requireCase(knowledgeBaseId, caseId);
        if (!request.version().equals(evaluationCase.getVersion())) {
            throw new RagEvaluationConflictException("evaluation case was updated concurrently");
        }
        List<KnowledgeDocument> expectedDocuments = requireReadyDocuments(
                knowledgeBaseId,
                request.expectedDocumentIds()
        );
        LocalDateTime now = LocalDateTime.now();
        evaluationCase.setQuestion(request.question().trim());
        evaluationCase.setUpdatedAt(now);
        if (caseMapper.updateById(evaluationCase) == 0) {
            throw new RagEvaluationConflictException("evaluation case was updated concurrently");
        }
        replaceExpectedDocuments(caseId, expectedDocuments, now);
        return toCaseResponse(evaluationCase, expectedDocuments);
    }

    /**
     * 删除评测用例及当前期望文档关系，历史运行快照继续保留。
     */
    @Transactional
    public void deleteCase(Long knowledgeBaseId, Long caseId) {
        requireCase(knowledgeBaseId, caseId);
        caseDocumentMapper.delete(new LambdaQueryWrapper<RagEvaluationCaseDocument>()
                .eq(RagEvaluationCaseDocument::getCaseId, caseId));
        caseMapper.deleteById(caseId);
    }

    /**
     * 查询知识库的全部评测用例，并批量组装期望文档。
     */
    @Transactional(readOnly = true)
    public List<RagEvaluationCaseResponse> listCases(Long knowledgeBaseId) {
        knowledgeBaseService.getById(knowledgeBaseId);
        List<RagEvaluationCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<RagEvaluationCase>()
                        .eq(RagEvaluationCase::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByAsc(RagEvaluationCase::getId)
        );
        if (cases.isEmpty()) {
            return List.of();
        }
        Map<Long, List<KnowledgeDocument>> documentsByCase = loadDocumentsByCase(
                cases.stream().map(RagEvaluationCase::getId).toList()
        );
        return cases.stream()
                .map(item -> toCaseResponse(
                        item,
                        documentsByCase.getOrDefault(item.getId(), List.of())
                ))
                .toList();
    }

    /**
     * 判断知识文档是否仍被当前评测用例引用。
     */
    @Transactional(readOnly = true)
    public boolean isDocumentReferenced(Long documentId) {
        return caseDocumentMapper.selectCount(new LambdaQueryWrapper<RagEvaluationCaseDocument>()
                .eq(RagEvaluationCaseDocument::getDocumentId, documentId)) > 0;
    }

    /**
     * 在短事务内保存一次运行汇总及全部逐题结果。
     */
    @Transactional
    public RagEvaluationRun saveRun(
            RagEvaluationRun run,
            List<RagEvaluationRunResult> results
    ) {
        runMapper.insert(run);
        for (RagEvaluationRunResult result : results) {
            result.setRunId(run.getId());
            runResultMapper.insert(result);
        }
        return run;
    }

    /**
     * 按时间倒序查询指定数量的历史运行汇总。
     */
    @Transactional(readOnly = true)
    public List<RagEvaluationRun> listRuns(Long knowledgeBaseId, int limit) {
        knowledgeBaseService.getById(knowledgeBaseId);
        return runMapper.selectPage(
                new Page<>(1, limit, false),
                new LambdaQueryWrapper<RagEvaluationRun>()
                        .eq(RagEvaluationRun::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(RagEvaluationRun::getCreatedAt)
                        .orderByDesc(RagEvaluationRun::getId)
        ).getRecords();
    }

    /**
     * 查询属于指定知识库的一次评测运行。
     */
    @Transactional(readOnly = true)
    public RagEvaluationRun getRun(Long knowledgeBaseId, Long runId) {
        RagEvaluationRun run = runMapper.selectOne(new LambdaQueryWrapper<RagEvaluationRun>()
                .eq(RagEvaluationRun::getId, runId)
                .eq(RagEvaluationRun::getKnowledgeBaseId, knowledgeBaseId));
        if (run == null) {
            throw new RagEvaluationNotFoundException("evaluation run", runId);
        }
        return run;
    }

    /**
     * 在同一事务内先删除逐题快照，再删除所属知识库的运行汇总。
     */
    @Transactional
    public void deleteRun(Long knowledgeBaseId, Long runId) {
        getRun(knowledgeBaseId, runId);
        runResultMapper.delete(new LambdaQueryWrapper<RagEvaluationRunResult>()
                .eq(RagEvaluationRunResult::getRunId, runId));
        runMapper.deleteById(runId);
    }

    /**
     * 按原始用例顺序读取一次运行的逐题结果。
     */
    @Transactional(readOnly = true)
    public List<RagEvaluationRunResult> listRunResults(Long runId) {
        return runResultMapper.selectList(new LambdaQueryWrapper<RagEvaluationRunResult>()
                .eq(RagEvaluationRunResult::getRunId, runId)
                .orderByAsc(RagEvaluationRunResult::getId));
    }

    /**
     * 校验全部期望文档属于当前知识库且已经完成索引。
     */
    private List<KnowledgeDocument> requireReadyDocuments(
            Long knowledgeBaseId,
            Collection<Long> documentIds
    ) {
        List<Long> normalizedIds = documentIds.stream().distinct().toList();
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .in(KnowledgeDocument::getId, normalizedIds)
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeDocument::getStatus, KnowledgeDocumentStatus.READY)
        );
        Map<Long, KnowledgeDocument> byId = new LinkedHashMap<>();
        documents.forEach(document -> byId.put(document.getId(), document));
        if (byId.size() != normalizedIds.size()) {
            throw new RagEvaluationConflictException(
                    "expected documents must belong to the knowledge base and be READY"
            );
        }
        return normalizedIds.stream().map(byId::get).toList();
    }

    /**
     * 使用新列表整体替换用例的期望文档关系。
     */
    private void replaceExpectedDocuments(
            Long caseId,
            List<KnowledgeDocument> documents,
            LocalDateTime now
    ) {
        caseDocumentMapper.delete(new LambdaQueryWrapper<RagEvaluationCaseDocument>()
                .eq(RagEvaluationCaseDocument::getCaseId, caseId));
        for (KnowledgeDocument document : documents) {
            RagEvaluationCaseDocument relation = new RagEvaluationCaseDocument();
            relation.setCaseId(caseId);
            relation.setDocumentId(document.getId());
            relation.setCreatedAt(now);
            caseDocumentMapper.insert(relation);
        }
    }

    /**
     * 批量读取用例关系和文档，避免逐用例访问数据库。
     */
    private Map<Long, List<KnowledgeDocument>> loadDocumentsByCase(List<Long> caseIds) {
        List<RagEvaluationCaseDocument> relations = caseDocumentMapper.selectList(
                new LambdaQueryWrapper<RagEvaluationCaseDocument>()
                        .in(RagEvaluationCaseDocument::getCaseId, caseIds)
                        .orderByAsc(RagEvaluationCaseDocument::getCaseId)
                        .orderByAsc(RagEvaluationCaseDocument::getDocumentId)
        );
        if (relations.isEmpty()) {
            return Map.of();
        }
        Map<Long, KnowledgeDocument> documentsById = new LinkedHashMap<>();
        knowledgeDocumentMapper.selectBatchIds(
                relations.stream().map(RagEvaluationCaseDocument::getDocumentId).distinct().toList()
        ).forEach(document -> documentsById.put(document.getId(), document));
        Map<Long, List<KnowledgeDocument>> documentsByCase = new LinkedHashMap<>();
        for (RagEvaluationCaseDocument relation : relations) {
            KnowledgeDocument document = documentsById.get(relation.getDocumentId());
            if (document != null) {
                documentsByCase.computeIfAbsent(relation.getCaseId(), ignored -> new ArrayList<>())
                        .add(document);
            }
        }
        return documentsByCase;
    }

    /**
     * 查询指定知识库中的评测用例，不存在时返回统一未找到异常。
     */
    private RagEvaluationCase requireCase(Long knowledgeBaseId, Long caseId) {
        RagEvaluationCase evaluationCase = caseMapper.selectOne(
                new LambdaQueryWrapper<RagEvaluationCase>()
                        .eq(RagEvaluationCase::getId, caseId)
                        .eq(RagEvaluationCase::getKnowledgeBaseId, knowledgeBaseId)
        );
        if (evaluationCase == null) {
            throw new RagEvaluationNotFoundException("evaluation case", caseId);
        }
        return evaluationCase;
    }

    /**
     * 将用例实体与期望文档转换为稳定的接口响应。
     */
    private RagEvaluationCaseResponse toCaseResponse(
            RagEvaluationCase evaluationCase,
            List<KnowledgeDocument> expectedDocuments
    ) {
        return new RagEvaluationCaseResponse(
                evaluationCase.getId(),
                evaluationCase.getKnowledgeBaseId(),
                evaluationCase.getQuestion(),
                expectedDocuments.stream()
                        .map(document -> new RagExpectedDocumentResponse(
                                document.getId(),
                                document.getOriginalName()
                        ))
                        .toList(),
                evaluationCase.getVersion(),
                evaluationCase.getCreatedAt(),
                evaluationCase.getUpdatedAt()
        );
    }
}
