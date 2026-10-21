package com.anying.helpdesk.knowledge;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentPersistenceService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentPublicationService;
import com.anying.helpdesk.security.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KnowledgeDocumentPublicationIntegrationTest {

    private static final LocalDateTime SWITCH_AT = LocalDateTime.of(2026, 10, 1, 0, 0);
    @Autowired private KnowledgeBaseService baseService;
    @Autowired private KnowledgeDocumentPersistenceService persistence;
    @Autowired private KnowledgeDocumentPublicationService publication;
    @Autowired private KnowledgeDocumentEligibilityService eligibility;
    @Autowired private KnowledgeDocumentMapper documentMapper;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwt;
    private Long baseId;

    /**
     * 为各用例创建独立知识库，所有数据由测试事务回滚。
     */
    @BeforeEach
    void prepareKnowledgeBase() {
        baseId = baseService.create(new CreateKnowledgeBaseRequest("差旅-" + UUID.randomUUID(), "虚构测试资料"), 1L).id();
    }

    /**
     * 验证旧版在截止时刻退出，新版在同一时刻生效，未来和草稿不混入召回。
     */
    @Test
    @DisplayName("半开有效期支持新旧业务版本在同一时刻交接")
    void switchesBusinessVersionsAtExactBoundary() {
        KnowledgeDocumentResponse old = ready("travel", "v1", "ALL", SWITCH_AT.minusYears(1), SWITCH_AT);
        KnowledgeDocumentResponse next = ready("travel", "v2", "ALL", SWITCH_AT, null);
        KnowledgeDocumentResponse future = ready("travel", "v3", "ALL", SWITCH_AT.plusMonths(1), null);
        ready("draft", "v1", "ALL", SWITCH_AT.minusYears(1), null);
        publish(old);
        publish(next);
        publish(future);
        assertThat(eligibility.findEligible(baseId, "ALL", SWITCH_AT.minusNanos(1000)).keySet()).containsExactly(old.id());
        assertThat(eligibility.findEligible(baseId, "ALL", SWITCH_AT).keySet()).containsExactly(next.id());
    }

    /**
     * 验证明确范围只包含自身和通用规则，未知范围不会退化为检索全部文档。
     */
    @Test
    @DisplayName("范围隔离与默认通用检索不会混用其他地区制度")
    void filtersScopeAndKnowledgeBase() {
        KnowledgeDocumentResponse general = publish(ready("general", "v1", "ALL", SWITCH_AT, null));
        KnowledgeDocumentResponse shanghai = publish(ready("travel", "v1", "CN_SH", SWITCH_AT, null));
        publish(ready("travel", "v1", "CN_BJ", SWITCH_AT, null));
        Long original = baseId;
        baseId = baseService.create(new CreateKnowledgeBaseRequest("另一个库-" + UUID.randomUUID(), "隔离测试"), 1L).id();
        publish(ready("travel", "v1", "ALL", SWITCH_AT, null));
        assertThat(eligibility.findEligible(original, "ALL", SWITCH_AT).keySet()).containsExactly(general.id());
        assertThat(eligibility.findEligible(original, "CN_SH", SWITCH_AT).keySet()).containsExactly(general.id(), shanghai.id());
        assertThat(eligibility.findEligible(original, "UNKNOWN", SWITCH_AT).keySet()).containsExactly(general.id());
    }

    /** 联合范围只允许请求的地区和通用资料，仍过滤草稿、未来、撤回以及其他知识库。 */
    @Test @DisplayName("多范围准入并集仍遵守发布有效期和地区边界")
    void unionScopesKeepEligibilityBoundaries() {
        var all = publish(ready("general", "v1", "ALL", SWITCH_AT, null));
        var sh = publish(ready("travel", "v1", "CN_SH", SWITCH_AT, null));
        var bj = publish(ready("travel", "v1", "CN_BJ", SWITCH_AT, null));
        publish(ready("travel", "v1", "CN_GZ", SWITCH_AT, null));
        publish(ready("future", "v1", "CN_SH", SWITCH_AT.plusDays(1), null));
        ready("draft", "v1", "CN_BJ", SWITCH_AT, null);
        var withdrawn = publish(ready("withdrawn", "v1", "CN_BJ", SWITCH_AT, null));
        publication.withdraw(baseId, withdrawn.id(), withdrawn.version());
        assertThat(eligibility.findEligibleScopes(baseId, java.util.List.of("CN_SH", "CN_BJ"), SWITCH_AT).keySet())
                .containsExactly(all.id(), sh.id(), bj.id());
    }

    /**
     * 验证撤回和索引失败都能让残留 ES 片段失去准入资格。
     */
    @Test
    @DisplayName("撤回和索引未就绪文档不进入可检索集合")
    void excludesWithdrawnAndFailedDocuments() {
        KnowledgeDocumentResponse withdrawn = publish(ready("withdrawn", "v1", "ALL", SWITCH_AT, null));
        publication.withdraw(baseId, withdrawn.id(), withdrawn.version());
        KnowledgeDocumentResponse failed = publish(ready("failed", "v1", "ALL", SWITCH_AT, null));
        KnowledgeDocument entity = documentMapper.selectById(failed.id());
        entity.setStatus(KnowledgeDocumentStatus.FAILED);
        documentMapper.updateById(entity);
        assertThat(eligibility.findEligible(baseId, "ALL", SWITCH_AT)).isEmpty();
    }

    /**
     * 验证同时有效的不同版本都保留给后续冲突判断，不按版本标签替用户作决定。
     */
    @Test
    @DisplayName("有效版本重叠时保留双方候选")
    void preservesOverlappingVersions() {
        KnowledgeDocumentResponse first = publish(ready("travel", "v2", "ALL", SWITCH_AT, null));
        KnowledgeDocumentResponse second = publish(ready("travel", "v10", "ALL", SWITCH_AT, null));
        assertThat(eligibility.findEligible(baseId, "ALL", SWITCH_AT).keySet()).containsExactly(first.id(), second.id());
    }

    /**
     * 验证草稿允许清空截止时间，但已发布记录不能编辑或直接重新索引。
     */
    @Test
    @DisplayName("已发布业务版本不可变且旧乐观锁版本被拒绝")
    void protectsPublishedVersionAndClearsNullableDeadline() {
        KnowledgeDocumentResponse draft = ready("travel", "v1", "ALL", SWITCH_AT, SWITCH_AT.plusDays(1));
        KnowledgeDocumentResponse edited = publication.updateMetadata(baseId, draft.id(),
                metadata(draft.version(), "travel", "v1", "ALL", SWITCH_AT, null));
        assertThat(edited.validUntil()).isNull();
        assertThat(edited.status()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThatThrownBy(() -> publication.publish(baseId, draft.id(), draft.version()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> publication.publish(baseId, edited.id(), edited.version()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        makeReady(edited.id());
        KnowledgeDocumentResponse published = publish(KnowledgeDocumentResponse.from(persistence.get(baseId, edited.id())));
        assertThatThrownBy(() -> publication.updateMetadata(baseId, published.id(),
                metadata(published.version(), "travel", "v2", "ALL", SWITCH_AT, null)))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
        assertThatThrownBy(() -> persistence.markProcessing(baseId, published.id()))
                .isInstanceOf(KnowledgeDocumentConflictException.class);
    }

    /**
     * 通过真实控制器验证元数据配置、发布、撤回和旧版本保护。
     */
    @Test
    @DisplayName("管理员可配置发布撤回且错误版本返回冲突")
    void managesPublicationThroughHttp() throws Exception {
        Long id = draft();
        String path = "/api/admin/knowledge-bases/" + baseId + "/documents/" + id;
        Integer version = persistence.get(baseId, id).getVersion();
        mvc.perform(put(path + "/metadata").header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(metadata(version, "travel", "v1", "CN_SH", SWITCH_AT, null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.businessVersion").value("v1"));
        makeReady(id);
        Integer readyVersion = persistence.get(baseId, id).getVersion();
        mvc.perform(post(path + "/publish").header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + readyVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("PUBLISHED"));
        mvc.perform(post(path + "/withdraw").header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + readyVersion + "}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path + "/withdraw").header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + (readyVersion + 1) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("WITHDRAWN"));
    }

    /**
     * 验证身份、归属、日期和唯一性校验均在业务入口阻止非法配置。
     */
    @Test
    @DisplayName("元数据接口校验权限归属日期和重复版本")
    void validatesMetadataRequests() throws Exception {
        ready("travel", "v1", "ALL", SWITCH_AT, null);
        Long id = draft();
        Integer version = persistence.get(baseId, id).getVersion();
        String path = "/api/admin/knowledge-bases/" + baseId + "/documents/" + id + "/metadata";
        String body = objectMapper.writeValueAsString(metadata(version, "travel", "v1", "ALL", SWITCH_AT, null));
        mvc.perform(put(path).header("Authorization", token(UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).header("Authorization", token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(put(path).header("Authorization", token(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(metadata(version, "other", "v1", "ALL", SWITCH_AT, SWITCH_AT))))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path.replace("/" + baseId + "/documents", "/999999/documents"))
                        .header("Authorization", token(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    /**
     * 验证 V15 历史就绪文档升级后保留文件数据，但不能未经业务审核自动发布。
     */
    @Test
    @DisplayName("迁移历史 READY 文档为未配置元数据的草稿")
    void migratesExistingDocumentsWithoutAutoPublishing() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:publication_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("15").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO knowledge_documents
                (knowledge_base_id, original_name, storage_provider, bucket_name, object_key,
                 content_type, file_extension, size_bytes, sha256, status, chunk_count, created_by)
                VALUES (1, 'legacy.md', 'local', 'local', 'legacy-object', 'text/markdown', 'md', 10, ?, 'READY', 1, 1)
                """, "0".repeat(64));
        Flyway.configure().dataSource(dataSource).load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM knowledge_documents WHERE object_key = 'legacy-object'");
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("publication_status")).isEqualTo("DRAFT");
        assertThat(row.get("source_key")).isNull();
        assertThat(row.get("applicability_scope")).isEqualTo("ALL");
    }

    /**
     * 构造上传完成、尚未配置业务元数据的草稿。
     */
    private Long draft() {
        KnowledgeDocument document = persistence.createUploading(baseId, "差旅制度.md", "local", "local",
                "publication-test/" + UUID.randomUUID(), "text/markdown", "md", 10, "0".repeat(64), 1L);
        persistence.markPending(document.getId());
        return document.getId();
    }

    /**
     * 创建业务元数据已配置且索引就绪的草稿，外部解析及向量化由其他测试覆盖。
     */
    private KnowledgeDocumentResponse ready(String source, String version, String scope, LocalDateTime from, LocalDateTime until) {
        Long id = draft();
        publication.updateMetadata(baseId, id, metadata(persistence.get(baseId, id).getVersion(), source, version, scope, from, until));
        makeReady(id);
        return KnowledgeDocumentResponse.from(persistence.get(baseId, id));
    }

    /**
     * 模拟索引工作器成功完成的一次状态推进。
     */
    private void makeReady(Long id) {
        persistence.markProcessing(baseId, id);
        persistence.markReady(id, 1);
    }

    /**
     * 使用最新乐观锁版本发布测试文档。
     */
    private KnowledgeDocumentResponse publish(KnowledgeDocumentResponse document) {
        return publication.publish(baseId, document.id(), document.version());
    }

    /**
     * 统一构造元数据配置请求以突出每个用例的业务差异。
     */
    private UpdateKnowledgeDocumentMetadataRequest metadata(Integer version, String source, String businessVersion,
                                                            String scope, LocalDateTime from, LocalDateTime until) {
        return new UpdateKnowledgeDocumentMetadataRequest(version, source, businessVersion, scope, from, until);
    }

    /**
     * 签发测试角色的真实 JWT，验证控制器的角色限制。
     */
    private String token(UserRole role) {
        User user = new User();
        user.setId(1L);
        user.setUsername("publication-test");
        user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
