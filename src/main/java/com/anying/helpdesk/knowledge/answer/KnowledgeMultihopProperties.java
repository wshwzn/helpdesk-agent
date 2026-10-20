package com.anying.helpdesk.knowledge.answer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 开关便于同题单轮对照；调度预算不承诺中断正在进行的外部调用。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.knowledge.multihop")
public class KnowledgeMultihopProperties {
    private boolean enabled = true;
    private boolean ruleChecksEnabled = false;
    @Min(1) @Max(180)
    private int schedulingBudgetSeconds = 90;
}
