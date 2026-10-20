package com.anying.helpdesk.knowledge.source;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeSourceStore {
    private final JdbcTemplate jdbc;
    private final KnowledgeBaseService bases;
    private final ObjectStorageService storage;
    private final KnowledgeSourceProperties properties;

    private static final RowMapper<KnowledgeSource> SOURCE = (rs, row) -> new KnowledgeSource(
            rs.getLong("id"), rs.getLong("knowledge_base_id"), rs.getString("bucket_name"),
            rs.getString("object_prefix"), rs.getBoolean("enabled"), rs.getLong("created_by"),
            rs.getString("scan_status"), rs.getObject("lease_until", LocalDateTime.class),
            rs.getObject("last_scan_at", LocalDateTime.class), rs.getString("error_code"),
            rs.getInt("added_count"), rs.getInt("updated_count"), rs.getInt("unchanged_count"),
            rs.getInt("failed_count"), rs.getInt("missing_count"));
    private static final RowMapper<KnowledgeSourceItem> ITEM = (rs, row) -> new KnowledgeSourceItem(
            rs.getLong("id"), rs.getLong("source_id"), rs.getString("object_key"), rs.getString("accepted_fingerprint"),
            rs.getObject("last_document_id", Long.class), rs.getString("item_status"), rs.getString("error_code"),
            rs.getObject("updated_at", LocalDateTime.class));

    /** 注册一个专属顶层目录，固定当前 OSS Bucket，避免嵌套扫描和扫描知识库自身快照。 */
    @Transactional
    public KnowledgeSource create(Long baseId, String prefix, Long adminId) {
        bases.getById(baseId);
        if (!"aliyun-oss".equals(storage.provider())) {
            throw new KnowledgeDocumentConflictException("OSS sources require aliyun-oss storage");
        }
        if (prefix == null || !prefix.matches("external-knowledge/[a-z0-9][a-z0-9_-]{0,79}/")) {
            throw new InvalidKnowledgeDocumentException("prefix must be external-knowledge/{lowercase-slug}/");
        }
        try {
            long id = insert("INSERT INTO knowledge_sources(knowledge_base_id, bucket_name, object_prefix, created_by) VALUES (?, ?, ?, ?)",
                    baseId, storage.bucketName(), prefix, adminId);
            return get(baseId, id);
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeDocumentConflictException("OSS prefix is already managed by a knowledge source");
        }
    }

    /** 按知识库读取来源，跨知识库请求不能取得或操作其他来源。 */
    public KnowledgeSource get(Long baseId, Long id) {
        return jdbc.query("SELECT * FROM knowledge_sources WHERE id = ? AND knowledge_base_id = ?", SOURCE, id, baseId)
                .stream().findFirst().orElseThrow(() -> new InvalidKnowledgeDocumentException("knowledge source does not exist in this base"));
    }

    /** 查询知识库已登记的来源和最近一次扫描汇总。 */
    public List<KnowledgeSource> list(Long baseId) {
        bases.getById(baseId);
        return jdbc.query("SELECT * FROM knowledge_sources WHERE knowledge_base_id = ? ORDER BY id", SOURCE, baseId);
    }

    /** 定时任务只读取显式启用的来源，绝不扫描普通待索引文档。 */
    public List<KnowledgeSource> enabled() {
        return jdbc.query("SELECT * FROM knowledge_sources WHERE enabled = TRUE ORDER BY last_scan_at, id LIMIT 100", SOURCE);
    }

    /** 暂停时同时作废扫描租约，正在外部下载的旧执行者随后无法提交。 */
    @Transactional
    public KnowledgeSource setEnabled(Long baseId, Long id, boolean enabled) {
        get(baseId, id);
        jdbc.update("""
                UPDATE knowledge_sources SET enabled = ?, run_token = NULL, lease_until = NULL,
                    error_code = CASE WHEN scan_status = 'RUNNING' THEN 'SOURCE_SCAN_CANCELLED' ELSE error_code END,
                    scan_status = CASE WHEN scan_status = 'RUNNING' THEN 'FAILED' ELSE scan_status END
                WHERE id = ?
                """, enabled, id);
        return get(baseId, id);
    }

    /** 用数据库时间取得扫描租约，过期扫描可被其他实例接管。 */
    @Transactional
    public KnowledgeSourceClaim claim(Long baseId, Long id) {
        get(baseId, id);
        var now = now();
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
                UPDATE knowledge_sources SET scan_status = 'RUNNING', run_token = ?, lease_until = ?,
                    error_code = NULL, added_count = 0, updated_count = 0, unchanged_count = 0,
                    failed_count = 0, missing_count = 0
                WHERE id = ? AND enabled = TRUE AND (lease_until IS NULL OR lease_until <= ?)
                """, token, now.plusSeconds(properties.getLeaseSeconds()), id, now);
        if (changed != 1) throw new KnowledgeDocumentConflictException("source is paused or already being scanned");
        return new KnowledgeSourceClaim(get(baseId, id), token);
    }

    /** 外部调用间续租，过期执行者不能重新获取原租约。 */
    public void renew(KnowledgeSourceClaim claim) {
        var now = now();
        if (jdbc.update("""
                UPDATE knowledge_sources SET lease_until = ? WHERE id = ? AND run_token = ?
                    AND scan_status = 'RUNNING' AND enabled = TRUE AND lease_until > ?
                """, now.plusSeconds(properties.getLeaseSeconds()), claim.source().id(), claim.token(), now) != 1) {
            throw new KnowledgeDocumentConflictException("source scan lease lost");
        }
    }

    /** 在写入事务开始时锁定来源并核对执行权，序列化同一来源的所有提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fence(KnowledgeSourceClaim claim) {
        var tokens = jdbc.queryForList("""
                SELECT run_token FROM knowledge_sources WHERE id = ? AND enabled = TRUE
                    AND scan_status = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6) FOR UPDATE
                """, String.class, claim.source().id());
        if (tokens.size() != 1 || !Objects.equals(tokens.getFirst(), claim.token())) {
            throw new KnowledgeDocumentConflictException("source scan lease lost");
        }
    }

    /** 在完整清单中出现的对象获得稳定映射；哈希碰撞仍比较原始键并拒绝覆盖。 */
    @Transactional
    public KnowledgeSourceItem seen(KnowledgeSourceClaim claim, String key) {
        fence(claim);
        String hash = OssSourceObject.hash(key);
        var items = jdbc.query("SELECT * FROM knowledge_source_items WHERE source_id = ? AND key_hash = ?", ITEM, claim.source().id(), hash);
        long id;
        if (items.isEmpty()) {
            id = insert("INSERT INTO knowledge_source_items(source_id, object_key, key_hash, last_seen_token) VALUES (?, ?, ?, ?)",
                    claim.source().id(), key, hash, claim.token());
        } else {
            var item = items.getFirst();
            if (!item.objectKey().equals(key)) throw new KnowledgeDocumentConflictException("external object identity collision");
            id = item.id();
            jdbc.update("UPDATE knowledge_source_items SET last_seen_token = ? WHERE id = ?", claim.token(), id);
        }
        return item(claim.source().id(), id);
    }

    /** 读取归属于指定来源的对象映射。 */
    public KnowledgeSourceItem item(Long sourceId, Long id) {
        return jdbc.query("SELECT * FROM knowledge_source_items WHERE id = ? AND source_id = ?", ITEM, id, sourceId)
                .stream().findFirst().orElseThrow(() -> new InvalidKnowledgeDocumentException("source item does not exist"));
    }

    /** 分页展示来源对象的导入、错误或缺失状态，与文档索引状态分开。 */
    public PageResponse<KnowledgeSourceItem> items(Long baseId, Long sourceId, long page, long size) {
        get(baseId, sourceId);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source_items WHERE source_id = ?", Long.class, sourceId);
        var rows = jdbc.query("SELECT * FROM knowledge_source_items WHERE source_id = ? ORDER BY id LIMIT ? OFFSET ?", ITEM,
                sourceId, size, (page - 1) * size);
        return new PageResponse<>(page, size, count, (count + size - 1) / size, rows);
    }

    /** 候选、任务与来源指针在调用方同一事务提交，只有成功导入才推进已接受指纹。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(KnowledgeSourceClaim claim, Long itemId, Long documentId, String fingerprint) {
        fence(claim);
        jdbc.update("""
                UPDATE knowledge_source_items SET last_document_id = ?, accepted_fingerprint = ?, item_status = 'SYNCED',
                    error_code = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND source_id = ?
                """, documentId, fingerprint, itemId, claim.source().id());
    }

    /** 重复扫描仅清除来源错误，不重新提交索引任务。 */
    @Transactional
    public void unchanged(KnowledgeSourceClaim claim, KnowledgeSourceItem item) {
        accept(claim, item.id(), item.lastDocumentId(), item.acceptedFingerprint());
    }

    /** 单项失败保留上次成功指纹与文档，下轮扫描仍能重试本次变更。 */
    @Transactional
    public void error(KnowledgeSourceClaim claim, Long itemId, String code) {
        fence(claim);
        jdbc.update("UPDATE knowledge_source_items SET item_status = 'ERROR', error_code = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND source_id = ?",
                code, itemId, claim.source().id());
    }

    /** 仅完整且无错误的扫描判定缺失；记录标记但不修改已发布文档。 */
    @Transactional
    public KnowledgeSource finish(KnowledgeSourceClaim claim, int added, int updated, int unchanged, int failed) {
        fence(claim);
        if (failed == 0) {
            jdbc.update("""
                    UPDATE knowledge_source_items SET item_status = 'MISSING', error_code = NULL, updated_at = CURRENT_TIMESTAMP(6)
                    WHERE source_id = ? AND (last_seen_token IS NULL OR last_seen_token <> ?)
                    """, claim.source().id(), claim.token());
        }
        int missing = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source_items WHERE source_id = ? AND item_status = 'MISSING'",
                Integer.class, claim.source().id());
        jdbc.update("""
                UPDATE knowledge_sources SET scan_status = ?, lease_until = NULL, last_scan_at = CURRENT_TIMESTAMP(6),
                    added_count = ?, updated_count = ?, unchanged_count = ?, failed_count = ?, missing_count = ?, error_code = ? WHERE id = ?
                """, failed == 0 ? "SUCCEEDED" : "PARTIAL", added, updated, unchanged, failed, missing,
                failed == 0 ? null : "SOURCE_ITEM_ERRORS", claim.source().id());
        return get(claim.source().knowledgeBaseId(), claim.source().id());
    }

    /** 清单或配置失败不执行缺失判断；过期工作者不能覆盖新一轮的报告。 */
    public void fail(KnowledgeSourceClaim claim) {
        jdbc.update("""
                UPDATE knowledge_sources SET scan_status = 'FAILED', lease_until = NULL, last_scan_at = CURRENT_TIMESTAMP(6),
                    error_code = 'SOURCE_SCAN_FAILED' WHERE id = ? AND run_token = ? AND scan_status = 'RUNNING'
                    AND lease_until > CURRENT_TIMESTAMP(6)
                """, claim.source().id(), claim.token());
    }

    /** 插入自增记录并返回主键，不依赖数据库会话级的上次插入状态。 */
    private long insert(String sql, Object... values) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /** 使用数据库时钟计算租约，避免不同应用实例的时间偏差。 */
    private LocalDateTime now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }
}
