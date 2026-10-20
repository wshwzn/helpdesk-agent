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
import com.anying.helpdesk.knowledge.source.*;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import com.anying.helpdesk.security.JwtTokenService;
import com.anying.helpdesk.storage.ObjectStorageService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:oss_sources;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeSourceIntegrationTest {
    @Autowired private KnowledgeBaseService bases;
    @Autowired private KnowledgeSourceStore sources;
    @Autowired private KnowledgeSourceSyncService sync;
    @Autowired private KnowledgeSourceImportService imports;
    @Autowired private KnowledgeDocumentPersistenceService documents;
    @Autowired private KnowledgeDocumentService uploads;
    @Autowired private KnowledgeDocumentRevisionService revisions;
    @Autowired private KnowledgeDocumentPublicationService publication;
    @Autowired private KnowledgeDocumentEligibilityService eligibility;
    @Autowired private KnowledgeDocumentIndexWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @MockitoSpyBean private KnowledgeIndexTaskStore tasks;
    @MockitoBean private ObjectStorageService storage;
    @MockitoBean private OssSourceGateway gateway;
    @MockitoBean private DefaultEmbeddingModelProvider models;
    @MockitoBean private KnowledgeChunkStore chunks;
    private final Map<String, byte[]> snapshots = new ConcurrentHashMap<>();
    private final Map<String, byte[]> external = new TreeMap<>();
    private final Map<String, OssSourceObject> heads = new HashMap<>();
    private Long baseId;
    private Long sourceId;
    private String prefix;
    private String key;
    private long stamp;

    /** 使用真实数据库事务、索引任务和解析器，OSS 与模型/ES 使用可控替身。 */
    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("INSERT INTO users(id, username, password_hash, display_name, role) SELECT 1, 'source-test', 'unused', '来源测试', 'ADMIN' WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 1)");
        when(storage.provider()).thenReturn("aliyun-oss");
        when(storage.bucketName()).thenReturn("source-tests");
        doAnswer(call -> {
            snapshots.put(call.getArgument(0), call.<InputStream>getArgument(1).readAllBytes());
            return null;
        }).when(storage).upload(anyString(), any(), anyLong(), anyString());
        when(storage.download(anyString(), anyString())).thenAnswer(call ->
                new ByteArrayInputStream(snapshots.get(call.<String>getArgument(1))));
        when(gateway.list(anyString(), anyInt())).thenAnswer(call -> new ArrayList<>(external.keySet()));
        when(gateway.head(anyString())).thenAnswer(call -> heads.get(call.<String>getArgument(0)));
        when(gateway.read(anyString(), any())).thenAnswer(call -> external.get(call.<String>getArgument(0)));
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(call -> call.<List<String>>getArgument(0).stream().map(text -> new float[]{1, 0, 0}).toList());
        when(models.get()).thenReturn(new ResolvedEmbeddingModel(1L, "test", 3, model));
        baseId = bases.create(new CreateKnowledgeBaseRequest("来源-" + UUID.randomUUID(), "测试"), 1L).id();
        prefix = "external-knowledge/test-" + UUID.randomUUID() + "/";
        sourceId = sources.create(baseId, prefix, 1L).id();
        key = prefix + "policy.md";
        put(key, "v1", "上海住宿上限为500元。", "travel");
    }

    /** 只清理当前用例的映射和文档，先解除循环外键再删除测试知识库。 */
    @AfterEach
    void cleanup() {
        jdbc.update("UPDATE knowledge_source_items SET last_document_id = NULL WHERE source_id = ?", sourceId);
        jdbc.update("UPDATE knowledge_documents SET predecessor_document_id = NULL, successor_document_id = NULL, source_item_id = NULL WHERE knowledge_base_id = ?", baseId);
        jdbc.update("DELETE FROM knowledge_documents WHERE knowledge_base_id = ?", baseId);
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", baseId);
    }

    /** 重复扫描不能重复下载、上传快照或创建索引任务。 */
    @Test @DisplayName("首次导入自动入队且未变扫描不重复处理")
    void importsAndSkipsUnchanged() {
        assertThat(scan().addedCount()).isEqualTo(1);
        var first = document();
        assertThat(first.getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
        assertThat(first.getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.DRAFT);
        assertThat(first.getObjectKey()).startsWith("knowledge-bases/").isNotEqualTo(key);
        assertThat(first.getSourceItemId()).isEqualTo(item().id());
        assertThat(scan().unchangedCount()).isEqualTo(1);
        assertThat(document().getId()).isEqualTo(first.getId());
        verify(gateway, times(1)).read(anyString(), any());
        assertThat(snapshots).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_tasks", Integer.class)).isEqualTo(1);
        runNext();
        assertThat(scan().unchangedCount()).isEqualTo(1);
        assertThat(tasks.claimNext()).isNull();
    }

    /** 新版准备不影响旧版，审核之后才切换数据库准入集合。 */
    @Test @DisplayName("修改进入候选并复用索引与审核交接")
    void updatesOnlyAfterApproval() {
        long old = initialPublished();
        put(key, "v2", "上海住宿上限为600元。", "travel");
        assertThat(scan().updatedCount()).isEqualTo(1);
        var candidate = document();
        assertThat(candidate.getPredecessorDocumentId()).isEqualTo(old);
        assertEligible(old);
        runNext();
        publication.approveRevision(baseId, candidate.getId(), new ApproveKnowledgeRevisionRequest(document().getVersion(), doc(old).getVersion()));
        assertEligible(candidate.getId());
        assertThat(snapshots).hasSize(2);
    }

    /** 同一业务版本内容被覆盖属于业务冲突，不应静默重新发布。 */
    @Test @DisplayName("内容变化却未修改业务版本时保留旧版并报告冲突")
    void rejectsVersionOverwrite() {
        long old = initialPublished();
        put(key, "v1", "上海住宿上限为900元。", "travel");
        assertThat(scan().failedCount()).isEqualTo(1);
        assertThat(item().errorCode()).isEqualTo("SOURCE_VERSION_CONFLICT");
        assertThat(document().getId()).isEqualTo(old);
        assertEligible(old);
        assertThat(snapshots).hasSize(1);
        assertThat(scan().failedCount()).isEqualTo(1);
        assertThat(snapshots).hasSize(1);
    }

    /** 同字节重传可能改变 OSS 修改时间，最终仍按内容和业务元数据去重。 */
    @Test @DisplayName("OSS 指纹变化但实际内容与业务元数据未变时不重建")
    void contentDeduplicatesAfterHeadChange() {
        initialPublished();
        put(key, "v1", "上海住宿上限为500元。", "travel");
        assertThat(scan().unchangedCount()).isEqualTo(1);
        assertThat(snapshots).hasSize(1);
        assertThat(tasks.claimNext()).isNull();
        verify(gateway, times(2)).read(anyString(), any());
    }

    /** 业务版本变化即使没有字节变化仍生成明确版本记录。 */
    @Test @DisplayName("仅业务元数据变化也能产生新版")
    void handlesMetadataOnlyChange() {
        long old = initialPublished();
        put(key, "v2", "上海住宿上限为500元。", "travel");
        assertThat(scan().updatedCount()).isEqualTo(1);
        assertThat(document().getPredecessorDocumentId()).isEqualTo(old);
        assertThat(document().getSha256()).isEqualTo(doc(old).getSha256());
    }

    /** 删除只产生核查信号，重新出现复用同一来源对象与已有文档。 */
    @Test @DisplayName("完整扫描标记缺失但不撤回且重现不重复导入")
    void marksMissingWithoutWithdrawing() {
        long old = initialPublished();
        var bytes = external.remove(key);
        assertThat(scan().missingCount()).isEqualTo(1);
        assertThat(item().itemStatus()).isEqualTo("MISSING");
        assertEligible(old);
        external.put(key, bytes);
        assertThat(scan().unchangedCount()).isEqualTo(1);
        assertThat(item().itemStatus()).isEqualTo("SYNCED");
        assertThat(snapshots).hasSize(1);
    }

    /** 列表失败不能把不可见结果解释为删除。 */
    @Test @DisplayName("分页失败或超限失败不执行缺失推断")
    void failedListingDoesNotInferDeletion() {
        initialPublished();
        when(gateway.list(anyString(), anyInt())).thenThrow(new KnowledgeStorageException("listing failed"));
        assertThatThrownBy(this::scan).isInstanceOf(KnowledgeStorageException.class);
        assertThat(sources.get(baseId, sourceId).scanStatus()).isEqualTo("FAILED");
        assertThat(item().itemStatus()).isEqualTo("SYNCED");
    }

    /** 保守策略要求所有文件检查都成功，单项错误时不新增缺失标记。 */
    @Test @DisplayName("非法元数据使扫描部分失败并保留未见对象状态")
    void partialScanDoesNotInferDeletion() {
        initialPublished();
        external.remove(key);
        String invalid = prefix + "invalid.md";
        put(invalid, "v1", "新文件", "invalid");
        heads.put(invalid, new OssSourceObject("test", 3, 1, Map.of()));
        assertThat(scan().scanStatus()).isEqualTo("PARTIAL");
        assertThat(item(key).itemStatus()).isEqualTo("SYNCED");
        assertThat(item(invalid).errorCode()).isEqualTo("SOURCE_METADATA_OR_FILE_INVALID");
    }

    /** 下载竞争失败既不创建候选，也不影响旧知识资格。 */
    @Test @DisplayName("对象在读取期间变化时拒绝不一致快照")
    void changingObjectCannotCreateCandidate() {
        long old = initialPublished();
        put(key, "v2", "新文件", "travel");
        when(gateway.read(anyString(), any())).thenThrow(new KnowledgeStorageException("changed"));
        assertThat(scan().failedCount()).isEqualTo(1);
        assertThat(document().getId()).isEqualTo(old);
        assertEligible(old);
    }

    /** 外部存储故障不推进接受指纹，恢复后相同来源变更可正常重试。 */
    @Test @DisplayName("快照上传失败可在下一轮恢复")
    void retriesSnapshotFailure() {
        doThrow(new KnowledgeStorageException("unavailable")).when(storage).upload(anyString(), any(), anyLong(), anyString());
        assertThat(scan().failedCount()).isEqualTo(1);
        assertThat(item().lastDocumentId()).isNull();
        assertThat(item().acceptedFingerprint()).isNull();
        doAnswer(call -> { snapshots.put(call.getArgument(0), call.<InputStream>getArgument(1).readAllBytes()); return null; })
                .when(storage).upload(anyString(), any(), anyLong(), anyString());
        assertThat(scan().addedCount()).isEqualTo(1);
    }

    /** 任务创建失败必须同时回滚文档和来源指针，不能留下半提交业务状态。 */
    @Test @DisplayName("任务入库失败回滚候选与映射")
    void rollsBackDocumentAndMapping() {
        KnowledgeIndexTaskStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(tasks);
        doThrow(new IllegalStateException("task failed")).when(target).enqueue(anyLong());
        assertThat(scan().failedCount()).isEqualTo(1);
        assertThat(item().lastDocumentId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE knowledge_base_id = ?", Integer.class, baseId)).isZero();
        doCallRealMethod().when(target).enqueue(anyLong());
        assertThat(scan().addedCount()).isEqualTo(1);
    }

    /** 新执行者接管后旧执行者的完成与错误补偿均不能覆盖状态。 */
    @Test @DisplayName("过期租约可接管且旧令牌不能写入")
    void fencesExpiredWorker() {
        var old = sources.claim(baseId, sourceId);
        jdbc.update("UPDATE knowledge_sources SET lease_until = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), sourceId);
        var current = sources.claim(baseId, sourceId);
        assertThatThrownBy(() -> sources.seen(old, key)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> sources.renew(old)).isInstanceOf(KnowledgeDocumentConflictException.class);
        sources.fail(old);
        assertThat(sources.get(baseId, sourceId).scanStatus()).isEqualTo("RUNNING");
        sources.finish(current, 0, 0, 0, 0);
    }

    /** 两个同时开始的扫描只有一个可以持有来源租约。 */
    @Test @DisplayName("并发领取同一来源只有一个成功")
    void claimsOnceAcrossWorkers() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tryClaim(start));
            var second = pool.submit(() -> tryClaim(start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    /** 暂停需立即使旧租约失效，恢复后可重新扫描。 */
    @Test @DisplayName("暂停会取消活动扫描且恢复后可继续")
    void pausesAndResumes() {
        var claim = sources.claim(baseId, sourceId);
        sources.setEnabled(baseId, sourceId, false);
        assertThat(sources.get(baseId, sourceId).errorCode()).isEqualTo("SOURCE_SCAN_CANCELLED");
        assertThatThrownBy(() -> sources.seen(claim, key)).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(this::scan).isInstanceOf(KnowledgeDocumentConflictException.class);
        sources.setEnabled(baseId, sourceId, true);
        assertThat(scan().addedCount()).isEqualTo(1);
    }

    /** 普通上传的待处理资料没有来源登记，不能被来源任务接管。 */
    @Test @DisplayName("来源扫描不处理普通 PENDING 文档")
    void leavesInternalDraftAlone() {
        var manual = uploads.upload(baseId, file("普通草稿"), 1L);
        scan();
        assertThat(doc(manual.id()).getStatus()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(tasks.find(manual.id())).isNull();
    }

    /** 外部资料只能在来源维护内容与元数据，索引重试和审核仍走内部流程。 */
    @Test @DisplayName("禁止通过内部接口修改或物理删除外部托管资料")
    void enforcesSingleEditingEntry() {
        initialPublished();
        var document = document();
        assertThatThrownBy(() -> revisions.upload(baseId, document.getId(), file("手工内容"),
                new UploadKnowledgeRevisionRequest(document.getVersion(), "v2", LocalDateTime.of(2020, 1, 1, 0, 0), null), 1L))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        publication.withdraw(baseId, document.getId(), document.getVersion());
        assertThatThrownBy(() -> uploads.delete(baseId, document.getId())).isInstanceOf(KnowledgeDocumentConflictException.class);
        verify(storage, never()).delete(anyString(), anyString());
    }

    /** 候选拒绝保留审计记录，新版本仍接替原来的在用资料。 */
    @Test @DisplayName("待审候选阻止连续覆盖，拒绝后新版本可再次进入审核")
    void rejectsCandidateBeforeNextVersion() {
        long old = initialPublished();
        put(key, "v2", "新版二", "travel");
        scan();
        long rejected = document().getId();
        put(key, "v3", "新版三", "travel");
        assertThat(scan().failedCount()).isEqualTo(1);
        runNext();
        imports.reject(baseId, sourceId, item().id(), document().getVersion());
        assertThat(doc(rejected).getPublicationStatus()).isEqualTo(KnowledgePublicationStatus.WITHDRAWN);
        assertThat(scan().updatedCount()).isEqualTo(1);
        assertThat(document().getBusinessVersion()).isEqualTo("v3");
        assertThat(document().getPredecessorDocumentId()).isEqualTo(old);
    }

    /** 未来新版已审核时不能继续自动创建其他交接链。 */
    @Test @DisplayName("未来交接期间扫描保持旧版可用且阻止后续覆盖")
    void respectsFutureHandoff() {
        long old = initialPublished();
        put(key, "v2", "未来新版", "travel");
        var metadata = new HashMap<>(heads.get(key).metadata());
        metadata.put("kb-valid-from", LocalDateTime.now().plusDays(2).withNano(0).toString());
        var head = heads.get(key);
        heads.put(key, new OssSourceObject(head.etag(), head.size(), head.modifiedAt(), metadata));
        scan();
        runNext();
        publication.approveRevision(baseId, document().getId(), new ApproveKnowledgeRevisionRequest(document().getVersion(), doc(old).getVersion()));
        put(key, "v3", "再次更新", "travel");
        assertThat(scan().failedCount()).isEqualTo(1);
        assertEligible(old);
    }

    /** 读取来源接口要求管理员且始终检查知识库归属。 */
    @Test @DisplayName("来源接口校验角色、前缀、分页及知识库归属")
    void protectsSourceApi() throws Exception {
        String path = "/api/admin/knowledge-bases/" + baseId + "/sources";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token(UserRole.CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", token(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectPrefix\":\"knowledge-bases/1/\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/" + sourceId + "/scan").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.addedCount").value(1));
        mvc.perform(get(path + "/" + sourceId + "/items").param("size", "101").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/knowledge-bases/999999/sources/" + sourceId).header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    /** 配置变化不能让同一个来源开始读取另一个 Bucket 并错误推断缺失。 */
    @Test @DisplayName("配置 Bucket 改变时整轮失败且不标记缺失")
    void rejectsBucketDrift() {
        initialPublished();
        when(storage.bucketName()).thenReturn("another-bucket");
        assertThatThrownBy(this::scan).isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThat(item().itemStatus()).isEqualTo("SYNCED");
    }

    /** 重用业务身份或把同路径改为其他制度均需人工处理。 */
    @Test @DisplayName("重命名或来源身份变化不能自动形成重复制度")
    void rejectsIdentityDriftAndMove() {
        long old = initialPublished();
        put(key, "v2", "其他制度", "another-policy");
        assertThat(scan().failedCount()).isEqualTo(1);
        external.remove(key);
        put(prefix + "moved.md", "v2", "移动后", "travel");
        assertThat(scan().failedCount()).isEqualTo(1);
        assertEligible(old);
        assertThat(snapshots).hasSize(1);
    }

    /** 模拟外部维护者同时更新对象和显式业务元数据。 */
    private void put(String objectKey, String version, String text, String identity) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        external.put(objectKey, bytes);
        heads.put(objectKey, new OssSourceObject(OssSourceObject.hash(text), bytes.length, ++stamp,
                Map.of("kb-source-key", identity, "kb-business-version", version, "kb-scope", "CN_SH", "kb-valid-from", "2020-01-01T00:00:00")));
    }

    /** 执行当前测试来源的扫描。 */
    private KnowledgeSource scan() { return sync.scan(baseId, sourceId); }

    /** 查询默认测试对象的同步结果。 */
    private KnowledgeSourceItem item() { return item(key); }

    /** 按原始对象键选择同步记录，避免依赖结果排序。 */
    private KnowledgeSourceItem item(String objectKey) {
        return sources.items(baseId, sourceId, 1, 100).records().stream().filter(value -> value.objectKey().equals(objectKey)).findFirst().orElseThrow();
    }

    /** 返回最近导入的文档实体。 */
    private KnowledgeDocument document() { return doc(item().lastDocumentId()); }

    /** 从数据库读取文档，避免旧测试对象掩盖版本变化。 */
    private KnowledgeDocument doc(Long id) { return documents.get(baseId, id); }

    /** 完成首份资料的导入、真实解析及发布。 */
    private long initialPublished() {
        assertThat(scan().addedCount()).isEqualTo(1);
        runNext();
        var document = document();
        publication.publish(baseId, document.getId(), document.getVersion());
        return document.getId();
    }

    /** 驱动真实索引工作者，仅模型和 ES 网络依赖使用替身。 */
    private void runNext() {
        var claim = tasks.claimNext();
        assertThat(claim).isNotNull();
        worker.index(claim);
        assertThat(tasks.find(claim.documentId()).status()).isEqualTo("SUCCEEDED");
    }

    /** 验证数据库资格过滤仍只返回预期的在用版本。 */
    private void assertEligible(Long id) { assertThat(eligibility.findEligible(baseId, "CN_SH", LocalDateTime.now()).keySet()).containsExactly(id); }

    /** 构造普通上传以验证与外部同步的边界。 */
    private MockMultipartFile file(String text) { return new MockMultipartFile("file", "manual.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)); }

    /** 在统一起点尝试领取，业务竞争失败转换为测试结果。 */
    private boolean tryClaim(CountDownLatch start) throws InterruptedException {
        start.await();
        try { sources.claim(baseId, sourceId); return true; }
        catch (KnowledgeDocumentConflictException exception) { return false; }
    }

    /** 签发角色令牌，不在测试中存储真实账号密码。 */
    private String token(UserRole role) {
        User user = new User();
        user.setId(1L);
        user.setUsername("source-test");
        user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
