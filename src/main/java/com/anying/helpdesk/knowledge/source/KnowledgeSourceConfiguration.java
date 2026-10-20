package com.anying.helpdesk.knowledge.source;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class KnowledgeSourceConfiguration {
    /** 为 OSS 扫描保留一个有界工作线程，关闭时不再接受新扫描。 */
    @Bean
    public ThreadPoolTaskExecutor knowledgeSourceExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("knowledge-source-");
        return executor;
    }
}
