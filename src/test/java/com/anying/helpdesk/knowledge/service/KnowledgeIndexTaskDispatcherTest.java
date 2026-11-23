package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.index.KnowledgeIndexTaskProperties;
import com.anying.helpdesk.knowledge.index.KnowledgeIndexingConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class KnowledgeIndexTaskDispatcherTest {
    /** 线程池拒绝后不领取任务，下次调度仍可重试，任务不会丢在内存队列。 */
    @Test
    @DisplayName("线程池拒绝后任务仍可在下一轮被领取")
    void rejectionDoesNotLoseOrClaimTask() {
        var tasks = mock(KnowledgeIndexTaskStore.class);
        var worker = mock(KnowledgeDocumentIndexWorker.class);
        var executor = mock(Executor.class);
        doThrow(new RejectedExecutionException()).doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        var properties = new KnowledgeIndexTaskProperties();
        properties.setConcurrency(1);
        var dispatcher = new KnowledgeIndexTaskDispatcher(tasks, worker, properties, executor);
        dispatcher.tick();
        verifyNoInteractions(tasks, worker);
        dispatcher.tick();
        verify(tasks).claimNext();
    }

    /** 单执行位正在执行的任务继续心跳，不会重复领取另一个任务。 */
    @Test
    @DisplayName("执行期间心跳续租且不会重复分派")
    void renewsRunningTaskWithoutDispatchingAnother() {
        var tasks = mock(KnowledgeIndexTaskStore.class);
        var worker = mock(KnowledgeDocumentIndexWorker.class);
        var claim = new KnowledgeIndexClaim(1L, 2L, "token");
        when(tasks.claimNext()).thenReturn(claim);
        AtomicReference<KnowledgeIndexTaskDispatcher> reference = new AtomicReference<>();
        doAnswer(invocation -> {
            reference.get().tick();
            return null;
        }).when(worker).index(claim);
        var properties = new KnowledgeIndexTaskProperties();
        properties.setConcurrency(1);
        var dispatcher = new KnowledgeIndexTaskDispatcher(tasks, worker, properties, Runnable::run);
        reference.set(dispatcher);
        dispatcher.tick();
        verify(tasks).renew(claim);
        verify(tasks).claimNext();
        verify(worker).index(claim);
    }

    /** 停用调度后不扫描数据库，保留入队供维护结束后继续。 */
    @Test
    @DisplayName("调度关闭时不领取任务")
    void disabledDispatcherDoesNotClaim() {
        var tasks = mock(KnowledgeIndexTaskStore.class);
        var worker = mock(KnowledgeDocumentIndexWorker.class);
        var properties = new KnowledgeIndexTaskProperties();
        properties.setEnabled(false);
        new KnowledgeIndexTaskDispatcher(tasks, worker, properties, Runnable::run).tick();
        verifyNoInteractions(tasks, worker);
    }

    /** 使用真实索引线程池验证三份文档并发执行、分别续租，以及完成后继续领取。 */
    @Test
    @DisplayName("按并发上限执行并续租，完成后领取下一批任务")
    void runsConcurrentTasksAndRefillsFreeSlots() throws Exception {
        var tasks = mock(KnowledgeIndexTaskStore.class);
        var worker = mock(KnowledgeDocumentIndexWorker.class);
        var first = new KnowledgeIndexClaim(1L, 1L, "first");
        var second = new KnowledgeIndexClaim(1L, 2L, "second");
        var third = new KnowledgeIndexClaim(1L, 3L, "third");
        var fourth = new KnowledgeIndexClaim(1L, 4L, "fourth");
        when(tasks.claimNext()).thenReturn(first, second, third, fourth, null);
        var started = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(3);
        var nextStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            KnowledgeIndexClaim claim = invocation.getArgument(0);
            if (claim.equals(fourth)) {
                nextStarted.countDown();
            } else {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(worker).index(any());
        var properties = new KnowledgeIndexTaskProperties();
        var executor = (ThreadPoolTaskExecutor) new KnowledgeIndexingConfiguration().knowledgeIndexExecutor(properties);
        Executor observedExecutor = command -> executor.execute(() -> {
            try {
                command.run();
            } finally {
                finished.countDown();
            }
        });
        var dispatcher = new KnowledgeIndexTaskDispatcher(tasks, worker, properties, observedExecutor);
        try {
            dispatcher.tick();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            dispatcher.tick();
            verify(tasks, times(3)).claimNext();
            verify(tasks).renew(first);
            verify(tasks).renew(second);
            verify(tasks).renew(third);
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            dispatcher.tick();
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
        }
        verify(worker).index(first);
        verify(worker).index(second);
        verify(worker).index(third);
        verify(worker).index(fourth);
    }
}
