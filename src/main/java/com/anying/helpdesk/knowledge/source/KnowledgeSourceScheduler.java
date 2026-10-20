package com.anying.helpdesk.knowledge.source;

import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KnowledgeSourceScheduler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceScheduler.class);
    private final KnowledgeSourceStore sources;
    private final KnowledgeSourceSyncService sync;
    private final KnowledgeSourceProperties properties;
    private final Executor executor;
    private final AtomicBoolean busy = new AtomicBoolean();

    /** 来源扫描使用独立线程，不能阻塞索引任务的定时心跳与其他短调度。 */
    public KnowledgeSourceScheduler(KnowledgeSourceStore sources, KnowledgeSourceSyncService sync,
            KnowledgeSourceProperties properties, @Qualifier("knowledgeSourceExecutor") Executor executor) {
        this.sources = sources;
        this.sync = sync;
        this.properties = properties;
        this.executor = executor;
    }

    /** 定时扫描已登记来源，实例间竞争由数据库租约处理，异常信息不包含 OSS 凭据。 */
    @Scheduled(fixedDelayString = "${app.knowledge.sources.scan-delay-ms:60000}", initialDelayString = "${app.knowledge.sources.scan-delay-ms:60000}")
    public void tick() {
        if (!properties.isEnabled() || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(this::scanEnabled);
        } catch (RuntimeException exception) {
            busy.set(false);
            log.warn("Knowledge source executor unavailable ({})", exception.getClass().getSimpleName());
        }
    }

    /** 执行线程就绪后逐个领取来源，限制单实例的 OSS 扫描并发。 */
    private void scanEnabled() {
        try {
            for (var source : sources.enabled()) {
                try {
                    sync.scan(source.knowledgeBaseId(), source.id());
                } catch (RuntimeException exception) {
                    log.debug("Knowledge source scan did not complete: sourceId={}, type={}", source.id(), exception.getClass().getSimpleName());
                }
            }
        } finally {
            busy.set(false);
        }
    }
}
