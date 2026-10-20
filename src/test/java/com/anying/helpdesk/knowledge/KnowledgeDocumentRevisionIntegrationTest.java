package com.anying.helpdesk.knowledge;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.*;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:document_revisions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeDocumentRevisionIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDateTime START = NOW.minusMonths(1);
    private static final String OLD_TEXT = "上海住宿上限为500元。";
    private static final String NEW_TEXT = "上海住宿上限为600元。";
    @Autowired private KnowledgeBaseService bases;
    @Autowired private KnowledgeDocumentService uploads;
    @Autowired private KnowledgeDocumentRevisionService revisions;
    @Autowired private KnowledgeRevisionPersistenceService revisionPersistence;
    @Autowired private KnowledgeDocumentPersistenceService documents;
    @Autowired private KnowledgeDocumentPublicationService publication;
    @Autowired private KnowledgeDocumentIndexingService indexing;
    @Autowired private KnowledgeDocumentIndexWorker worker;
    @Autowired private KnowledgeIndexTaskStore tasks;
    @Autowired private KnowledgeDocumentEligibilityService eligibility;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtTokenService jwt;
    @MockitoBean private ObjectStorageService storage;
    @MockitoBean private DefaultEmbeddingModelProvider models;
    @MockitoBean private KnowledgeChunkStore chunks;
    @MockitoBean private Clock knowledgeClock;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private Long baseId;
    private Long oldId;

    /** 创建一份真实经过上传、解析、任务完成及发布的旧版，外部存储和模型使用替身。 */
    @BeforeEach
    void prepare() throws Exception {
        setTime(NOW);
        when(storage.provider()).thenReturn("local");
        when(storage.bucketName()).thenReturn("local");
        doAnswer(call -> {
            objects.put(call.getArgument(0), call.<InputStream>getArgument(1).readAllBytes());
            return null;
        }).when(storage).upload(anyString(), any(), anyLong(), anyString());
        when(storage.download(anyString(), anyString())).thenAnswer(call -> {
            byte[] bytes = objects.get(call.<String>getArgument(1));
            if (bytes == null) throw new KnowledgeStorageException("missing test object");
            return new ByteArrayInputStream(bytes);
        });
        doAnswer(call -> { objects.remove(call.<String>getArgument(1)); return null; })
                .when(storage).delete(anyString(), anyString());
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(call -> call.<java.util.List<String>>getArgument(0)
                .stream().map(value -> new float[]{1, 0, 0}).toList());
        when(models.get()).thenReturn(new ResolvedEmbeddingModel(1L, "test-model", 3, model));
        baseId = bases.create(new CreateKnowledgeBaseRequest("版本交接-" + UUID.randomUUID(), "测试"), 1L).id();
        oldId = uploads.upload(baseId, file(OLD_TEXT), 1L).id();
        indexing.submit(baseId, oldId, new UpdateKnowledgeDocumentMetadataRequest(doc(oldId).getVersion(),
                "travel", "v1", "CN_SH", START, null));
        runNext();
        publication.publish(baseId, oldId, doc(oldId).getVersion());
    }

    /** 先断开本用例版本链再删除测试数据，保留外键保护正常业务删除的行为。 */
    @AfterEach
    void clean() {
        jdbc.update("UPDATE knowledge_documents SET predecessor_document_id = NULL, successor_document_id = NULL, superseded_at = NULL WHERE knowledge_base_id = ?", baseId);
        jdbc.update("DELETE FROM knowledge_documents WHERE knowledge_base_id = ?", baseId);
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", baseId);
    }

    /** 内容和元数据均未变化时不创建候选、不上传对象、不消耗向量调用。 */
    @Test
    @DisplayName("与旧版完全相同的上传直接返回无变化")
    void skipsUnchangedPayload() {
        var result = revisions.upload(baseId, oldId, file(OLD_TEXT), request("v1", START, null), 1L);
        assertThat(result.outcome()).isEqualTo("UNCHANGED");
        assertThat(result.document().id()).isEqualTo(oldId);
        assertThat(objects).hasSize(1);
        assertThat(tasks.claimNext()).isNull();
        verify(storage, times(1)).upload(anyString(), any(), anyLong(), anyString());
        verify(models, times(1)).get();
    }

    /** 上传新版固定继承来源和范围，重复请求复用候选及其唯一任务。 */
    @Test
    @DisplayName("重复上传新版复用候选且准备期间旧版继续可用")
    void deduplicatesCandidateAndKeepsPredecessorAvailable() {
        var request = request("v2", START, null);
        var first = revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L);
        var second = revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L);
        assertThat(first.outcome()).isEqualTo("CREATED");
        assertThat(second.outcome()).isEqualTo("EXISTING");
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(first.document().predecessorDocumentId()).isEqualTo(oldId);
        assertThat(first.document().sourceKey()).isEqualTo("travel");
        assertThat(first.document().applicabilityScope()).isEqualTo("CN_SH");
        assertEligible(NOW, oldId);
        assertThat(objects).hasSize(2);
        assertThat(tasks.find(first.document().id()).attemptCount()).isZero();
        verify(storage, times(2)).upload(anyString(), any(), anyLong(), anyString());
    }

    /** 同一业务版本不能被另一个请求静默覆盖，文件内容变化需要新版本标签。 */
    @Test
    @DisplayName("同一业务版本不同内容或有效期返回冲突")
    void rejectsConflictingDuplicate() {
        var request = request("v2", START, null);
        revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L);
        assertThatThrownBy(() -> revisions.upload(baseId, oldId, file("不同内容"), request, 1L))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> revisions.upload(baseId, oldId, file(NEW_TEXT), request("v2", START.plusDays(1), null), 1L))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> revisions.upload(baseId, oldId, file(NEW_TEXT), request("v1", START, null), 1L))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThat(objects).hasSize(2);
    }

    /** 相同文件的新业务版本仍需构建自己的元数据快照，不复用错误版本的片段。 */
    @Test
    @DisplayName("仅业务元数据变化也创建独立候选")
    void indexesMetadataOnlyRevision() {
        var result = revisions.upload(baseId, oldId, file(OLD_TEXT), request("v2", START, null), 1L);
        assertThat(result.outcome()).isEqualTo("CREATED");
        runNext();
        assertThat(doc(result.document().id()).getActiveIndexBuildId()).isNotEqualTo(doc(oldId).getActiveIndexBuildId());
        assertThat(doc(result.document().id()).getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.DRAFT);
    }

    /** 对同一父版本的并发上传通过父行锁串行预留，只产生一个对象和任务。 */
    @Test
    @DisplayName("并发重复上传只创建一份新版")
    void serializesDuplicateUploads() throws Exception {
        var request = request("v2", START, null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L); });
            var b = pool.submit(() -> { start.await(); return revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L); });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.document().id()).isEqualTo(second.document().id());
            assertThat(java.util.List.of(first.outcome(), second.outcome())).containsExactlyInAnyOrder("CREATED", "EXISTING");
            assertThat(objects).hasSize(2);
            assertThat(tasks.find(first.document().id()).status()).isEqualTo("QUEUED");
        }
    }

    /** 审核立即生效时双方使用同一个微秒边界，旧文件及原业务有效期保留。 */
    @Test
    @DisplayName("立即替换原子交接且保留旧版文件和来源记录")
    void approvesImmediateReplacement() {
        Long nextId = candidate("v2", START, null);
        runNext();
        var result = approve(nextId);
        assertThat(result.effectiveAt()).isEqualTo(NOW);
        assertThat(doc(oldId).getValidUntil()).isNull();
        assertThat(doc(oldId).getSupersededAt()).isEqualTo(NOW);
        assertThat(doc(nextId).getActivatedAt()).isEqualTo(NOW);
        assertThat(doc(nextId).getValidFrom()).isEqualTo(START);
        assertEligible(NOW.minusNanos(1000), oldId);
        assertEligible(NOW, nextId);
        assertThat(KnowledgeDocumentMetadata.from(doc(oldId)).validUntil()).isEqualTo(NOW);
        assertThat(KnowledgeDocumentMetadata.from(doc(nextId)).validFrom()).isEqualTo(NOW);
        assertThat(new String(objects.get(doc(oldId).getObjectKey()), StandardCharsets.UTF_8)).isEqualTo(OLD_TEXT);
        assertThat(new String(objects.get(doc(nextId).getObjectKey()), StandardCharsets.UTF_8)).isEqualTo(NEW_TEXT);
    }

    /** 未来交接依靠准入时间过滤，整个期间不需要定时任务修改发布状态。 */
    @Test
    @DisplayName("未来版本在准确生效边界接替旧版")
    void schedulesFutureReplacementWithoutEarlyWithdrawal() {
        LocalDateTime at = NOW.plusDays(2);
        Long nextId = candidate("v2", at, null);
        runNext();
        assertThat(approve(nextId).effectiveAt()).isEqualTo(at);
        assertThat(doc(oldId).getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.PUBLISHED);
        assertEligible(NOW, oldId);
        assertEligible(at.minusNanos(1000), oldId);
        assertEligible(at, nextId);
    }

    /** 撤回尚未生效的新版会撤销原定交接，使旧版继续沿自身有效期提供服务。 */
    @Test
    @DisplayName("撤回待生效新版同时取消旧版退出边界")
    void cancelsScheduledSuccessorSafely() {
        LocalDateTime at = NOW.plusDays(2);
        Long nextId = candidate("v2", at, null);
        runNext();
        approve(nextId);
        assertThatThrownBy(() -> publication.withdraw(baseId, oldId, doc(oldId).getVersion()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        publication.withdraw(baseId, nextId, doc(nextId).getVersion());
        assertThat(doc(oldId).getSuccessorDocumentId()).isNull();
        assertThat(doc(oldId).getSupersededAt()).isNull();
        assertThat(doc(nextId).getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.WITHDRAWN);
        assertEligible(at.plusDays(1), oldId);
    }

    /** 已经生效后撤回新版不能自动复活过时制度，已审核链也不能被物理删除。 */
    @Test
    @DisplayName("生效后撤回新版不会恢复旧制度")
    void doesNotRestoreOldPolicyAfterActivation() {
        Long nextId = candidate("v2", START, null);
        runNext();
        approve(nextId);
        publication.withdraw(baseId, nextId, doc(nextId).getVersion());
        assertThat(eligibility.findEligible(baseId, "CN_SH", NOW)).isEmpty();
        publication.withdraw(baseId, oldId, doc(oldId).getVersion());
        assertThatThrownBy(() -> uploads.delete(baseId, oldId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> uploads.delete(baseId, nextId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThat(objects).hasSize(2);
    }

    /** 拒绝旧版不能覆盖到交接时刻的计划，防止更新流程主动制造服务空档。 */
    @Test
    @DisplayName("新版有效期产生空档或已经过期时审核失败")
    void rejectsGapAndExpiredRevision() {
        jdbc.update("UPDATE knowledge_documents SET valid_until = ? WHERE id = ?", NOW.plusDays(1), oldId);
        Long nextId = candidate("v2", NOW.plusDays(2), null);
        runNext();
        assertThatThrownBy(() -> approve(nextId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        Long expired = candidate("v3", START, NOW.minusDays(1));
        runNext();
        assertThatThrownBy(() -> approve(expired)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThat(doc(oldId).getSuccessorDocumentId()).isNull();
        assertEligible(NOW, oldId);
    }

    /** 更新两份记录的事务发生回滚时，双方状态都必须恢复。 */
    @Test
    @DisplayName("审核事务回滚不会只切换一方")
    void rollsBackBothSides() {
        Long nextId = candidate("v2", START, null);
        runNext();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            approve(nextId);
            throw new IllegalStateException("simulate transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(doc(oldId).getSuccessorDocumentId()).isNull();
        assertThat(doc(nextId).getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.DRAFT);
        assertEligible(NOW, oldId);
    }

    /** 对相同旧版的两份候选同时审核，只有一个提交能改变有效版本。 */
    @Test
    @DisplayName("并发审核不同候选只有一个成功")
    void preventsTwoApprovedSuccessors() throws Exception {
        Long a = candidate("v2", START, null);
        Long b = candidate("v3", START, null);
        runNext();
        runNext();
        Integer oldVersion = doc(oldId).getVersion();
        var ra = new ApproveKnowledgeRevisionRequest(doc(a).getVersion(), oldVersion);
        var rb = new ApproveKnowledgeRevisionRequest(doc(b).getVersion(), oldVersion);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return tryApprove(a, ra); });
            var second = pool.submit(() -> { start.await(); return tryApprove(b, rb); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        Long winner = doc(oldId).getSuccessorDocumentId();
        assertThat(winner).isIn(a, b);
        assertEligible(NOW, winner);
    }

    /** 候选索引失败和上传失败都只影响候选，不改变旧版文件、索引或发布状态。 */
    @Test
    @DisplayName("候选处理失败不影响旧版问答资格")
    void keepsPredecessorOnFailures() {
        String oldBuild = doc(oldId).getActiveIndexBuildId();
        Long nextId = candidate("v2", START, null);
        tasks.fail(tasks.claimNext(), "DOCUMENT_PARSE_FAILED", false);
        assertThat(doc(nextId).getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        assertThatThrownBy(() -> approve(nextId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        doThrow(new KnowledgeStorageException("simulated upload failure"))
                .when(storage).upload(anyString(), any(), anyLong(), anyString());
        assertThatThrownBy(() -> candidate("v3", START, null)).isInstanceOf(KnowledgeStorageException.class);
        assertThat(doc(oldId).getActiveIndexBuildId()).isEqualTo(oldBuild);
        assertThat(doc(oldId).getSuccessorDocumentId()).isNull();
        assertEligible(NOW, oldId);
    }

    /** 所有发布入口均受保护，不能通过普通发布、编辑元数据或过期版本绕过审核。 */
    @Test
    @DisplayName("候选禁止绕过审核且审核必须使用双方最新版本")
    void guardsPublicationAndVersions() {
        Long nextId = candidate("v2", START, null);
        assertThatThrownBy(() -> approve(nextId)).isInstanceOf(KnowledgeDocumentConflictException.class);
        runNext();
        assertThatThrownBy(() -> publication.publish(baseId, nextId, doc(nextId).getVersion()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> publication.updateMetadata(baseId, nextId,
                new UpdateKnowledgeDocumentMetadataRequest(doc(nextId).getVersion(), "other", "v2", "ALL", START, null)))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> publication.approveRevision(baseId, nextId,
                new ApproveKnowledgeRevisionRequest(doc(nextId).getVersion() - 1, doc(oldId).getVersion())))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> publication.approveRevision(baseId, nextId,
                new ApproveKnowledgeRevisionRequest(doc(nextId).getVersion(), doc(oldId).getVersion() - 1)))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertEligible(NOW, oldId);
    }

    /** 已成功入队时迟到的上传失败补偿不能破坏任务与文档的一致状态。 */
    @Test
    @DisplayName("上传补偿不能覆盖已提交的候选任务")
    void fencesLateUploadCompensation() {
        Long nextId = candidate("v2", START, null);
        revisionPersistence.failUpload(nextId, "DOCUMENT_STATE_UPDATE_FAILED");
        assertThat(doc(nextId).getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
        assertThat(tasks.find(nextId).status()).isEqualTo("QUEUED");
    }

    /** 管理接口同时覆盖 multipart JSON、角色校验、知识库归属和审核响应。 */
    @Test
    @DisplayName("新版上传与审核接口校验权限和知识库归属")
    void exposesAdminRevisionApi() throws Exception {
        String root = "/api/admin/knowledge-bases/" + baseId + "/documents/" + oldId;
        MockMultipartFile metadata = new MockMultipartFile("metadata", "", "application/json",
                json.writeValueAsBytes(request("v2", START, null)));
        mvc.perform(multipart(root + "/revisions").file(file(NEW_TEXT)).file(metadata)
                        .header("Authorization", token(UserRole.CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/knowledge-bases/999999/documents/" + oldId + "/revisions")
                        .file(file(NEW_TEXT)).file(metadata).header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isNotFound());
        String body = mvc.perform(multipart(root + "/revisions").file(file(NEW_TEXT)).file(metadata)
                        .header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.document.predecessorDocumentId").value(oldId))
                .andReturn().getResponse().getContentAsString();
        long nextId = json.readTree(body).at("/data/document/id").asLong();
        mvc.perform(multipart(root + "/revisions").file(file(NEW_TEXT)).file(metadata)
                        .header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("EXISTING"));
        runNext();
        mvc.perform(post("/api/admin/knowledge-bases/" + baseId + "/documents/" + nextId + "/approve-replacement")
                        .header("Authorization", token(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveKnowledgeRevisionRequest(doc(nextId).getVersion(), doc(oldId).getVersion()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.predecessor.successorDocumentId").value(nextId))
                .andExpect(jsonPath("$.data.successor.publicationStatus").value("PUBLISHED"));
    }

    /** 连续修订必须从当前版本继续，不能重新从已经被接替的祖先分叉发布。 */
    @Test
    @DisplayName("连续修订沿明确版本链交接")
    void supportsRevisionChain() {
        Long middle = candidate("v2", START, null);
        runNext();
        approve(middle);
        assertThatThrownBy(() -> candidate("v3", START, null)).isInstanceOf(KnowledgeDocumentConflictException.class);
        setTime(NOW.plusHours(1));
        var next = revisions.upload(baseId, middle, file("上海住宿上限为700元。"),
                new UploadKnowledgeRevisionRequest(doc(middle).getVersion(), "v3", START, null), 1L).document().id();
        runNext();
        publication.approveRevision(baseId, next, new ApproveKnowledgeRevisionRequest(doc(next).getVersion(), doc(middle).getVersion()));
        assertEligible(NOW.plusMinutes(30), middle);
        assertEligible(NOW.plusHours(1), next);
        assertThat(doc(next).getPredecessorDocumentId()).isEqualTo(middle);
        assertThat(doc(oldId).getSuccessorDocumentId()).isEqualTo(middle);
    }

    /** 未审核且已经停止处理的候选允许清理，文件、任务及版本关系一起移除。 */
    @Test
    @DisplayName("失败候选可以删除且旧版不受影响")
    void deletesFailedUnapprovedCandidate() {
        Long nextId = candidate("v2", START, null);
        String key = doc(nextId).getObjectKey();
        tasks.fail(tasks.claimNext(), "DOCUMENT_PARSE_FAILED", false);
        uploads.delete(baseId, nextId);
        assertThat(tasks.find(nextId)).isNull();
        assertThat(objects).doesNotContainKey(key);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE predecessor_document_id = ?", Integer.class, oldId)).isZero();
        assertEligible(NOW, oldId);
    }

    /** 客户端重发成功请求时，即使旧版版本号已因审核改变，也只读取已有候选。 */
    @Test
    @DisplayName("审核后的重复上传仍复用原候选")
    void deduplicatesUploadAfterApproval() {
        var request = request("v2", START, null);
        Long nextId = revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L).document().id();
        runNext();
        approve(nextId);
        var repeated = revisions.upload(baseId, oldId, file(NEW_TEXT), request, 1L);
        assertThat(repeated.outcome()).isEqualTo("EXISTING");
        assertThat(repeated.document().id()).isEqualTo(nextId);
        assertThat(objects).hasSize(2);
        assertThat(tasks.claimNext()).isNull();
    }

    /** 新建候选必须来自当前旧版，过期页面或撤回后的旧版不能继续产生新版本。 */
    @Test
    @DisplayName("过期版本号和已撤回旧版不能创建候选")
    void rejectsStaleOrWithdrawnPredecessor() {
        assertThatThrownBy(() -> revisions.upload(baseId, oldId, file(NEW_TEXT),
                new UploadKnowledgeRevisionRequest(doc(oldId).getVersion() - 1, "v2", START, null), 1L))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        publication.withdraw(baseId, oldId, doc(oldId).getVersion());
        assertThatThrownBy(() -> candidate("v2", START, null)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThat(objects).hasSize(1);
    }

    /** 非法业务版本和日期在创建候选前被拒绝，不留下上传占位记录。 */
    @Test
    @DisplayName("新版接口拒绝非法元数据且不创建候选")
    void validatesRevisionMetadata() throws Exception {
        String url = "/api/admin/knowledge-bases/" + baseId + "/documents/" + oldId + "/revisions";
        for (var request : java.util.List.of(request("INVALID", START, null), request("v2", START, START))) {
            var metadata = new MockMultipartFile("metadata", "", "application/json", json.writeValueAsBytes(request));
            mvc.perform(multipart(url).file(file(NEW_TEXT)).file(metadata).header("Authorization", token(UserRole.ADMIN)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(objects).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE knowledge_base_id = ?", Integer.class, baseId)).isEqualTo(1);
    }

    /** 驱动持久任务执行真实解析、分块与批次提交，只有外部模型和 ES 为替身。 */
    private void runNext() {
        var claim = tasks.claimNext();
        assertThat(claim).isNotNull();
        worker.index(claim);
        assertThat(tasks.find(claim.documentId()).status()).isEqualTo("SUCCEEDED");
    }

    /** 构造 UTF-8 测试文件，保证实际解析内容与摘要一致。 */
    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "policy.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    /** 构造携带当前旧版乐观锁版本的新版元数据。 */
    private UploadKnowledgeRevisionRequest request(String version, LocalDateTime from, LocalDateTime until) {
        return new UploadKnowledgeRevisionRequest(doc(oldId).getVersion(), version, from, until);
    }

    /** 创建不同业务版本的候选但不执行后台任务。 */
    private Long candidate(String version, LocalDateTime from, LocalDateTime until) {
        return revisions.upload(baseId, oldId, file(NEW_TEXT), request(version, from, until), 1L).document().id();
    }

    /** 从数据库读取当前文档，避免用测试对象掩盖事务或乐观锁问题。 */
    private KnowledgeDocument doc(Long id) {
        return documents.get(baseId, id);
    }

    /** 使用双方最新版本审核候选。 */
    private KnowledgeRevisionApprovalResponse approve(Long id) {
        return publication.approveRevision(baseId, id,
                new ApproveKnowledgeRevisionRequest(doc(id).getVersion(), doc(oldId).getVersion()));
    }

    /** 将业务冲突转换为并发测试结果，其他异常仍使测试失败。 */
    private boolean tryApprove(Long id, ApproveKnowledgeRevisionRequest request) {
        try {
            publication.approveRevision(baseId, id, request);
            return true;
        } catch (KnowledgeDocumentConflictException exception) {
            return false;
        }
    }

    /** 在任意边界时刻检查真实数据库准入结果。 */
    private void assertEligible(LocalDateTime at, Long id) {
        assertThat(eligibility.findEligible(baseId, "CN_SH", at).keySet()).containsExactly(id);
    }

    /** 固定业务时钟以确定性验证交接与撤回，不改变索引任务的数据库租约时钟。 */
    private void setTime(LocalDateTime at) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        when(knowledgeClock.getZone()).thenReturn(zone);
        when(knowledgeClock.instant()).thenReturn(at.atZone(zone).toInstant());
    }

    /** 签发角色测试令牌，不依赖本地测试账号或真实密码。 */
    private String token(UserRole role) {
        User user = new User();
        user.setId(1L);
        user.setUsername("revision-test");
        user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
