package com.anying.helpdesk.knowledge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexTaskMigrationTest {
    /** 从真实 V19 表结构升级，验证旧内存任务明确失败而已有有效资料不受影响。 */
    @Test
    @DisplayName("V20 标记旧执行中任务失败并保留历史就绪文档")
    void migratesLegacyProcessingWithoutReindexingReadyDocuments() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("19").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        for (String state : new String[]{"PROCESSING", "READY", "PENDING"}) {
            jdbc.update("""
                    INSERT INTO knowledge_documents(knowledge_base_id, original_name, storage_provider, bucket_name,
                        object_key, content_type, file_extension, size_bytes, sha256, status, chunk_count, created_by)
                    VALUES (1, 'test.md', 'local', 'local', ?, 'text/markdown', 'md', 10, ?, ?, 2, 1)
                    """, state + ".md", "a".repeat(64), state);
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForMap("SELECT status, error_code, version, chunk_count FROM knowledge_documents WHERE object_key = 'PROCESSING.md'"))
                .containsEntry("status", "FAILED").containsEntry("error_code", "INDEX_TASK_MISSING")
                .containsEntry("version", 1).containsEntry("chunk_count", 0);
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_documents WHERE object_key = 'READY.md'", String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_documents WHERE object_key = 'PENDING.md'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE active_index_build_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_tasks", Integer.class)).isZero();
    }
}
