package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class KnowledgeUploadSupport {
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "txt", "text/plain", "md", "text/markdown", "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** 普通上传和新版上传共用大小、文件类型、名称清理与内容哈希规则。 */
    public KnowledgeUploadFile prepare(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidKnowledgeDocumentException("document file must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidKnowledgeDocumentException("document file must not exceed 20 MB");
        }
        try {
            return prepare(file.getOriginalFilename(), file.getBytes());
        } catch (IOException exception) {
            throw new InvalidKnowledgeDocumentException("failed to read uploaded document");
        }
    }

    /** 外部来源快照与人工上传共用格式和内容校验，避免引入伪造的 MultipartFile。 */
    public KnowledgeUploadFile prepare(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_SIZE) {
            throw new InvalidKnowledgeDocumentException("document must be between 1 byte and 20 MB");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidKnowledgeDocumentException("document filename must not be blank");
        }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > 255) {
            throw new InvalidKnowledgeDocumentException("document filename is invalid or too long");
        }
        int separator = name.lastIndexOf('.');
        String extension = separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.containsKey(extension)) {
            throw new InvalidKnowledgeDocumentException("only txt, md, pdf, and docx documents are supported");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new KnowledgeUploadFile(bytes, name, extension, CONTENT_TYPES.get(extension), hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** 每份候选保存独立对象，不覆盖旧版的原始文件。 */
    public String objectKey(Long knowledgeBaseId, String extension) {
        return "knowledge-bases/" + knowledgeBaseId + "/documents/" + UUID.randomUUID() + "." + extension;
    }
}
