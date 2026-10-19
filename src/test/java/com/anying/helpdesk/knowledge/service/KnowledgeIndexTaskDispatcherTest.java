package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.index.KnowledgeIndexTaskProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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
        var dispatcher = new KnowledgeIndexTaskDispatcher(tasks, worker, new KnowledgeIndexTaskProperties(), executor);
        dispatcher.tick();
        verifyNoInteractions(tasks, worker);
        dispatcher.tick();
        verify(tasks).claimNext();
    }

    /** 正在执行的任务继续心跳，但同实例不会再领取另一个任务。 */
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
        var dispatcher = new KnowledgeIndexTaskDispatcher(tasks, worker, new KnowledgeIndexTaskProperties(), Runnable::run);
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
}
