package com.anying.helpdesk.knowledge.answer.evaluation;

import com.anying.helpdesk.knowledge.answer.*;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.*;
import com.anying.helpdesk.knowledge.service.*;
import com.anying.helpdesk.modelconfig.factory.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 使用真实 HTTP 检索和生产补查、生成、核验代码，只将默认库绑定和元数据固定到评测库。 */
public class BridgeQaEvaluationCli {
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** 每题两组交替先后顺序并共享初检结果，所有模型及额外检索均真实调用。 */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        JsonNode input = JSON.readTree(output.resolve("input.json").toFile());
        String token = JSON.readTree(Path.of(args[1]).toFile()).path("admin").asText();
        long baseId = input.path("baseId").asLong();
        var documents = new HashMap<Long, KnowledgeDocument>();
        for (var node : input.path("documents")) {
            var document = new KnowledgeDocument();
            document.setId(node.path("id").asLong());
            document.setSourceKey(node.path("sourceKey").asText());
            document.setBusinessVersion(node.path("businessVersion").asText());
            document.setApplicabilityScope("ALL");
            document.setValidFrom(LocalDateTime.parse(node.path("validFrom").asText()));
            documents.put(document.getId(), document);
        }
        Path calls = output.resolve("calls.jsonl");
        boolean probe = args.length == 3 && "probe".equals(args[2]);
        if (!probe && Files.exists(calls)) throw new IllegalStateException("run already exists");
        var app = new SpringApplication(KnowledgeReviewEvaluationCli.EvaluationConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.read-only=true", "--app.chat.log-model-protocol=false", "--logging.level.root=ERROR")) {
            var provider = context.getBean(DefaultChatModelProvider.class);
            var model = provider.get();
            var client = context.getBean(KnowledgeAnswerModelClient.class);
            checkModel(model);
            if (probe) return;
            Files.writeString(calls, "", StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("metadata.json"), JSON.writeValueAsString(Map.of(
                    "modelConfigId", model.configId(), "modelName", model.modelName(),
                    "mode", "LIVE_RETRIEVAL_AND_PRODUCTION_ANSWER_SERVICE", "sharedFirstSearch", true,
                    "taskPlanningEnabled", false, "schedulingBudgetSeconds", 90, "workers", 2)), StandardOpenOption.CREATE_NEW);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<Future<?>>();
                for (var item : input.path("cases")) futures.add(executor.submit(() -> {
                    try {
                        String question = item.path("question").asText();
                        KnowledgeSearchResponse initial = null;
                        try { initial = search(baseId, question, token); }
                        catch (Exception ignored) { /* 初检失败让两个组同样失败，不选择性重试。 */ }
                        var first = initial;
                        var arms = new ArrayList<>(List.of("SINGLE", "MULTIHOP"));
                        if (Integer.parseInt(item.path("id").asText().substring(1)) % 2 == 0) Collections.reverse(arms);
                        for (String arm : arms) {
                            if (!provider.get().equals(model)) throw new IllegalStateException("default model changed");
                            var row = execute(item.path("id").asText(), question, arm, baseId, documents, first, token, model, client);
                            synchronized (BridgeQaEvaluationCli.class) {
                                Files.writeString(calls, JSON.writeValueAsString(row) + "\n", StandardOpenOption.APPEND);
                                System.out.println("BRIDGE " + item.path("id").asText() + " " + arm + " " + row.get("error"));
                            }
                        }
                    } catch (Exception failure) { throw new CompletionException(failure); }
                }));
                for (var future : futures) future.get();
            }
            if (!provider.get().equals(model)) throw new IllegalStateException("default model changed");
            Files.writeString(output.resolve("finished.json"), "{\"complete\":true}", StandardOpenOption.CREATE_NEW);
        }
    }

    /** 正式实验前做一次最小可用性检查，额度不足时停止，不批量生成没有比较意义的失败。 */
    private static void checkModel(ResolvedChatModel model) {
        try {
            model.chatModel().call("Reply OK.");
            System.out.println("BRIDGE PREFLIGHT OK");
        } catch (RuntimeException failure) {
            String message = String.valueOf(failure.getMessage());
            String code = message.contains("Insufficient Balance") ? "MODEL_BALANCE_INSUFFICIENT" : "MODEL_PREFLIGHT_FAILED";
            // 厂商异常可能包含请求信息，终端只输出固定分类，不串接原始异常。
            throw new IllegalStateException(code);
        }
    }

    /** 将单题固定到独立知识库，唯一策略差异是生产证据收集器的补查开关。 */
    private static Map<String, Object> execute(String id, String question, String arm, long baseId,
            Map<Long, KnowledgeDocument> documents, KnowledgeSearchResponse initial, String token,
            ResolvedChatModel model, KnowledgeAnswerModelClient client) {
        var properties = new KnowledgeMultihopProperties();
        properties.setEnabled("MULTIHOP".equals(arm));
        var retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.search(any(), any())).thenAnswer(invocation -> {
            var request = (KnowledgeSearchRequest) invocation.getArgument(1);
            if (question.equals(request.query())) {
                if (initial == null) throw new IllegalStateException("initial retrieval failed");
                return initial;
            }
            return search(baseId, request.query(), token);
        });
        var bases = mock(KnowledgeBaseService.class);
        when(bases.findDefault()).thenReturn(Optional.of(new KnowledgeBaseResponse(baseId, "企业关联问答评测", null,
                25, java.math.BigDecimal.ZERO, 5, false, documents.size(), 0, null, null)));
        var applicability = mock(KnowledgeApplicabilityService.class);
        when(applicability.get(baseId)).thenReturn(new KnowledgeApplicabilityPolicy(0L, List.of()));
        var eligibility = mock(KnowledgeDocumentEligibilityService.class);
        when(eligibility.findEligible(any(), any(), any())).thenReturn(documents);
        var clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        var service = new TrustedKnowledgeAnswerService(bases, new KnowledgeEvidenceCollector(retrieval, client, properties),
                eligibility, client, new KnowledgeAnswerGuard(), clock, applicability);
        var trace = new KnowledgeAnswerTrace(UUID.randomUUID().toString(), null, null, question, "ALL", model, clock, ignored -> { });
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("arm", arm);
        long start = System.nanoTime();
        try {
            var answer = service.answer(model, question, "ALL", trace);
            trace.succeeded();
            row.put("answer", answer.knowledgeAnswer().answer());
            row.put("totalTokens", answer.totalTokens());
        } catch (RuntimeException failure) {
            trace.failed(failure);
            row.put("error", failure instanceof KnowledgeAnswerFailure known ? known.errorCode() : "EVALUATION_RUNTIME_FAILURE");
        }
        row.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        row.put("diagnostic", trace.snapshot());
        return row;
    }

    /** 通过管理员检索接口调用真实向量化、ES双路召回和资格过滤，不注入标准答案查询。 */
    private static KnowledgeSearchResponse search(long baseId, String question, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:8080/api/admin/knowledge-bases/" + baseId + "/search"))
                .timeout(Duration.ofSeconds(180)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("query", question, "applicabilityScope", "ALL")))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("retrieval HTTP " + response.statusCode());
        return JSON.treeToValue(JSON.readTree(response.body()).path("data"), KnowledgeSearchResponse.class);
    }
}
