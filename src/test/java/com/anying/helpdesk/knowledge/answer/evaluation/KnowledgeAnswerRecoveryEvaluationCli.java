package com.anying.helpdesk.knowledge.answer.evaluation;

import com.anying.helpdesk.knowledge.answer.*;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 显式联网的冻结证据回放；模型真实调用，检索和资格检查固定，不冒充在线端到端验证。 */
public class KnowledgeAnswerRecoveryEvaluationCli {
    public record Dataset(List<Case> cases, List<String> adoptionCriteria) { }
    public record Case(String id, String sourceRunId, String question, String scope,
                       List<KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerDraft fixedDraft,
                       Boolean expectedApproved, int repeats) { }

    /** 从既有现场回放生成到复核完整链路，并用固定错误草案检查新策略是否放过无据结论。 */
    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        var dataset = json.readValue(input.toFile(), Dataset.class);
        Files.createDirectories(output);
        Path calls = output.resolve("calls.jsonl");
        Files.writeString(calls, "", StandardOpenOption.CREATE_NEW);
        var app = new SpringApplication(KnowledgeReviewEvaluationCli.EvaluationConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.read-only=true", "--app.chat.log-model-protocol=false", "--logging.level.root=ERROR")) {
            var provider = context.getBean(DefaultChatModelProvider.class);
            var model = provider.get();
            var client = context.getBean(KnowledgeAnswerModelClient.class);
            Files.writeString(output.resolve("metadata.json"), json.writeValueAsString(Map.of(
                    "modelConfigId", model.configId(), "modelName", model.modelName(),
                    "datasetSha256", digest(Files.readAllBytes(input)),
                    "baselinePromptSha256", digest(client.reviewPrompt(KnowledgeAnswerModelClient.ReviewStrategy.BASELINE).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "candidatePromptSha256", digest(client.reviewPrompt(KnowledgeAnswerModelClient.ReviewStrategy.GROUNDED).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "mode", "FROZEN_EVIDENCE_WITH_REAL_MODELS")), StandardOpenOption.CREATE_NEW);
            for (var item : dataset.cases()) {
                if (item.fixedDraft() != null) new KnowledgeAnswerGuard().validate(item.fixedDraft(), item.evidence());
                for (int repeat = 1; repeat <= item.repeats(); repeat++) {
                    var strategies = new ArrayList<>(List.of(KnowledgeAnswerModelClient.ReviewStrategy.BASELINE,
                            KnowledgeAnswerModelClient.ReviewStrategy.GROUNDED));
                    if (repeat % 2 == 0) Collections.reverse(strategies);
                    for (var strategy : strategies) {
                        if (!provider.get().equals(model)) throw new IllegalStateException("default model changed");
                        ReflectionTestUtils.setField(client, "reviewStrategy", strategy);
                        var row = execute(item, model, client);
                        row.put("strategy", strategy);
                        row.put("repeat", repeat);
                        Files.writeString(calls, json.writeValueAsString(row) + "\n", StandardOpenOption.APPEND);
                        System.out.println("RECOVERY " + item.id() + " " + repeat + " " + strategy + " " + row.get("outcome"));
                    }
                }
            }
            Files.writeString(output.resolve("finished.json"), "{\"complete\":true}", StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            System.err.println("Recovery evaluation stopped: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    /** 每条用例只执行预定次数，保留失败和所有尝试，不把技术异常当成正确拦截。 */
    private static Map<String, Object> execute(Case item, ResolvedChatModel model, KnowledgeAnswerModelClient client) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", item.id());
        row.put("sourceRunId", item.sourceRunId());
        row.put("expectedApproved", item.expectedApproved());
        var clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        var trace = new KnowledgeAnswerTrace(UUID.randomUUID().toString(), null, null, item.question(), item.scope(), model, clock, ignored -> { });
        long start = System.nanoTime();
        try {
            if (item.fixedDraft() != null) {
                var result = client.review(model, item.question(), item.scope(), item.evidence(), item.fixedDraft());
                row.put("review", result.value());
                row.put("usage", new KnowledgeAnswerModelClient.TokenUsage(result.promptTokens(), result.completionTokens(), result.totalTokens()));
                row.put("outcome", Objects.equals(result.value().approved(), item.expectedApproved()) ? "CHECK_PASSED" : "CHECK_FAILED");
            } else {
                var result = service(item, client, clock).answer(model, item.question(), item.scope(), trace);
                trace.succeeded();
                row.put("answer", result);
                row.put("outcome", "DELIVERED_REQUIRES_MANUAL_CHECK");
            }
        } catch (RuntimeException failure) {
            trace.failed(failure);
            row.put("outcome", "FAILED");
            row.put("errorCode", failure instanceof KnowledgeAnswerFailure known ? known.errorCode() : "EVALUATION_CALL_FAILED");
            if (failure instanceof KnowledgeAnswerFailure known) row.put("usage", known.usage());
        }
        if (item.fixedDraft() == null) row.put("diagnostic", trace.snapshot());
        row.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        return row;
    }

    /** 注入完整冻结证据及匹配元数据，复用生产编排与校验，不改线上资料或默认知识库。 */
    private static TrustedKnowledgeAnswerService service(Case item, KnowledgeAnswerModelClient client, Clock clock) {
        var bases = mock(KnowledgeBaseService.class);
        var collector = mock(KnowledgeEvidenceCollector.class);
        var eligibility = mock(KnowledgeDocumentEligibilityService.class);
        var applicability = mock(KnowledgeApplicabilityService.class);
        when(bases.findDefault()).thenReturn(Optional.of(new KnowledgeBaseResponse(3L, "冻结现场", null,
                100, new BigDecimal("0.3"), 50, true, 0, 0, null, null)));
        when(applicability.get(3L)).thenReturn(new KnowledgeApplicabilityPolicy(0L, List.of()));
        when(collector.collect(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            KnowledgeAnswerTrace trace = invocation.getArgument(4);
            trace.evidence(item.evidence());
            return new KnowledgeEvidenceCollector.Collected(item.evidence(), new KnowledgeAnswerModelClient.TokenUsage(0, 0, 0), null);
        });
        var documents = new HashMap<Long, KnowledgeDocument>();
        for (var evidence : item.evidence()) {
            var metadata = evidence.metadata();
            var document = new KnowledgeDocument();
            document.setId(evidence.documentId());
            document.setSourceKey(metadata.sourceKey());
            document.setBusinessVersion(metadata.businessVersion());
            document.setApplicabilityScope(metadata.applicabilityScope());
            document.setValidFrom(metadata.validFrom());
            document.setValidUntil(metadata.validUntil());
            documents.put(document.getId(), document);
        }
        when(eligibility.findEligible(any(), any(), any())).thenReturn(documents);
        return new TrustedKnowledgeAnswerService(bases, collector, eligibility, client,
                new KnowledgeAnswerGuard(), clock, applicability);
    }

    /** 固定输入和规则摘要用于确认真实调用未更换资料或提示。 */
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
