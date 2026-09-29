package com.anying.helpdesk.conversation.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaleChatGenerationRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StaleChatGenerationRecoveryJob.class);

    private final StaleChatGenerationRecoveryService recoveryService;

    /**
     * 定期回收因服务退出或请求异常而长期停留在 PENDING 的 AI 消息。
     */
    @Scheduled(
            initialDelayString = "${app.chat.pending-recovery-interval:1m}",
            fixedDelayString = "${app.chat.pending-recovery-interval:1m}"
    )
    public void recoverStaleGenerations() {
        int recoveredCount = recoveryService.recoverAll();
        if (recoveredCount > 0) {
            log.warn("Recovered {} interrupted AI response generation(s)", recoveredCount);
        }
    }
}
