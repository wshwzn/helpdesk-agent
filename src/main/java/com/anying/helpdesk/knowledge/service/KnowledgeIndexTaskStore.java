package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.dto.KnowledgeIndexTaskResponse;
import com.anying.helpdesk.knowledge.index.KnowledgeIndexTaskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeIndexTaskStore {
    private final JdbcTemplate jdbc;
    private final KnowledgeIndexTaskProperties properties;

    /** 在文档已锁定为 PROCESSING 的事务内创建任务，重新提交时重置本轮重试预算。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueue(Long documentId) {
        jdbc.update("DELETE FROM knowledge_index_tasks WHERE document_id = ?", documentId);
        jdbc.update("""
                INSERT INTO knowledge_index_tasks(document_id, status, max_attempts, available_at)
                VALUES (?, 'QUEUED', ?, CURRENT_TIMESTAMP(6))
                """, documentId, properties.getMaxAttempts());
    }

    /** 查询最近一次提交；未提交的文档返回空，调度器不会将其自动纳入队列。 */
    public KnowledgeIndexTaskResponse find(Long documentId) {
        List<KnowledgeIndexTaskResponse> rows = jdbc.query("SELECT * FROM knowledge_index_tasks WHERE document_id = ?",
                (rs, row) -> new KnowledgeIndexTaskResponse(rs.getLong("document_id"), rs.getString("status"),
                        rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getTimestamp("available_at").toLocalDateTime(),
                        localTime(rs.getTimestamp("lease_until")), rs.getString("error_code"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 使用数据库时间判断超时，并以条件更新竞争领取任务，多个实例只有一个获得令牌。 */
    @Transactional
    public KnowledgeIndexClaim claimNext() {
        LocalDateTime now = databaseNow();
        List<Long> candidates = jdbc.queryForList("""
                SELECT document_id FROM knowledge_index_tasks
                WHERE (status = 'QUEUED' AND available_at <= ?) OR (status = 'RUNNING' AND lease_until <= ?)
                ORDER BY available_at, document_id LIMIT 20
                """, Long.class, now, now);
        for (Long id : candidates.stream().sorted().toList()) {
            // 与提交、删除统一先锁文档再锁任务，避免锁顺序相反。
            List<Long> bases = jdbc.queryForList("SELECT knowledge_base_id FROM knowledge_documents WHERE id = ? FOR UPDATE",
                    Long.class, id);
            if (bases.isEmpty()) {
                continue;
            }
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                    UPDATE knowledge_index_tasks SET status = 'RUNNING', attempt_count = attempt_count + 1,
                        run_token = ?, lease_until = ?, updated_at = ?
                    WHERE document_id = ? AND attempt_count < max_attempts AND
                        ((status = 'QUEUED' AND available_at <= ?) OR (status = 'RUNNING' AND lease_until <= ?))
                    """, token, now.plusSeconds(properties.getLeaseSeconds()), now, id, now, now);
            if (changed == 1) {
                return new KnowledgeIndexClaim(bases.getFirst(), id, token);
            }
            int exhausted = jdbc.update("""
                    UPDATE knowledge_index_tasks SET status = 'FAILED', error_code = 'INDEX_LEASE_EXPIRED',
                        lease_until = NULL, updated_at = ?
                    WHERE document_id = ? AND status = 'RUNNING' AND lease_until <= ? AND attempt_count >= max_attempts
                    """, now, id, now);
            if (exhausted == 1) {
                failDocument(id, "INDEX_LEASE_EXPIRED");
            }
        }
        return null;
    }

    /** 心跳仅延长仍有效且属于本执行者的租约；过期执行者不能复活。 */
    public boolean renew(KnowledgeIndexClaim claim) {
        LocalDateTime now = databaseNow();
        return jdbc.update("""
                UPDATE knowledge_index_tasks SET lease_until = ?, updated_at = ?
                WHERE document_id = ? AND run_token = ? AND status = 'RUNNING' AND lease_until > ?
                """, now.plusSeconds(properties.getLeaseSeconds()), now, claim.documentId(), claim.token(), now) == 1;
    }

    /** 在昂贵的外部调用之间检查执行权，减少过期任务继续产生费用。 */
    public boolean owns(KnowledgeIndexClaim claim) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_index_tasks WHERE document_id = ? AND run_token = ?
                    AND status = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6)
                """, Integer.class, claim.documentId(), claim.token()) == 1;
    }

    /** 在同一事务内确认执行权、激活本批次并完成任务；过期工作者不能覆盖新结果。 */
    @Transactional
    public boolean complete(KnowledgeIndexClaim claim, int chunkCount) {
        lockDocument(claim.documentId());
        int changed = jdbc.update("""
                UPDATE knowledge_index_tasks SET status = 'SUCCEEDED', error_code = NULL, lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE document_id = ? AND run_token = ? AND status = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6)
                """, claim.documentId(), claim.token());
        if (changed == 0) {
            return false;
        }
        int activated = jdbc.update("""
                UPDATE knowledge_documents SET status = 'READY', active_index_build_id = ?, chunk_count = ?,
                    error_code = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PROCESSING' AND publication_status = 'DRAFT'
                """, claim.token(), chunkCount, claim.documentId());
        if (activated != 1) {
            throw new IllegalStateException("index task document is not processing");
        }
        return true;
    }

    /** 记录固定错误码；暂时性故障有限重试，确定性解析错误和预算耗尽则终止。 */
    @Transactional
    public void fail(KnowledgeIndexClaim claim, String errorCode, boolean retryable) {
        lockDocument(claim.documentId());
        LocalDateTime now = databaseNow();
        int changed = jdbc.update("""
                UPDATE knowledge_index_tasks SET status = CASE WHEN attempt_count < max_attempts AND ? THEN 'QUEUED' ELSE 'FAILED' END,
                    error_code = ?, lease_until = NULL, available_at = ?, updated_at = ?
                WHERE document_id = ? AND run_token = ? AND status = 'RUNNING' AND lease_until > ?
                """, retryable, errorCode, now.plusSeconds(properties.getRetryDelaySeconds()), now,
                claim.documentId(), claim.token(), now);
        if (changed == 1 && "FAILED".equals(find(claim.documentId()).status())) {
            failDocument(claim.documentId(), errorCode);
        }
    }

    /** 将终止任务关联的文档标记为失败，保持业务发布状态不变。 */
    private void failDocument(Long id, String errorCode) {
        jdbc.update("""
                UPDATE knowledge_documents SET status = 'FAILED', chunk_count = 0, error_code = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PROCESSING'
                """, errorCode, id);
    }

    /** 固定文档与任务的加锁顺序，保证完成与重新提交互斥。 */
    private void lockDocument(Long id) {
        jdbc.queryForList("SELECT id FROM knowledge_documents WHERE id = ? FOR UPDATE", Long.class, id);
    }

    /** 所有实例用同一数据库时钟计算租约，避免应用服务器时钟偏差。 */
    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toLocalDateTime();
    }

    /** 将可空的租约时间转换为接口时间。 */
    private LocalDateTime localTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
