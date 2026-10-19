package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.mapper.KnowledgeBaseMapper;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;

@Service
@RequiredArgsConstructor
public class KnowledgeApplicabilityService {
    private final KnowledgeBaseMapper bases;
    private final ObjectMapper json;
    private final Validator validator;

    /** 读取独立配置修订号；旧知识库默认没有主题，不额外调用路由模型。 */
    public KnowledgeApplicabilityPolicy get(Long baseId) {
        var base = bases.selectById(baseId);
        if (base == null) throw new KnowledgeBaseNotFoundException(baseId);
        if (base.getClarificationPolicy() == null) return new KnowledgeApplicabilityPolicy(0L, List.of());
        try {
            return json.readValue(base.getClarificationPolicy(), KnowledgeApplicabilityPolicy.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid stored applicability policy");
        }
    }

    /** 用知识库乐观锁和独立修订号更新配置，避免覆盖并发管理操作。 */
    @Transactional
    public KnowledgeApplicabilityPolicy update(Long baseId, KnowledgeApplicabilityPolicy request) {
        validate(request);
        var base = bases.selectById(baseId);
        if (base == null) throw new KnowledgeBaseNotFoundException(baseId);
        long revision = base.getClarificationRevision();
        if (request.revision() != revision) throw new KnowledgeClarificationException(true, "applicability policy has changed");
        var next = new KnowledgeApplicabilityPolicy(revision + 1, request.topics());
        try { base.setClarificationPolicy(json.writeValueAsString(next)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("cannot serialize applicability policy"); }
        base.setClarificationRevision(next.revision());
        base.setUpdatedAt(LocalDateTime.now());
        if (bases.updateById(base) != 1) throw new KnowledgeClarificationException(true, "applicability policy has changed");
        return next;
    }

    /** 禁止重复主题、通用范围充当缺失条件以及同一范围出现冲突名称。 */
    private void validate(KnowledgeApplicabilityPolicy policy) {
        if (policy == null || !validator.validate(policy).isEmpty())
            throw new KnowledgeClarificationException(false, "invalid applicability policy");
        Set<String> ids = new HashSet<>();
        Map<String, String> labels = new HashMap<>();
        for (var topic : policy.topics()) {
            if (!ids.add(topic.id())) throw new KnowledgeClarificationException(false, "duplicate topic id");
            Set<String> scopes = new HashSet<>();
            for (var option : topic.scopeOptions()) {
                String previous = labels.putIfAbsent(option.value(), option.label());
                if ("ALL".equals(option.value()) || !scopes.add(option.value())
                        || (previous != null && !previous.equals(option.label())))
                    throw new KnowledgeClarificationException(false, "invalid or inconsistent scope option");
            }
        }
    }

    /** 校验模型只能选择配置中的主题；多主题取共同范围，不自行扩大适用面。 */
    public List<KnowledgeApplicabilityPolicy.ScopeOption> requiredOptions(
            KnowledgeApplicabilityPolicy policy, KnowledgeApplicabilityPolicy.Route route) {
        if (route == null || route.uncertain() == null || route.topicIds() == null
                || route.topicIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(route.topicIds()).size() != route.topicIds().size()
                || !policy.topics().stream().map(KnowledgeApplicabilityPolicy.Topic::id).toList().containsAll(route.topicIds()))
            throw new KnowledgeAnswerFailure(KNOWLEDGE_PROTOCOL_INVALID, "invalid applicability route");
        if (route.uncertain()) throw new KnowledgeAnswerFailure(KNOWLEDGE_APPLICABILITY_UNCERTAIN, "uncertain applicability route");
        List<KnowledgeApplicabilityPolicy.ScopeOption> options = null;
        for (var topic : policy.topics()) {
            if (!route.topicIds().contains(topic.id()) || topic.scopeOptions().isEmpty()) continue;
            if (options == null) options = new ArrayList<>(topic.scopeOptions());
            else options.retainAll(topic.scopeOptions());
        }
        if (options != null && options.isEmpty())
            throw new KnowledgeAnswerFailure(KNOWLEDGE_APPLICABILITY_UNCERTAIN, "incompatible applicability topics");
        return options == null ? List.of() : List.copyOf(options);
    }

    /** 生成前后均检查修订，配置变更不会悄悄套用到旧澄清卡片。 */
    public void requireRevision(Long baseId, long revision) {
        if (get(baseId).revision() != revision)
            throw new KnowledgeAnswerFailure(KNOWLEDGE_APPLICABILITY_CHANGED, "applicability policy changed");
    }
}
