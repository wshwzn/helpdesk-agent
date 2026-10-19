package com.anying.helpdesk.knowledge;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentParsingException;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.knowledge.processing.KnowledgeDocumentParser;
import com.anying.helpdesk.knowledge.processing.ParsedDocument;
import com.anying.helpdesk.knowledge.service.*;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import com.anying.helpdesk.security.JwtTokenService;
import com.anying.helpdesk.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:index_tasks;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeIndexTaskIntegrationTest {
    @Autowired private KnowledgeBaseService bases;
    @Autowired private KnowledgeDocumentPersistenceService documents;
    @Autowired private KnowledgeDocumentPublicationService publication;
    @Autowired private KnowledgeDocumentService documentService;
    @Autowired private KnowledgeDocumentIndexingService indexing;
    @Autowired private KnowledgeIndexTaskStore tasks;
    @Autowired private KnowledgeDocumentIndexWorker worker;
    @Autowired private KnowledgeDocumentEligibilityService eligibility;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtTokenService jwt;
    @MockitoBean private ObjectStorageService storage;
    @MockitoBean private KnowledgeDocumentParser parser;
    @MockitoBean private DefaultEmbeddingModelProvider models;
    @MockitoBean private KnowledgeChunkStore chunks;
    private Long baseId;
    private Long documentId;

    /** 每个用例创建可独立提交事务的文档，以便真实测试并发领取。 */
    @BeforeEach
    void prepare() {
        baseId = bases.create(new CreateKnowledgeBaseRequest("索引任务-" + UUID.randomUUID(), "测试"), 1L).id();
        documentId = documents.createUploading(baseId, "policy.md", "local", "local",
                UUID.randomUUID() + ".md", "text/markdown", "md", 10, "a".repeat(64), 1L).getId();
        documents.markPending(documentId);
    }

    /** 清理本用例数据，任务通过外键级联清除。 */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_documents WHERE knowledge_base_id = ?", baseId);
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", baseId);
    }

    /** 单纯上传或保存元数据不会被定时索引调度接管。 */
    @Test
    @DisplayName("待索引文档没有提交任务时不会自动执行")
    void pendingDocumentsAreNotAutomaticallyEnqueued() {
        assertThat(tasks.find(documentId)).isNull();
        assertThat(tasks.claimNext()).isNull();
        publication.updateMetadata(baseId, documentId, metadata());
        assertThat(tasks.claimNext()).isNull();
        assertThat(documents.get(baseId, documentId).getStatus()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        verifyNoInteractions(models, chunks);
    }

    /** 验证原子提交接口、状态查询及管理员权限，202 不依赖外部服务可用。 */
    @Test
    @DisplayName("保存并提交接口创建持久任务且不在请求线程调用模型")
    void submitsThroughAdminApi() throws Exception {
        String path = "/api/admin/knowledge-bases/" + baseId + "/documents/" + documentId;
        mvc.perform(post(path + "/submit").header("Authorization", token(UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(metadata())))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/index-task").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post(path + "/submit").header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(metadata())))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.publicationStatus").value("DRAFT"));
        mvc.perform(get(path + "/index-task").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attemptCount").value(0))
                .andExpect(jsonPath("$.data.runToken").doesNotExist());
        mvc.perform(get("/api/admin/knowledge-bases/999999/documents/" + documentId + "/index-task")
                        .header("Authorization", token(UserRole.ADMIN))).andExpect(status().isNotFound());
        verifyNoInteractions(models, chunks);
    }

    /** 验证业务元数据修改与任务入队同事务回滚，提交过程不会留下半成品。 */
    @Test
    @DisplayName("提交事务回滚时元数据和任务一起撤销")
    void rollsBackSubmissionTogether() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            indexing.submit(baseId, documentId, metadata());
            throw new IllegalStateException("simulate rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.find(documentId)).isNull();
        assertThat(documents.get(baseId, documentId).getSourceKey()).isNull();
        assertThat(documents.get(baseId, documentId).getStatus()).isEqualTo(KnowledgeDocumentStatus.PENDING);
    }

    /** 两个实例同时提交和领取时，数据库只保留一条任务且只发放一个有效执行权。 */
    @Test
    @DisplayName("并发提交幂等且并发领取只有一个执行者")
    void serializesConcurrentSubmissionAndClaim() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return indexing.start(baseId, documentId); });
            var second = pool.submit(() -> { start.await(); return indexing.start(baseId, documentId); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).version()).isEqualTo(second.get(10, TimeUnit.SECONDS).version());
            assertThat(tasks.find(documentId).attemptCount()).isZero();
            CountDownLatch claim = new CountDownLatch(1);
            var a = pool.submit(() -> { claim.await(); return tasks.claimNext(); });
            var b = pool.submit(() -> { claim.await(); return tasks.claimNext(); });
            claim.countDown();
            assertThat(java.util.stream.Stream.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList()).hasSize(1);
            assertThat(tasks.find(documentId).attemptCount()).isEqualTo(1);
        }
    }

    /** 任务持有文档期间禁止修改元数据和删除，避免执行快照与来源不一致。 */
    @Test
    @DisplayName("排队和执行期间禁止修改或删除文档")
    void freezesDocumentDuringTask() {
        indexing.submit(baseId, documentId, metadata());
        assertThatThrownBy(() -> publication.updateMetadata(baseId, documentId, metadata()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> documentService.delete(baseId, documentId))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
    }

    /** 完成索引仅激活技术批次，发布仍是独立业务动作。 */
    @Test
    @DisplayName("真实工作流完成批次后仍需人工发布才能检索")
    void completesWorkerWithoutPublishing() {
        indexing.submit(baseId, documentId, metadata());
        prepareExternalServices();
        KnowledgeIndexClaim claim = tasks.claimNext();
        worker.index(claim);
        var document = documents.get(baseId, documentId);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(document.getActiveIndexBuildId()).isEqualTo(claim.token());
        assertThat(document.getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.DRAFT);
        assertThat(tasks.find(documentId).status()).isEqualTo("SUCCEEDED");
        assertThat(eligibility.findEligible(baseId, "ALL", LocalDateTime.now())).isEmpty();
        publication.publish(baseId, documentId, document.getVersion());
        assertThat(eligibility.findEligible(baseId, "ALL", LocalDateTime.now())).containsKey(documentId);
        assertThatThrownBy(() -> indexing.start(baseId, documentId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        verify(chunks, never()).deleteDocument(anyLong());
    }

    /** 租约过期模拟进程退出；新实例接管后旧实例完成、失败、续租均不能修改结果。 */
    @Test
    @DisplayName("过期任务恢复后旧执行者不能激活或破坏新批次")
    void recoversExpiredLeaseAndFencesOldWorker() {
        indexing.submit(baseId, documentId, metadata());
        KnowledgeIndexClaim old = tasks.claimNext();
        assertThat(tasks.renew(old)).isTrue();
        expireLease();
        assertThat(tasks.renew(old)).isFalse();
        KnowledgeIndexClaim next = tasks.claimNext();
        assertThat(next.token()).isNotEqualTo(old.token());
        assertThat(tasks.complete(old, 10)).isFalse();
        tasks.fail(old, "EMBEDDING_FAILED", true);
        assertThat(tasks.find(documentId).status()).isEqualTo("RUNNING");
        assertThat(tasks.complete(next, 2)).isTrue();
        assertThat(tasks.complete(old, 10)).isFalse();
        tasks.fail(old, "EMBEDDING_FAILED", false);
        assertThat(documents.get(baseId, documentId).getActiveIndexBuildId()).isEqualTo(next.token());
        assertThat(documents.get(baseId, documentId).getChunkCount()).isEqualTo(2);
        assertThat(tasks.find(documentId).status()).isEqualTo("SUCCEEDED");
    }

    /** 暂时性故障保留任务并退避，到达预算后终止；管理员可显式重新提交。 */
    @Test
    @DisplayName("自动重试有限且失败后可手动重试")
    void retriesWithBoundAndAllowsExplicitResubmission() {
        indexing.start(baseId, documentId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            KnowledgeIndexClaim claim = tasks.claimNext();
            tasks.fail(claim, "ELASTICSEARCH_INDEX_FAILED", true);
            assertThat(tasks.find(documentId).attemptCount()).isEqualTo(attempt);
            assertThat(tasks.claimNext()).isNull();
            makeDue();
        }
        assertThat(tasks.find(documentId).status()).isEqualTo("FAILED");
        assertThat(documents.get(baseId, documentId).getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        indexing.start(baseId, documentId);
        assertThat(tasks.find(documentId).attemptCount()).isZero();
        assertThat(tasks.find(documentId).status()).isEqualTo("QUEUED");
    }

    /** 多次进程退出同样消耗重试预算，避免任务永久卡在 PROCESSING。 */
    @Test
    @DisplayName("连续租约过期达到上限后明确失败")
    void boundsRepeatedCrashes() {
        indexing.start(baseId, documentId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(tasks.claimNext()).isNotNull();
            expireLease();
        }
        assertThat(tasks.claimNext()).isNull();
        assertThat(tasks.find(documentId).errorCode()).isEqualTo("INDEX_LEASE_EXPIRED");
        assertThat(documents.get(baseId, documentId).getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
    }

    /** 无法解析的文件不会重复消耗模型调用，删除时清理该文档所有批次及任务。 */
    @Test
    @DisplayName("解析错误立即终止且删除文档会级联删除任务")
    void failsInvalidDocumentAndDeletesTask() {
        indexing.start(baseId, documentId);
        prepareExternalServices();
        when(parser.parse(any(), anyString(), anyString())).thenThrow(new KnowledgeDocumentParsingException("invalid"));
        worker.index(tasks.claimNext());
        assertThat(tasks.find(documentId).status()).isEqualTo("FAILED");
        assertThat(tasks.find(documentId).attemptCount()).isEqualTo(1);
        documentService.delete(baseId, documentId);
        assertThat(tasks.find(documentId)).isNull();
        verify(chunks).deleteDocument(documentId);
    }

    /** 为有效文件提供可预测的存储、解析与向量结果，测试不调用真实外部服务。 */
    private void prepareExternalServices() {
        when(storage.provider()).thenReturn("local");
        when(storage.download(anyString(), anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[0]));
        when(parser.parse(any(), anyString(), anyString())).thenReturn(new ParsedDocument("住宿上限为五百元。", "text/markdown"));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1, 0, 0}));
        when(models.get()).thenReturn(new ResolvedEmbeddingModel(1L, "test", 3, model));
    }

    /** 构造携带当前乐观锁版本的完整业务元数据。 */
    private UpdateKnowledgeDocumentMetadataRequest metadata() {
        return new UpdateKnowledgeDocumentMetadataRequest(documents.get(baseId, documentId).getVersion(),
                "travel", "v1", "ALL", LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    /** 使用受控 SQL 模拟进程退出后经过租约期限，无需等待真实时间。 */
    private void expireLease() {
        jdbc.update("UPDATE knowledge_index_tasks SET lease_until = ? WHERE document_id = ?", LocalDateTime.of(2000, 1, 1, 0, 0), documentId);
    }

    /** 将退避中的任务推进到可重试时间，保持测试快速且确定。 */
    private void makeDue() {
        jdbc.update("UPDATE knowledge_index_tasks SET available_at = ? WHERE document_id = ?", LocalDateTime.of(2000, 1, 1, 0, 0), documentId);
    }

    /** 生成角色测试令牌，不依赖本地账号或真实凭据。 */
    private String token(UserRole role) {
        User user = new User();
        user.setId(1L);
        user.setUsername("index-test");
        user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
