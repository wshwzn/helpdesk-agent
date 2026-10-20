package com.anying.helpdesk.knowledge.answer.evaluation;

import com.anying.helpdesk.knowledge.answer.*;
import com.anying.helpdesk.modelconfig.factory.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 显式执行规划协议回放；复用只读模型配置，不启动业务调度或检索服务。 */
public class KnowledgePlanningEvaluationCli {
    public record Dataset(int schemaVersion, String labelSource, String description,
                          List<String> adoptionCriteria, List<Case> cases) { }
    public record Case(String id, String category, String question, String scope,
                       List<KnowledgeAnswerReport.Evidence> evidence, List<String> searchedQueries,
                       List<KnowledgeRetrievalPlan.Decision> expectedDecisions) { }
    public record Row(String id, int repeat, long durationMs, KnowledgeRetrievalPlan plan,
                      String errorCode, KnowledgeAnswerModelClient.TokenUsage usage, boolean scenarioChecksPassed) { }

    /** 独立进程仅读取模型配置并调用规划器，所有失败逐条保留且不自行重试。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("expected dataset, output, repeats");
        Path datasetPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int repeats = Integer.parseInt(args[2]);
        if (repeats < 1 || repeats > 2) throw new IllegalArgumentException("repeats must be 1..2");
        var json = JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var dataset = json.readValue(Files.readString(datasetPath), Dataset.class);
        validate(dataset);
        Files.writeString(output.resolve("calls.jsonl"), "", StandardOpenOption.CREATE_NEW);
        var app = new SpringApplication(KnowledgeReviewEvaluationCli.EvaluationConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.read-only=true", "--app.chat.log-model-protocol=false", "--logging.level.root=ERROR")) {
            var provider = context.getBean(DefaultChatModelProvider.class);
            var model = provider.get();
            var client = context.getBean(KnowledgeAnswerModelClient.class);
            Files.writeString(output.resolve("metadata.json"), json.writeValueAsString(Map.of(
                    "startedAt", Instant.now().toString(), "modelConfigId", model.configId(), "modelName", model.modelName(),
                    "datasetSha256", sha256(Files.readAllBytes(datasetPath)), "repeats", repeats,
                    "planned", dataset.cases().size() * repeats)), StandardOpenOption.CREATE_NEW);
            for (int repeat = 1; repeat <= repeats; repeat++) {
                var cases = new ArrayList<>(dataset.cases());
                Collections.shuffle(cases, new Random(20261021L + repeat));
                for (var item : cases) {
                    if (!provider.get().equals(model)) throw new IllegalStateException("default model changed during evaluation");
                    var row = execute(client, model, item, repeat);
                    Files.writeString(output.resolve("calls.jsonl"), json.writeValueAsString(row) + "\n", StandardOpenOption.APPEND);
                    System.out.println("PLANNING " + repeat + " " + item.id() + " "
                            + (row.errorCode() == null ? row.plan().decision() : row.errorCode()));
                }
            }
            if (!provider.get().equals(model)) throw new IllegalStateException("default model changed during evaluation");
            Files.writeString(output.resolve("finished.json"), json.writeValueAsString(Map.of("finishedAt", Instant.now().toString())),
                    StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            System.err.println("Planning evaluation stopped: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    /** 在计费调用前核对身份、必填输入及证据编号，标签仅用于本地结果检查。 */
    public static void validate(Dataset dataset) {
        if (dataset.schemaVersion() != 1 || !"DEVELOPER_ASSISTANT_PROVISIONAL".equals(dataset.labelSource())
                || dataset.cases() == null || dataset.cases().isEmpty()) throw new IllegalArgumentException("invalid planning dataset");
        var ids = new HashSet<String>();
        for (var item : dataset.cases()) {
            if (item.id() == null || !ids.add(item.id()) || item.question() == null || item.question().isBlank()
                    || item.scope() == null || item.scope().isBlank() || item.evidence() == null || item.evidence().isEmpty()
                    || item.searchedQueries() == null || item.searchedQueries().isEmpty()
                    || item.searchedQueries().stream().anyMatch(q -> q == null || q.isBlank())
                    || item.expectedDecisions() == null || item.expectedDecisions().isEmpty()
                    || item.expectedDecisions().stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("invalid planning case");
            var evidenceIds = new HashSet<String>();
            for (var source : item.evidence()) {
                if (source == null || source.id() == null || !evidenceIds.add(source.id())
                        || source.content() == null || source.content().isBlank())
                    throw new IllegalArgumentException("invalid planning evidence");
            }
        }
    }

    /** 每条输入只调用规划方法，解析失败保留固定分类和已知用量，标签不传给模型。 */
    public static Row execute(KnowledgeAnswerModelClient client, ResolvedChatModel model, Case item, int repeat) {
        long start = System.nanoTime();
        try {
            var result = client.plan(model, item.question(), item.scope(), item.evidence(), item.searchedQueries());
            var plan = result.value();
            boolean checked = item.expectedDecisions().contains(plan.decision())
                    && (item.expectedDecisions().contains(KnowledgeRetrievalPlan.Decision.NEEDS_USER_INPUT) || plan.userConditions().isEmpty());
            return new Row(item.id(), repeat, (System.nanoTime() - start) / 1_000_000, plan, null,
                    new KnowledgeAnswerModelClient.TokenUsage(result.promptTokens(), result.completionTokens(), result.totalTokens()), checked);
        } catch (RuntimeException failure) {
            return new Row(item.id(), repeat, (System.nanoTime() - start) / 1_000_000, null,
                    failure instanceof KnowledgeAnswerFailure known ? known.errorCode() : "KNOWLEDGE_PLANNING_CALL_FAILED",
                    failure instanceof KnowledgeAnswerFailure known ? known.usage() : null, false);
        }
    }

    /** 输入摘要用于核对运行材料，不导出配置凭据。 */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
