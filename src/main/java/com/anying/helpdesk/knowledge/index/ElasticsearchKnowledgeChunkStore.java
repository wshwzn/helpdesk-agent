package com.anying.helpdesk.knowledge.index;

import com.anying.helpdesk.knowledge.exception.KnowledgeIndexException;
import com.anying.helpdesk.knowledge.exception.KnowledgeRetrievalException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchKnowledgeChunkStore implements KnowledgeChunkStore {

    private static final List<String> SEARCH_SOURCE_FIELDS = List.of(
            "documentId",
            "chunkSequence",
            "originalName",
            "content"
    );

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String authorizationHeader;

    /**
     * 创建支持 Basic Auth 和自签名 CA 的 Elasticsearch REST 客户端。
     */
    public ElasticsearchKnowledgeChunkStore(
            ElasticsearchProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.endpoint = normalizeEndpoint(properties.getUri());
        this.authorizationHeader = buildAuthorizationHeader(properties);
        this.httpClient = buildHttpClient(properties);
    }

    /**
     * 创建索引或验证现有索引的 dense_vector 维度。
     */
    @Override
    public void ensureIndex(int dimensions) {
        HttpResponse<String> headResponse = send("HEAD", indexPath(), null, null);
        if (headResponse.statusCode() == 404) {
            createIndex(dimensions);
        } else if (!isSuccess(headResponse.statusCode())) {
            throw statusException("failed to check Elasticsearch index", headResponse.statusCode());
        }
        validateIndexDimensions(dimensions);
    }

    /**
     * 先清理旧 Chunk，再使用确定性文档 ID 批量写入新 Chunk。
     */
    @Override
    public void replaceDocumentChunks(Long documentId, int dimensions, List<IndexedKnowledgeChunk> chunks) {
        ensureIndex(dimensions);
        deleteDocument(documentId);
        if (chunks.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (IndexedKnowledgeChunk chunk : chunks) {
            body.append(toJson(Map.of(
                    "index", Map.of("_id", chunk.documentId() + "-" + chunk.chunkSequence())
            ))).append('\n');
            body.append(toJson(chunk)).append('\n');
        }
        HttpResponse<String> response = send(
                "POST",
                indexPath() + "/_bulk?refresh=wait_for",
                body.toString(),
                "application/x-ndjson"
        );
        if (!isSuccess(response.statusCode())) {
            throw statusException("failed to bulk index knowledge chunks", response.statusCode());
        }
        JsonNode result = readJson(response.body(), "invalid Elasticsearch bulk response");
        if (result.path("errors").asBoolean(false)) {
            throw new KnowledgeIndexException("Elasticsearch rejected one or more knowledge chunks");
        }
    }

    /**
     * 通过一次 msearch 请求执行 BM25 与 KNN，并保留两路各自的排名和原始分数。
     */
    @Override
    public KnowledgeChunkSearchResults search(
            Long knowledgeBaseId,
            Long embeddingModelConfigId,
            String embeddingModelName,
            String query,
            float[] queryVector,
            int rankWindowSize,
            int vectorNumCandidates,
            Double vectorMinSimilarity
    ) {
        StringBuilder body = new StringBuilder();
        body.append("{}\n");
        body.append(toJson(buildBm25SearchBody(knowledgeBaseId, query, rankWindowSize))).append('\n');
        body.append("{}\n");
        body.append(toJson(buildVectorSearchBody(
                knowledgeBaseId,
                embeddingModelConfigId,
                embeddingModelName,
                queryVector,
                rankWindowSize,
                vectorNumCandidates,
                vectorMinSimilarity
        ))).append('\n');
        HttpResponse<String> response = send(
                "POST",
                indexPath() + "/_msearch",
                body.toString(),
                "application/x-ndjson"
        );
        if (!isSuccess(response.statusCode())) {
            throw retrievalStatusException("failed to search Elasticsearch", response.statusCode());
        }
        JsonNode root = readSearchJson(response.body());
        JsonNode responses = root.path("responses");
        if (!responses.isArray() || responses.size() != 2) {
            throw new KnowledgeRetrievalException("Elasticsearch returned an invalid msearch response");
        }
        return new KnowledgeChunkSearchResults(
                parseSearchHits(responses.get(0)),
                parseSearchHits(responses.get(1))
        );
    }

    /**
     * 使用 delete-by-query 清除一份文档的所有检索数据。
     */
    @Override
    public void deleteDocument(Long documentId) {
        HttpResponse<String> response = send(
                "POST",
                indexPath() + "/_delete_by_query?refresh=true&conflicts=proceed",
                toJson(Map.of("query", Map.of("term", Map.of("documentId", documentId)))),
                MediaType.APPLICATION_JSON_VALUE
        );
        if (response.statusCode() == 404) {
            return;
        }
        if (!isSuccess(response.statusCode())) {
            throw statusException("failed to delete knowledge chunks", response.statusCode());
        }
        JsonNode result = readJson(response.body(), "invalid Elasticsearch delete response");
        if (!result.path("failures").isEmpty()) {
            throw new KnowledgeIndexException("Elasticsearch failed to delete one or more knowledge chunks");
        }
    }

    /**
     * 构造限定知识库范围的 BM25 全文检索请求。
     */
    private Map<String, Object> buildBm25SearchBody(Long knowledgeBaseId, String query, int rankWindowSize) {
        return Map.of(
                "size", rankWindowSize,
                "_source", SEARCH_SOURCE_FIELDS,
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("match", Map.of("content", query))),
                        "filter", List.of(Map.of("term", Map.of("knowledgeBaseId", knowledgeBaseId)))
                ))
        );
    }

    /**
     * 构造仅使用当前默认向量模型 Chunk 的 KNN 检索请求。
     */
    private Map<String, Object> buildVectorSearchBody(
            Long knowledgeBaseId,
            Long embeddingModelConfigId,
            String embeddingModelName,
            float[] queryVector,
            int rankWindowSize,
            int vectorNumCandidates,
            Double vectorMinSimilarity
    ) {
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "contentVector");
        knn.put("query_vector", queryVector);
        knn.put("k", rankWindowSize);
        knn.put("num_candidates", vectorNumCandidates);
        if (vectorMinSimilarity != null) {
            knn.put("similarity", vectorMinSimilarity);
        }
        knn.put("filter", Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("knowledgeBaseId", knowledgeBaseId)),
                Map.of("term", Map.of("embeddingModelConfigId", embeddingModelConfigId)),
                Map.of("term", Map.of("embeddingModelName", embeddingModelName))
        ))));
        return Map.of(
                "size", rankWindowSize,
                "_source", SEARCH_SOURCE_FIELDS,
                "knn", knn
        );
    }

    /**
     * 解析一路 ES 命中，并在子查询失败时返回脱敏的检索异常。
     */
    private List<KnowledgeChunkSearchHit> parseSearchHits(JsonNode response) {
        if (response.has("error") || response.path("status").asInt(200) >= 400) {
            throw new KnowledgeRetrievalException("Elasticsearch rejected a knowledge search query");
        }
        JsonNode hitsNode = response.path("hits").path("hits");
        if (!hitsNode.isArray()) {
            throw new KnowledgeRetrievalException("Elasticsearch returned invalid search hits");
        }
        List<KnowledgeChunkSearchHit> hits = new java.util.ArrayList<>(hitsNode.size());
        for (JsonNode hitNode : hitsNode) {
            JsonNode source = hitNode.path("_source");
            if (!source.isObject()) {
                throw new KnowledgeRetrievalException("Elasticsearch returned a hit without source data");
            }
            hits.add(new KnowledgeChunkSearchHit(
                    hitNode.path("_id").asText(),
                    source.path("documentId").asLong(),
                    source.path("chunkSequence").asInt(),
                    source.path("originalName").asText(),
                    source.path("content").asText(),
                    hitNode.path("_score").asDouble()
            ));
        }
        return List.copyOf(hits);
    }

    /**
     * 解析检索响应 JSON，并将协议格式问题归类为检索失败。
     */
    private JsonNode readSearchJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new KnowledgeRetrievalException("invalid Elasticsearch search response", exception);
        }
    }

    /**
     * 创建包含全文字段和余弦相似度向量字段的固定映射。
     */
    private void createIndex(int dimensions) {
        Map<String, Object> propertiesMapping = new LinkedHashMap<>();
        propertiesMapping.put("knowledgeBaseId", Map.of("type", "long"));
        propertiesMapping.put("documentId", Map.of("type", "long"));
        propertiesMapping.put("chunkSequence", Map.of("type", "integer"));
        propertiesMapping.put("startOffset", Map.of("type", "integer"));
        propertiesMapping.put("endOffset", Map.of("type", "integer"));
        propertiesMapping.put("content", Map.of("type", "text", "analyzer", "standard"));
        propertiesMapping.put("contentVector", Map.of(
                "type", "dense_vector",
                "dims", dimensions,
                "similarity", "cosine"
        ));
        propertiesMapping.put("originalName", Map.of("type", "keyword", "ignore_above", 255));
        propertiesMapping.put("embeddingModelConfigId", Map.of("type", "long"));
        propertiesMapping.put("embeddingModelName", Map.of("type", "keyword", "ignore_above", 100));
        propertiesMapping.put("indexedAt", Map.of("type", "date"));
        Map<String, Object> body = Map.of(
                "settings", Map.of("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Map.of("dynamic", "strict", "properties", propertiesMapping)
        );
        HttpResponse<String> response = send(
                "PUT",
                indexPath(),
                toJson(body),
                MediaType.APPLICATION_JSON_VALUE
        );
        if (!isSuccess(response.statusCode()) && response.statusCode() != 400) {
            throw statusException("failed to create Elasticsearch index", response.statusCode());
        }
    }

    /**
     * 读取索引映射并拒绝与当前 Embedding 配置不一致的维度。
     */
    private void validateIndexDimensions(int expectedDimensions) {
        HttpResponse<String> response = send("GET", indexPath() + "/_mapping", null, null);
        if (!isSuccess(response.statusCode())) {
            throw statusException("failed to read Elasticsearch index mapping", response.statusCode());
        }
        JsonNode root = readJson(response.body(), "invalid Elasticsearch mapping response");
        int actualDimensions = root.path(properties.getIndexName())
                .path("mappings")
                .path("properties")
                .path("contentVector")
                .path("dims")
                .asInt(-1);
        if (actualDimensions != expectedDimensions) {
            throw new KnowledgeIndexException(
                    "Elasticsearch vector dimensions do not match the default embedding model"
            );
        }
    }

    /**
     * 发送一个经过认证的 Elasticsearch 请求，不向异常暴露响应正文。
     */
    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(properties.getRequestTimeout())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new KnowledgeIndexException("failed to connect to Elasticsearch", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KnowledgeIndexException("Elasticsearch request was interrupted", exception);
        }
    }

    /**
     * 根据可选 CA 文件构造 JDK HTTP 客户端。
     */
    private HttpClient buildHttpClient(ElasticsearchProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout());
        if (properties.getCaCertPath() == null || properties.getCaCertPath().isBlank()) {
            return builder.build();
        }
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate certificate;
            try (InputStream inputStream = Files.newInputStream(Path.of(properties.getCaCertPath()))) {
                certificate = certificateFactory.generateCertificate(inputStream);
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            trustStore.setCertificateEntry("elasticsearch-http-ca", certificate);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return builder.sslContext(sslContext).build();
        } catch (IOException | GeneralSecurityException exception) {
            throw new KnowledgeIndexException("failed to load Elasticsearch CA certificate", exception);
        }
    }

    /**
     * 规范化 Elasticsearch 根地址并拒绝携带路径或查询参数。
     */
    private String normalizeEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeIndexException("invalid Elasticsearch URI", exception);
        }
        if (uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new KnowledgeIndexException("invalid Elasticsearch URI");
        }
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /**
     * 在配置用户名时生成预认证 Basic 请求头。
     */
    private String buildAuthorizationHeader(ElasticsearchProperties properties) {
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            return null;
        }
        String credentials = properties.getUsername() + ":" + properties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将索引名转换为 REST 路径。
     */
    private String indexPath() {
        return "/" + properties.getIndexName();
    }

    /**
     * 将对象序列化为 Elasticsearch 请求 JSON。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new KnowledgeIndexException("failed to serialize Elasticsearch request", exception);
        }
    }

    /**
     * 解析 Elasticsearch JSON 响应并转换协议错误。
     */
    private JsonNode readJson(String value, String message) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new KnowledgeIndexException(message, exception);
        }
    }

    /**
     * 判断 HTTP 状态码是否表示成功。
     */
    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 创建只包含操作和状态码的脱敏异常。
     */
    private KnowledgeIndexException statusException(String operation, int statusCode) {
        return new KnowledgeIndexException(operation + " (HTTP " + statusCode + ")");
    }

    /**
     * 创建只包含检索操作和状态码的脱敏异常。
     */
    private KnowledgeRetrievalException retrievalStatusException(String operation, int statusCode) {
        return new KnowledgeRetrievalException(operation + " (HTTP " + statusCode + ")");
    }
}
