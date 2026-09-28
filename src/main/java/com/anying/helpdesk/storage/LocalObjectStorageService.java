package com.anying.helpdesk.storage;

import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorageService implements ObjectStorageService {

    private static final String LOCAL_BUCKET = "local";

    private final Path rootDirectory;

    /**
     * 初始化并规范化本地对象存储根目录。
     */
    public LocalObjectStorageService(ObjectStorageProperties properties) {
        this.rootDirectory = Path.of(properties.getLocalRootDirectory()).toAbsolutePath().normalize();
    }

    /**
     * 返回本地存储提供方标识。
     */
    @Override
    public String provider() {
        return "local";
    }

    /**
     * 返回本地开发使用的逻辑桶名称。
     */
    @Override
    public String bucketName() {
        return LOCAL_BUCKET;
    }

    /**
     * 将上传流保存到本地根目录内，并阻止对象键越界。
     */
    @Override
    public void upload(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        Path target = resolveObjectPath(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new KnowledgeStorageException("failed to upload knowledge document", exception);
        }
    }

    /**
     * 打开本地对象读取流，并继续执行桶名和路径边界校验。
     */
    @Override
    public InputStream download(String bucketName, String objectKey) {
        if (!LOCAL_BUCKET.equals(bucketName)) {
            throw new KnowledgeStorageException("stored document belongs to another bucket");
        }
        try {
            return Files.newInputStream(resolveObjectPath(objectKey));
        } catch (IOException exception) {
            throw new KnowledgeStorageException("failed to download knowledge document", exception);
        }
    }

    /**
     * 删除本地对象；不存在的对象无需报错。
     */
    @Override
    public void delete(String bucketName, String objectKey) {
        if (!LOCAL_BUCKET.equals(bucketName)) {
            throw new KnowledgeStorageException("stored document belongs to another bucket");
        }
        try {
            Files.deleteIfExists(resolveObjectPath(objectKey));
        } catch (IOException exception) {
            throw new KnowledgeStorageException("failed to delete knowledge document", exception);
        }
    }

    /**
     * 将对象键解析到根目录，并拒绝目录穿越路径。
     */
    private Path resolveObjectPath(String objectKey) {
        Path resolved = rootDirectory.resolve(objectKey).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new KnowledgeStorageException("invalid object key");
        }
        return resolved;
    }
}
