package com.anying.helpdesk.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "aliyun-oss")
public class AliyunOssObjectStorageService implements ObjectStorageService, AutoCloseable {

    private final OSS client;
    private final String bucketName;

    /**
     * 使用环境配置创建 OSS 客户端，缺少必要配置时阻止应用启动。
     */
    public AliyunOssObjectStorageService(ObjectStorageProperties properties) {
        requireText(properties.getEndpoint(), "OSS_ENDPOINT");
        requireText(properties.getBucket(), "OSS_BUCKET");
        requireText(properties.getAccessKeyId(), "OSS_ACCESS_KEY_ID");
        requireText(properties.getAccessKeySecret(), "OSS_ACCESS_KEY_SECRET");
        this.client = new OSSClientBuilder().build(
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret()
        );
        this.bucketName = properties.getBucket();
    }

    /**
     * 返回阿里云 OSS 提供方标识。
     */
    @Override
    public String provider() {
        return "aliyun-oss";
    }

    /**
     * 返回配置的 OSS Bucket 名称。
     */
    @Override
    public String bucketName() {
        return bucketName;
    }

    /**
     * 上传文件流并显式设置长度与内容类型。
     */
    @Override
    public void upload(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        metadata.setContentType(contentType);
        try {
            client.putObject(bucketName, objectKey, inputStream, metadata);
        } catch (OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to upload knowledge document", exception);
        }
    }

    /**
     * 从当前 OSS Bucket 打开对象读取流，由调用方在解析结束后关闭。
     */
    @Override
    public InputStream download(String bucketName, String objectKey) {
        if (!this.bucketName.equals(bucketName)) {
            throw new KnowledgeStorageException("stored document belongs to another bucket");
        }
        try {
            return client.getObject(bucketName, objectKey).getObjectContent();
        } catch (OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to download knowledge document", exception);
        }
    }

    /**
     * 删除属于当前 Bucket 的对象。
     */
    @Override
    public void delete(String bucketName, String objectKey) {
        if (!this.bucketName.equals(bucketName)) {
            throw new KnowledgeStorageException("stored document belongs to another bucket");
        }
        try {
            client.deleteObject(bucketName, objectKey);
        } catch (OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to delete knowledge document", exception);
        }
    }

    /**
     * 在 Spring 容器关闭时释放 OSS 客户端资源。
     */
    @Override
    public void close() {
        client.shutdown();
    }

    /**
     * 校验 OSS 必需配置，同时避免将配置值写入异常。
     */
    private void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured for aliyun-oss storage");
        }
    }
}
