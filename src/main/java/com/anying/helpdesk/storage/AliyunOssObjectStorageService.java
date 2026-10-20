package com.anying.helpdesk.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.GetObjectRequest;
import com.anying.helpdesk.knowledge.source.OssSourceGateway;
import com.anying.helpdesk.knowledge.source.OssSourceObject;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

@Service
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "aliyun-oss")
public class AliyunOssObjectStorageService implements ObjectStorageService, OssSourceGateway, AutoCloseable {

    private final OSS client;
    private final String bucketName;

    /** 仅在完整分页成功后返回清单，防止部分结果被误当成来源删除。 */
    @Override
    public List<String> list(String prefix, int limit) {
        try {
            var request = new ListObjectsV2Request(bucketName);
            request.setPrefix(prefix);
            request.setMaxKeys(Math.min(limit + 1, 100));
            List<String> keys = new ArrayList<>();
            var tokens = new HashSet<String>();
            int scanned = 0;
            do {
                var result = client.listObjectsV2(request);
                for (var object : result.getObjectSummaries()) {
                    if (++scanned > limit || !object.getKey().startsWith(prefix)) {
                        throw new KnowledgeStorageException("source listing exceeds its configured boundary");
                    }
                    if (!object.getKey().endsWith("/")) keys.add(object.getKey());
                }
                if (!result.isTruncated()) return keys.stream().distinct().toList();
                String token = result.getNextContinuationToken();
                if (token == null || !tokens.add(token)) {
                    throw new KnowledgeStorageException("source listing did not advance");
                }
                request.setContinuationToken(token);
            } while (true);
        } catch (OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to list external source", exception);
        }
    }

    /** 获取包含自定义元数据的完整头信息，普通对象清单不能替代此步骤。 */
    @Override
    public OssSourceObject head(String key) {
        try {
            return describe(client.getObjectMetadata(bucketName, key));
        } catch (OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to inspect external object", exception);
        }
    }

    /** 条件下载并再次核对完整元数据，避免 HEAD 与 GET 之间的变更混入同一快照。 */
    @Override
    public byte[] read(String key, OssSourceObject expected) {
        if (expected.size() < 1 || expected.size() > 20L * 1024 * 1024) {
            throw new KnowledgeStorageException("external document must be between 1 byte and 20 MB");
        }
        var request = new GetObjectRequest(bucketName, key);
        request.setMatchingETagConstraints(List.of(expected.etag()));
        try (var object = client.getObject(request)) {
            if (!describe(object.getObjectMetadata()).fingerprint().equals(expected.fingerprint())) {
                throw new KnowledgeStorageException("external object changed during snapshot");
            }
            byte[] content = object.getObjectContent().readNBytes(20 * 1024 * 1024 + 1);
            if (content.length != expected.size()) {
                throw new KnowledgeStorageException("external snapshot length mismatch");
            }
            return content;
        } catch (IOException | OSSException | ClientException exception) {
            throw new KnowledgeStorageException("failed to snapshot external document", exception);
        }
    }

    /** 统一 HEAD 和 GET 的变化标识，排除业务无关的响应头。 */
    private OssSourceObject describe(ObjectMetadata metadata) {
        return new OssSourceObject(metadata.getETag(), metadata.getContentLength(),
                metadata.getLastModified().getTime(), metadata.getUserMetadata());
    }

    /**
     * 使用环境配置创建 OSS 客户端，缺少必要配置时阻止应用启动。
     */
    @org.springframework.beans.factory.annotation.Autowired
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

    /** 注入 SDK 客户端用于验证分页和条件读取协议，不连接真实云服务。 */
    AliyunOssObjectStorageService(OSS client, String bucketName) {
        this.client = client;
        this.bucketName = bucketName;
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
