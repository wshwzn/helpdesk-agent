package com.anying.helpdesk.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.*;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AliyunOssSourceGatewayTest {
    private final OSS client = mock(OSS.class);
    private final AliyunOssObjectStorageService gateway = new AliyunOssObjectStorageService(client, "test");

    /** 分页必须推进游标并保留所有对象，目录标记本身不导入。 */
    @Test @DisplayName("跨页读取完整清单并跳过目录标记")
    void paginatesCompleteListing() {
        var first = page(true, "next", "external/", "external/a.md");
        var last = page(false, null, "external/b.md");
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call -> {
            ListObjectsV2Request request = call.getArgument(0);
            assertThat(request.getPrefix()).isEqualTo("external/");
            return request.getContinuationToken() == null ? first : last;
        });
        assertThat(gateway.list("external/", 10)).containsExactly("external/a.md", "external/b.md");
        verify(client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    /** 后页失败必须抛错，调用方不得得到可能误判缺失的半份清单。 */
    @Test @DisplayName("后续分页失败拒绝返回部分结果")
    void rejectsPartialListing() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(true, "next", "external/a.md")).thenThrow(new ClientException("failure"));
        assertThatThrownBy(() -> gateway.list("external/", 10)).isInstanceOf(KnowledgeStorageException.class);
    }

    /** 达到上限、游标不推进或返回目录外对象均终止，避免无限扫描。 */
    @Test @DisplayName("数量上限与异常游标保护扫描边界")
    void boundsListing() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null, "external/a.md", "external/b.md"));
        assertThatThrownBy(() -> gateway.list("external/", 1)).isInstanceOf(KnowledgeStorageException.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(true, "same", "external/a.md"));
        assertThatThrownBy(() -> gateway.list("external/", 10)).isInstanceOf(KnowledgeStorageException.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null, "outside/a.md"));
        assertThatThrownBy(() -> gateway.list("external/", 10)).isInstanceOf(KnowledgeStorageException.class);
    }

    /** 下载请求必须携带 ETag 前置条件并核对 GET 返回的元数据。 */
    @Test @DisplayName("一致快照使用条件 GET 并校验长度")
    void readsConditionalSnapshot() {
        when(client.getObjectMetadata("test", "external/a.md")).thenReturn(metadata("v1", 3));
        var expected = gateway.head("external/a.md");
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request = call.getArgument(0);
            assertThat(request.getMatchingETagConstraints()).containsExactly("etag");
            var result = new OSSObject();
            result.setObjectMetadata(metadata("v1", 3));
            result.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
            return result;
        });
        assertThat(gateway.read("external/a.md", expected)).containsExactly((byte)1, (byte)2, (byte)3);
    }

    /** 仅业务元数据变更也必须阻止把旧头信息与新正文混合导入。 */
    @Test @DisplayName("相同 ETag 但元数据变化时拒绝快照")
    void rejectsMetadataRace() {
        when(client.getObjectMetadata("test", "external/a.md")).thenReturn(metadata("v1", 3));
        var object = new OSSObject();
        object.setObjectMetadata(metadata("v2", 3));
        object.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object);
        assertThatThrownBy(() -> gateway.read("external/a.md", gateway.head("external/a.md")))
                .isInstanceOf(KnowledgeStorageException.class).hasMessageContaining("changed");
    }

    /** 对象长度必须与预期一致，并在下载前拒绝超限文件。 */
    @Test @DisplayName("错误长度和超限文件不能导入")
    void rejectsInvalidLength() {
        when(client.getObjectMetadata("test", "external/a.md")).thenReturn(metadata("v1", 3));
        var object = new OSSObject();
        object.setObjectMetadata(metadata("v1", 3));
        object.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2}));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object);
        assertThatThrownBy(() -> gateway.read("external/a.md", gateway.head("external/a.md")))
                .isInstanceOf(KnowledgeStorageException.class).hasMessageContaining("length");
        when(client.getObjectMetadata("test", "external/a.md")).thenReturn(metadata("v1", 21L * 1024 * 1024));
        assertThatThrownBy(() -> gateway.read("external/a.md", gateway.head("external/a.md")))
                .isInstanceOf(KnowledgeStorageException.class).hasMessageContaining("20 MB");
        verify(client, times(1)).getObject(any(GetObjectRequest.class));
    }

    /** 构造真实 SDK 分页结果，验证生产网关实际使用的字段。 */
    private ListObjectsV2Result page(boolean truncated, String token, String... keys) {
        var result = new ListObjectsV2Result();
        result.setTruncated(truncated);
        result.setNextContinuationToken(token);
        result.getObjectSummaries().addAll(List.of(keys).stream().map(key -> {
            var summary = new OSSObjectSummary();
            summary.setKey(key);
            return summary;
        }).toList());
        return result;
    }

    /** 构造内容相同而业务版本可变的头信息。 */
    private ObjectMetadata metadata(String version, long length) {
        var value = new ObjectMetadata();
        value.setHeader("ETag", "etag");
        value.setContentLength(length);
        value.setLastModified(new Date(1000));
        value.setUserMetadata(Map.of("kb-business-version", version));
        return value;
    }
}
