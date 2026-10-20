package com.anying.helpdesk.knowledge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class KnowledgeSourceMigrationTest {
    /** 升级不接管旧资料，新增映射的唯一约束与外键拒绝错绑对象。 */
    @Test @DisplayName("V22 保留旧资料并约束来源与文档关联")
    void preservesDataAndEnforcesSourceIdentity() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:source_migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("21").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO users(id, username, password_hash, display_name, role) VALUES (1, 'source-test', 'unused', '测试', 'ADMIN')");
        jdbc.update("INSERT INTO knowledge_bases(id, name, created_by) VALUES (1, '来源迁移测试', 1)");
        jdbc.update("""
                INSERT INTO knowledge_documents(knowledge_base_id, original_name, storage_provider, bucket_name,
                    object_key, content_type, file_extension, size_bytes, sha256, status, publication_status, created_by)
                VALUES (1, 'test.md', 'local', 'local', 'test.md', 'text/markdown', 'md', 10, ?, 'PENDING', 'DRAFT', 1)
                """, "a".repeat(64));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForMap("SELECT status, source_item_id FROM knowledge_documents WHERE id = 1"))
                .containsEntry("status", "PENDING").containsEntry("source_item_id", null);
        jdbc.update("INSERT INTO knowledge_sources(knowledge_base_id,bucket_name,object_prefix,created_by) VALUES (1,'bucket','external-knowledge/test/',1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_sources(knowledge_base_id,bucket_name,object_prefix,created_by) VALUES (1,'bucket','external-knowledge/test/',1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO knowledge_source_items(source_id,object_key,key_hash) VALUES (1,'external-knowledge/test/a.md',?)", "b".repeat(64));
        jdbc.update("UPDATE knowledge_documents SET source_item_id = 1 WHERE id = 1");
        jdbc.update("UPDATE knowledge_source_items SET last_document_id = 1 WHERE id = 1");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM knowledge_documents WHERE id = 1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_documents SET source_item_id = 999 WHERE id = 1")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
