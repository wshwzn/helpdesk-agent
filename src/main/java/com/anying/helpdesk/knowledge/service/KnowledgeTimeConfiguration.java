package com.anying.helpdesk.knowledge.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class KnowledgeTimeConfiguration {
    /**
     * 固定知识有效期的业务时区，避免部署机器时区不同改变制度生效时刻。
     */
    @Bean
    public Clock knowledgeClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
