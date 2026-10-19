package com.anying.helpdesk.knowledge.answer.trace;

import com.anying.helpdesk.common.response.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 独立短事务保存诊断快照，不与客户消息的完成或失败事务绑定。 */
@Repository
@RequiredArgsConstructor
public class KnowledgeAnswerRunStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record Summary(String runId, Long assistantMessageId, Long conversationId, String status,
                          String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt) { }

    /** 按递增修订号保存快照，终态和较新快照不允许被迟到写入覆盖。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void save(KnowledgeAnswerRunSnapshot snapshot) {
        String json = encode(snapshot);
        int updated = jdbc.update("""
                UPDATE knowledge_answer_runs SET status=?, error_code=?, revision=?, snapshot=?, updated_at=?, finished_at=?
                WHERE run_id=? AND status='RUNNING' AND revision < ?
                """, snapshot.status().name(), snapshot.errorCode(), snapshot.revision(), json,
                snapshot.updatedAt(), snapshot.finishedAt(), snapshot.runId(), snapshot.revision());
        if (updated != 0 || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM knowledge_answer_runs WHERE run_id=?", Boolean.class, snapshot.runId()))) return;
        jdbc.update("""
                INSERT INTO knowledge_answer_runs (run_id, assistant_message_id, conversation_id, status,
                    error_code, revision, snapshot, started_at, updated_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.runId(), snapshot.assistantMessageId(), snapshot.conversationId(), snapshot.status().name(),
                snapshot.errorCode(), snapshot.revision(), json, snapshot.startedAt(), snapshot.updatedAt(), snapshot.finishedAt());
    }

    /** 管理员按不透明运行编号读取完整诊断，缺失记录不补造成功或失败状态。 */
    @Transactional(readOnly = true)
    public Optional<KnowledgeAnswerRunSnapshot> find(String runId) {
        List<String> rows = jdbc.queryForList("SELECT snapshot FROM knowledge_answer_runs WHERE run_id=?", String.class, runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(decode(rows.getFirst()));
    }

    /** 分页列表只读取摘要，避免批量返回草案和知识正文。 */
    @Transactional(readOnly = true)
    public PageResponse<Summary> page(long page, int size, Long conversationId, KnowledgeAnswerRunSnapshot.Status status) {
        String where = " WHERE 1=1";
        List<Object> parameters = new ArrayList<>();
        if (conversationId != null) {
            where += " AND conversation_id=?";
            parameters.add(conversationId);
        }
        if (status != null) {
            where += " AND status=?";
            parameters.add(status.name());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_answer_runs" + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add(Math.multiplyExact(page - 1, size));
        var rows = jdbc.query("SELECT run_id, assistant_message_id, conversation_id, status, error_code, started_at, finished_at"
                        + " FROM knowledge_answer_runs" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Summary(rs.getString("run_id"), rs.getLong("assistant_message_id"),
                        rs.getLong("conversation_id"), rs.getString("status"), rs.getString("error_code"),
                        rs.getObject("started_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class)),
                parameters.toArray());
        return new PageResponse<>(page, size, total, (total + size - 1) / size, rows);
    }

    /** 序列化失败只抛固定说明，避免异常正文包含未核验草案。 */
    private String encode(KnowledgeAnswerRunSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid diagnostic snapshot");
        }
    }

    /** 只解析应用保存的快照，损坏记录按技术错误处理。 */
    private KnowledgeAnswerRunSnapshot decode(String json) {
        try {
            return objectMapper.readValue(json, KnowledgeAnswerRunSnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unreadable diagnostic snapshot");
        }
    }
}
