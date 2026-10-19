package com.anying.helpdesk.knowledge.answer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 管理员定义业务主题及选择范围；配置只表达适用条件，不提供制度答案。 */
public record KnowledgeApplicabilityPolicy(
        @NotNull @PositiveOrZero Long revision,
        @NotNull @Size(max = 20) List<@NotNull @Valid Topic> topics) {
    public record Topic(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{0,39}") String id,
                        @NotBlank @Size(max = 500) String description,
                        @NotNull @Size(max = 20) List<@NotNull @Valid ScopeOption> scopeOptions) { }
    public record ScopeOption(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String value,
                              @NotBlank @Size(max = 80) String label) { }
    public record Route(List<String> topicIds, Boolean uncertain) { }
    public record Assessment(KnowledgeApplicabilityPolicy policy, Route route, Long continuationOfMessageId, boolean routingAttempted) { }
}
