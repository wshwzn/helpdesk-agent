package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.index.KnowledgeIndexTaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KnowledgeIndexTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexTaskDispatcher.class);
    private final KnowledgeIndexTaskStore tasks;
    private final KnowledgeDocumentIndexWorker worker;
    private final KnowledgeIndexTaskProperties properties;
    private final Executor executor;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile KnowledgeIndexClaim running;

    /** 每实例限制一个执行任务，数据库租约负责跨实例协调。 */
    public KnowledgeIndexTaskDispatcher(KnowledgeIndexTaskStore tasks, KnowledgeDocumentIndexWorker worker,
            KnowledgeIndexTaskProperties properties, @Qualifier("knowledgeIndexExecutor") Executor executor) {
        this.tasks = tasks;
        this.worker = worker;
        this.properties = properties;
        this.executor = executor;
    }

    /** 短周期续租或分派执行；数据库中没有任务的 PENDING 文档不会被扫描。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        KnowledgeIndexClaim claim = running;
        if (claim != null) {
            tasks.renew(claim);
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runNext);
        } catch (RuntimeException exception) {
            busy.set(false);
            log.warn("Knowledge index executor unavailable ({})", exception.getClass().getSimpleName());
        }
    }

    /** 执行线程就绪后才领取任务，避免租约在本地线程队列中被耗尽。 */
    private void runNext() {
        try {
            running = tasks.claimNext();
            if (running != null) {
                worker.index(running);
            }
        } catch (RuntimeException exception) {
            log.warn("Knowledge index dispatch failed ({})", exception.getClass().getSimpleName());
        } finally {
            running = null;
            busy.set(false);
        }
    }
}
