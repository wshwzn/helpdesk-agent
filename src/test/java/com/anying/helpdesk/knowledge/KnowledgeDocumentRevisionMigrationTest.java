package com.anying.helpdesk.knowledge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class KnowledgeDocumentRevisionMigrationTest {
    /** 在 V20 数据上升级，旧文档不会被自动关联或失效，数据库拒绝悬空的版本关系。 */
    @Test
    @DisplayName("V21 保留历史发布数据并建立版本关系外键")
    void preservesLegacyDataAndEnforcesReferences() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:revision_migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("20").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO knowledge_documents(knowledge_base_id, original_name, storage_provider, bucket_name,
                    object_key, content_type, file_extension, size_bytes, sha256, status, publication_status, created_by)
                VALUES (1, 'test.md', 'local', 'local', 'test.md', 'text/markdown', 'md', 10, ?, 'READY', 'PUBLISHED', 1)
                """, "a".repeat(64));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM knowledge_documents WHERE id = 1");
        assertThat(row).containsEntry("status", "READY").containsEntry("publication_status", "PUBLISHED")
                .containsEntry("predecessor_document_id", null).containsEntry("successor_document_id", null)
                .containsEntry("activated_at", null).containsEntry("superseded_at", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_documents SET predecessor_document_id = 999 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_documents SET successor_document_id = 999 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
