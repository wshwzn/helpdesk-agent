package com.anying.helpdesk.storage;

import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageServiceTest {

    @TempDir
    private Path tempDirectory;

    /**
     * 验证本地实现可以按对象键上传并幂等删除文件。
     */
    @Test
    @DisplayName("本地对象存储可以上传并删除文件")
    void uploadsAndDeletesObject() throws Exception {
        LocalObjectStorageService service = createService();
        byte[] content = "knowledge".getBytes();
        String objectKey = "knowledge-bases/1/documents/test.txt";

        service.upload(objectKey, new ByteArrayInputStream(content), content.length, "text/plain");

        Path storedFile = tempDirectory.resolve(objectKey);
        assertThat(storedFile).hasContent("knowledge");
        try (InputStream inputStream = service.download("local", objectKey)) {
            assertThat(inputStream.readAllBytes()).isEqualTo(content);
        }
        service.delete("local", objectKey);
        service.delete("local", objectKey);
        assertThat(Files.exists(storedFile)).isFalse();
    }

    /**
     * 验证恶意对象键不能逃逸本地存储根目录。
     */
    @Test
    @DisplayName("本地对象存储拒绝目录穿越")
    void rejectsPathTraversal() {
        LocalObjectStorageService service = createService();

        assertThatThrownBy(() -> service.upload(
                "../outside.txt",
                new ByteArrayInputStream(new byte[]{1}),
                1,
                "text/plain"
        )).isInstanceOf(KnowledgeStorageException.class);
    }

    /**
     * 使用测试临时目录创建本地对象存储实例。
     */
    private LocalObjectStorageService createService() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setLocalRootDirectory(tempDirectory.toString());
        return new LocalObjectStorageService(properties);
    }
}
