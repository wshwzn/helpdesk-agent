package com.anying.helpdesk.knowledge.evaluation.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationRun;
import com.anying.helpdesk.knowledge.evaluation.domain.RagEvaluationRunResult;
import com.anying.helpdesk.knowledge.evaluation.dto.CreateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationCaseResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationCaseResultResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationRunResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationRunSummaryResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagExpectedDocumentResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.UpdateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.exception.RagEvaluationConflictException;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private static final int MAX_CASES_PER_RUN = 100;
    private static final int METRIC_SCALE = 6;
    private static final TypeReference<List<RagExpectedDocumentResponse>> EXPECTED_DOCUMENTS_TYPE =
            new TypeReference<>() { };
    private static final TypeReference<List<KnowledgeSearchResultResponse>> SEARCH_RESULTS_TYPE =
            new TypeReference<>() { };

    private final RagEvaluationPersistenceService persistenceService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ObjectMapper objectMapper;

    /**
     * 创建一条支持多个期望文档的检索评测用例。
     */
    public RagEvaluationCaseResponse createCase(
            Long knowledgeBaseId,
            CreateRagEvaluationCaseRequest request,
            Long adminId
    ) {
        return persistenceService.createCase(knowledgeBaseId, request, adminId);
    }

    /**
     * 修改评测问题和期望文档列表。
     */
    public RagEvaluationCaseResponse updateCase(
            Long knowledgeBaseId,
            Long caseId,
            UpdateRagEvaluationCaseRequest request
    ) {
        return persistenceService.updateCase(knowledgeBaseId, caseId, request);
    }

    /**
     * 删除当前评测用例但保留历史运行快照。
     */
    public void deleteCase(Long knowledgeBaseId, Long caseId) {
        persistenceService.deleteCase(knowledgeBaseId, caseId);
    }

    /**
     * 查询知识库当前维护的评测用例。
     */
    public List<RagEvaluationCaseResponse> listCases(Long knowledgeBaseId) {
        return persistenceService.listCases(knowledgeBaseId);
    }

    /**
     * 执行全部评测用例，按固定参数快照计算 Hit@K、Recall@K、MRR 和耗时。
     */
    public RagEvaluationRunResponse run(Long knowledgeBaseId, Long adminId) {
        List<RagEvaluationCaseResponse> cases = persistenceService.listCases(knowledgeBaseId);
        if (cases.isEmpty()) {
            throw new RagEvaluationConflictException("at least one evaluation case is required");
        }
        if (cases.size() > MAX_CASES_PER_RUN) {
            throw new RagEvaluationConflictException("a single evaluation run supports at most 100 cases");
        }
        KnowledgeRetrievalSettingsResponse settings = knowledgeBaseService.getRetrievalSettings(knowledgeBaseId);
        List<RagEvaluationRunResult> resultEntities = new ArrayList<>(cases.size());
        int hitCases = 0;
        double recallSum = 0;
        double reciprocalRankSum = 0;
        long latencySum = 0;

        for (RagEvaluationCaseResponse evaluationCase : cases) {
            CaseMeasurement measurement = evaluateCase(knowledgeBaseId, settings, evaluationCase);
            resultEntities.add(measurement.entity());
            if (measurement.hit()) {
                hitCases++;
            }
            recallSum += measurement.recallAtK();
            reciprocalRankSum += measurement.reciprocalRank();
            latencySum += measurement.latencyMs();
        }

        RagEvaluationRun run = new RagEvaluationRun();
        run.setKnowledgeBaseId(knowledgeBaseId);
        run.setCandidateLimit(settings.candidateLimit());
        run.setVectorMinSimilarity(settings.vectorMinSimilarity());
        run.setResultLimit(settings.resultLimit());
        run.setTotalCases(cases.size());
        run.setHitCases(hitCases);
        run.setHitRate(metric((double) hitCases / cases.size()));
        run.setRecallAtK(metric(recallSum / cases.size()));
        run.setMeanReciprocalRank(metric(reciprocalRankSum / cases.size()));
        run.setAverageLatencyMs(Math.round((double) latencySum / cases.size()));
        run.setCreatedBy(adminId);
        run.setCreatedAt(LocalDateTime.now());
        persistenceService.saveRun(run, resultEntities);
        return getRun(knowledgeBaseId, run.getId());
    }

    /**
     * 查询最近的评测运行汇总，供管理员比较不同参数效果。
     */
    public List<RagEvaluationRunSummaryResponse> listRuns(Long knowledgeBaseId, int limit) {
        return persistenceService.listRuns(knowledgeBaseId, limit).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查询一次历史运行的汇总指标与逐题召回快照。
     */
    public RagEvaluationRunResponse getRun(Long knowledgeBaseId, Long runId) {
        RagEvaluationRun run = persistenceService.getRun(knowledgeBaseId, runId);
        List<RagEvaluationCaseResultResponse> results = persistenceService.listRunResults(runId)
                .stream()
                .map(this::toCaseResult)
                .toList();
        return new RagEvaluationRunResponse(toSummary(run), results);
    }

    /**
     * 阻止删除仍被评测用例引用的文档，避免期望集合静默失效。
     */
    public void requireDocumentNotReferenced(Long documentId) {
        if (persistenceService.isDocumentReferenced(documentId)) {
            throw new RagEvaluationConflictException(
                    "document is referenced by RAG evaluation cases; remove those cases first"
            );
        }
    }

    /**
     * 对单条问题执行检索并生成文档级命中指标和结果快照。
     */
    private CaseMeasurement evaluateCase(
            Long knowledgeBaseId,
            KnowledgeRetrievalSettingsResponse settings,
            RagEvaluationCaseResponse evaluationCase
    ) {
        long startedAt = System.nanoTime();
        KnowledgeSearchResponse response = knowledgeRetrievalService.search(
                knowledgeBaseId,
                new KnowledgeSearchRequest(evaluationCase.question()),
                settings
        );
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        Set<Long> expectedIds = evaluationCase.expectedDocuments().stream()
                .map(RagExpectedDocumentResponse::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> matchedIds = response.results().stream()
                .map(KnowledgeSearchResultResponse::documentId)
                .filter(expectedIds::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Integer firstRelevantRank = response.results().stream()
                .filter(result -> expectedIds.contains(result.documentId()))
                .map(KnowledgeSearchResultResponse::rank)
                .findFirst()
                .orElse(null);
        double recallAtK = (double) matchedIds.size() / expectedIds.size();
        double reciprocalRank = firstRelevantRank == null ? 0 : 1.0D / firstRelevantRank;

        RagEvaluationRunResult entity = new RagEvaluationRunResult();
        entity.setCaseId(evaluationCase.id());
        entity.setQuestion(evaluationCase.question());
        entity.setExpectedDocumentsJson(writeJson(evaluationCase.expectedDocuments()));
        entity.setRetrievedResultsJson(writeJson(response.results()));
        entity.setExpectedDocumentCount(expectedIds.size());
        entity.setMatchedDocumentCount(matchedIds.size());
        entity.setFirstRelevantRank(firstRelevantRank);
        entity.setHit(!matchedIds.isEmpty());
        entity.setRecallAtK(metric(recallAtK));
        entity.setReciprocalRank(metric(reciprocalRank));
        entity.setLatencyMs(latencyMs);
        entity.setCreatedAt(LocalDateTime.now());
        return new CaseMeasurement(entity, !matchedIds.isEmpty(), recallAtK, reciprocalRank, latencyMs);
    }

    /**
     * 将运行实体转换为管理端可比较的汇总结构。
     */
    private RagEvaluationRunSummaryResponse toSummary(RagEvaluationRun run) {
        return new RagEvaluationRunSummaryResponse(
                run.getId(),
                run.getKnowledgeBaseId(),
                run.getCandidateLimit(),
                run.getVectorMinSimilarity(),
                run.getResultLimit(),
                run.getTotalCases(),
                run.getHitCases(),
                run.getHitRate(),
                run.getRecallAtK(),
                run.getMeanReciprocalRank(),
                run.getAverageLatencyMs(),
                run.getCreatedAt()
        );
    }

    /**
     * 解析逐题运行快照并转换为详情响应。
     */
    private RagEvaluationCaseResultResponse toCaseResult(RagEvaluationRunResult result) {
        return new RagEvaluationCaseResultResponse(
                result.getCaseId(),
                result.getQuestion(),
                readJson(result.getExpectedDocumentsJson(), EXPECTED_DOCUMENTS_TYPE),
                result.getHit(),
                result.getExpectedDocumentCount(),
                result.getMatchedDocumentCount(),
                result.getFirstRelevantRank(),
                result.getRecallAtK(),
                result.getReciprocalRank(),
                result.getLatencyMs(),
                readJson(result.getRetrievedResultsJson(), SEARCH_RESULTS_TYPE)
        );
    }

    /**
     * 将指标统一保留六位小数，便于数据库存储和前端比较。
     */
    private BigDecimal metric(double value) {
        return BigDecimal.valueOf(value).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 将运行快照序列化为 JSON，序列化失败视为内部状态错误。
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize RAG evaluation snapshot", exception);
        }
    }

    /**
     * 将历史 JSON 快照恢复为强类型列表。
     */
    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse RAG evaluation snapshot", exception);
        }
    }

    private record CaseMeasurement(
            RagEvaluationRunResult entity,
            boolean hit,
            double recallAtK,
            double reciprocalRank,
            long latencyMs
    ) {
    }
}
