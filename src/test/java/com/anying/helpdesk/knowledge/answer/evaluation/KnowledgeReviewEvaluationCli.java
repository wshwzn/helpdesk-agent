package com.anying.helpdesk.knowledge.answer.evaluation;

import com.anying.helpdesk.config.ModelConfigConfiguration;
import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.knowledge.answer.*;
import com.anying.helpdesk.modelconfig.factory.*;
import com.anying.helpdesk.modelconfig.mapper.AiModelConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 显式执行的真实模型评测入口，不由普通 Maven 测试触发，不提供 HTTP 评测接口。 */
public class KnowledgeReviewEvaluationCli {
    public record Dataset(int schemaVersion, String labelSource, String description,
                          List<String> adoptionCriteria, List<Case> cases) { }
    public record Case(String id, String pairId, String split, String category, Boolean expectedApproved,
                       String rationale, String question, String scope,
                       List<KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerDraft draft) { }
    public record Row(String id, String pairId, String split, boolean expectedApproved, int repeat,
                      KnowledgeAnswerModelClient.ReviewStrategy strategy, long durationMs,
                      KnowledgeAnswerModelClient.Review review, String errorCode, KnowledgeAnswerModelClient.TokenUsage usage) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ChatProperties.class)
    @MapperScan(basePackageClasses = AiModelConfigMapper.class)
    @Import({ModelConfigConfiguration.class, DefaultChatModelProvider.class, DynamicChatModelRegistry.class,
            OpenAiCompatibleChatModelFactory.class, OpenAiCompatibleEndpointResolver.class, KnowledgeAnswerModelClient.class})
    static class EvaluationConfiguration { }

    /** 只启动模型配置读取所需组件，禁用 Web、迁移及 SQL 初始化，避免评测改动业务数据。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("expected dataset, output, repeats, optional candidate");
        var candidate = args.length == 4 ? KnowledgeAnswerModelClient.ReviewStrategy.valueOf(args[3])
                : KnowledgeAnswerModelClient.ReviewStrategy.CHECKLIST;
        if (candidate == KnowledgeAnswerModelClient.ReviewStrategy.BASELINE)
            throw new IllegalArgumentException("candidate must differ from baseline");
        var selectedStrategies = List.of(KnowledgeAnswerModelClient.ReviewStrategy.BASELINE, candidate);
        Path datasetPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int repeats = Integer.parseInt(args[2]);
        if (repeats < 1 || repeats > 5) throw new IllegalArgumentException("repeats must be 1..5");
        ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        Dataset dataset = json.readValue(Files.readString(datasetPath), Dataset.class);
        validate(dataset);
        Files.writeString(output.resolve("calls.jsonl"), "", StandardOpenOption.CREATE_NEW);
        var app = new SpringApplication(EvaluationConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.read-only=true", "--app.chat.log-model-protocol=false", "--logging.level.root=ERROR")) {
            var provider = context.getBean(DefaultChatModelProvider.class);
            var model = provider.get();
            var client = context.getBean(KnowledgeAnswerModelClient.class);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("startedAt", Instant.now().toString());
            metadata.put("modelConfigId", model.configId());
            metadata.put("modelName", model.modelName());
            metadata.put("datasetSha256", sha256(Files.readAllBytes(datasetPath)));
            metadata.put("repeats", repeats);
            metadata.put("candidate", candidate.name());
            metadata.put("planned", dataset.cases().size() * repeats * 2);
            for (var strategy : selectedStrategies) {
                metadata.put(strategy.name() + "PromptSha256", sha256(client.reviewPrompt(strategy).getBytes(StandardCharsets.UTF_8)));
            }
            Files.writeString(output.resolve("metadata.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
                    StandardOpenOption.CREATE_NEW);
            for (int repeat = 1; repeat <= repeats; repeat++) {
                var cases = new ArrayList<>(dataset.cases());
                Collections.shuffle(cases, new Random(20261019L + repeat));
                int index = 0;
                for (var item : cases) {
                    var strategies = new ArrayList<>(selectedStrategies);
                    if ((index++ + repeat) % 2 == 0) Collections.reverse(strategies);
                    for (var strategy : strategies) {
                        if (!provider.get().equals(model)) throw new IllegalStateException("default model changed during evaluation");
                        var row = execute(client, model, item, repeat, strategy);
                        Files.writeString(output.resolve("calls.jsonl"), json.writeValueAsString(row) + "\n", StandardOpenOption.APPEND);
                        System.out.println("REVIEW " + repeat + " " + item.id() + " " + strategy + " "
                                + (row.errorCode() != null ? row.errorCode() : row.review().approved()));
                    }
                }
            }
            Files.writeString(output.resolve("finished.json"), json.writeValueAsString(Map.of("finishedAt", Instant.now().toString())),
                    StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            System.err.println("Review evaluation stopped: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    /** 核对题集确实是经过引用校验的成对语义样本，禁止把协议错误混入负例成绩。 */
    public static void validate(Dataset dataset) {
        if (dataset.schemaVersion() != 1 || !"DEVELOPER_ASSISTANT_PROVISIONAL".equals(dataset.labelSource())
                || dataset.cases() == null || dataset.cases().isEmpty()) throw new IllegalArgumentException("invalid review dataset");
        var ids = new HashSet<String>();
        var pairs = new HashMap<String, List<Case>>();
        var guard = new KnowledgeAnswerGuard();
        for (var item : dataset.cases()) {
            if (item.id() == null || !ids.add(item.id()) || item.expectedApproved() == null
                    || item.rationale() == null || item.rationale().isBlank() || item.question() == null || item.question().isBlank()
                    || !List.of("DEVELOPMENT", "CHALLENGE").contains(item.split())) throw new IllegalArgumentException("invalid review case");
            guard.validate(item.draft(), item.evidence());
            pairs.computeIfAbsent(item.pairId(), ignored -> new ArrayList<>()).add(item);
        }
        for (var pair : pairs.values()) {
            if (pair.size() != 2 || pair.getFirst().expectedApproved().equals(pair.getLast().expectedApproved())
                    || !pair.getFirst().question().equals(pair.getLast().question())
                    || !pair.getFirst().scope().equals(pair.getLast().scope())
                    || !pair.getFirst().evidence().equals(pair.getLast().evidence())
                    || !pair.getFirst().split().equals(pair.getLast().split())) throw new IllegalArgumentException("invalid review pair");
        }
    }

    /** 每个样本只调用一次复核，技术失败与模型否决分开，错误正文不进入结果文件。 */
    public static Row execute(KnowledgeAnswerModelClient client, ResolvedChatModel model, Case item, int repeat,
                              KnowledgeAnswerModelClient.ReviewStrategy strategy) {
        long start = System.nanoTime();
        try {
            var result = client.review(model, item.question(), item.scope(), item.evidence(), item.draft(), strategy);
            return new Row(item.id(), item.pairId(), item.split(), item.expectedApproved(), repeat, strategy,
                    (System.nanoTime() - start) / 1_000_000, result.value(), null,
                    new KnowledgeAnswerModelClient.TokenUsage(result.promptTokens(), result.completionTokens(), result.totalTokens()));
        } catch (RuntimeException error) {
            return new Row(item.id(), item.pairId(), item.split(), item.expectedApproved(), repeat, strategy,
                    (System.nanoTime() - start) / 1_000_000, null,
                    error instanceof KnowledgeAnswerFailure known ? known.errorCode() : "KNOWLEDGE_REVIEW_CALL_FAILED",
                    error instanceof KnowledgeAnswerFailure known ? known.usage() : null);
        }
    }

    /** 对实际输入和提示生成摘要，供跨运行核对而不导出模型配置凭据。 */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
