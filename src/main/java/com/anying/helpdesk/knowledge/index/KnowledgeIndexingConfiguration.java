package com.anying.helpdesk.knowledge.index;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class KnowledgeIndexingConfiguration {

    /**
     * 创建容量受限的知识库索引线程池，避免大批文档耗尽 Web 请求线程。
     */
    @Bean(name = "knowledgeIndexExecutor")
    public Executor knowledgeIndexExecutor(KnowledgeIndexTaskProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(properties.getConcurrency());
        executor.setThreadNamePrefix("knowledge-index-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
