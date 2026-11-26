package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.index.KnowledgeIndexTaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.stream.IntStream;

@Component
public class KnowledgeIndexTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexTaskDispatcher.class);
    private final KnowledgeIndexTaskStore tasks;
    private final KnowledgeDocumentIndexWorker worker;
    private final KnowledgeIndexTaskProperties properties;
    private final Executor executor;
    private final List<Slot> slots;

    /** 按配置创建固定执行位，数据库租约负责跨实例协调。 */
    public KnowledgeIndexTaskDispatcher(KnowledgeIndexTaskStore tasks, KnowledgeDocumentIndexWorker worker,
            KnowledgeIndexTaskProperties properties, @Qualifier("knowledgeIndexExecutor") Executor executor) {
        this.tasks = tasks;
        this.worker = worker;
        this.properties = properties;
        this.executor = executor;
        this.slots = IntStream.range(0, properties.getConcurrency()).mapToObj(i -> new Slot()).toList();
    }

    /** 短周期续租或分派执行；数据库中没有任务的 PENDING 文档不会被扫描。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        for (Slot slot : slots) {
            dispatch(slot);
        }
    }

    /** 为执行中的文档续租，并向空闲执行位分派任务。 */
    private void dispatch(Slot slot) {
        KnowledgeIndexClaim claim = slot.running;
        if (claim != null) {
            try {
                tasks.renew(claim);
            } catch (RuntimeException exception) {
                log.warn("Knowledge index lease renewal failed for document {}", claim.documentId(), exception);
            }
        }
        if (!slot.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> runNext(slot));
        } catch (RuntimeException exception) {
            slot.busy.set(false);
            log.warn("Knowledge index executor unavailable", exception);
        }
    }

    /** 执行线程就绪后才领取任务，避免租约在本地线程队列中被耗尽。 */
    private void runNext(Slot slot) {
        try {
            slot.running = tasks.claimNext();
            if (slot.running != null) {
                worker.index(slot.running);
            }
        } catch (RuntimeException exception) {
            log.warn("Knowledge index dispatch failed", exception);
        } finally {
            slot.running = null;
            slot.busy.set(false);
        }
    }

    private static final class Slot {
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile KnowledgeIndexClaim running;
    }
}
