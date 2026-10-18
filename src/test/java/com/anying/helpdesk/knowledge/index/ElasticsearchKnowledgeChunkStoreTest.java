package com.anying.helpdesk.knowledge.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchKnowledgeChunkStoreTest {

    private HttpServer server;

    /**
     * 每个测试结束后停止本地假 ES 服务，避免占用随机端口。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证新旧映射均可写入业务版本快照，并保留现有分块替换协议。
     */
    @Test
    @DisplayName("创建映射后可以删除旧 Chunk 并发送 NDJSON 批量请求")
    void createsMappingAndReplacesDocumentChunks() throws IOException {
        AtomicBoolean indexExists = new AtomicBoolean(false);
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> bulkBody = new AtomicReference<>();
        AtomicReference<String> deleteBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleRequest(
                exchange,
                indexExists,
                createBody,
                bulkBody,
                deleteBody,
                3
        ));
        server.start();

        ElasticsearchKnowledgeChunkStore store = createStore(server.getAddress().getPort());
        IndexedKnowledgeChunk chunk = new IndexedKnowledgeChunk(
                2L,
                5L,
                1,
                0,
                4,
                "测试内容",
                new float[]{1F, 0F, 0F},
                "guide.md",
                9L,
                "embedding-model",
                Instant.parse("2026-09-28T12:00:00Z"),
                new KnowledgeDocumentMetadata("travel-policy", "v2", "CN_SH",
                        LocalDateTime.of(2026, 10, 1, 0, 0), null)
        );

        store.replaceDocumentChunks(5L, 3, List.of(chunk));

        assertThat(createBody.get()).contains("\"dense_vector\"").contains("\"dims\":3");
        assertThat(deleteBody.get()).contains("\"documentId\":5");
        assertThat(bulkBody.get())
                .contains("\"_id\":\"5-1\"")
                .contains("\"content\":\"测试内容\"")
                .contains("\"businessVersion\":\"v2\"")
                .contains("\"validFrom\":\"2026-10-01T00:00:00\"")
                .contains("\"contentVector\":[1.0,0.0,0.0]");
    }

    /**
     * 验证 BM25 和 KNN 会合并为一次 msearch 请求，并解析成两路独立候选。
     */
    @Test
    @DisplayName("通过 msearch 同时执行 BM25 与 KNN 检索")
    void searchesBm25AndKnnWithOneMsearchRequest() throws IOException {
        AtomicReference<String> searchBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && "/test_knowledge_chunks/_msearch".equals(exchange.getRequestURI().getPath())) {
                searchBody.set(readBody(exchange));
                send(exchange, 200, """
                        {
                          "responses": [
                            {"hits":{"hits":[{"_id":"5-1","_score":4.2,"_source":{"documentId":5,"chunkSequence":1,"originalName":"guide.md","content":"登录说明"}}]}},
                            {"hits":{"hits":[{"_id":"5-2","_score":0.91,"_source":{"documentId":5,"chunkSequence":2,"originalName":"guide.md","content":"密码帮助"}}]}}
                          ]
                        }
                        """);
                return;
            }
            send(exchange, 404, "{}");
        });
        server.start();
        ElasticsearchKnowledgeChunkStore store = createStore(server.getAddress().getPort());

        KnowledgeChunkSearchResults results = store.search(
                3L,
                8L,
                "embedding-model",
                "如何登录",
                new float[]{0.1F, 0.2F, 0.3F},
                20,
                40,
                0.65D,
                List.of(5L, 7L)
        );

        assertThat(results.bm25Hits()).singleElement()
                .satisfies(hit -> assertThat(hit.chunkId()).isEqualTo("5-1"));
        assertThat(results.vectorHits()).singleElement()
                .satisfies(hit -> assertThat(hit.score()).isEqualTo(0.91D));
        assertThat(searchBody.get())
                .contains("\"match\":{\"content\":\"如何登录\"}")
                .contains("\"knn\"")
                .contains("\"embeddingModelConfigId\":8")
                .contains("\"embeddingModelName\":\"embedding-model\"")
                .contains("\"similarity\":0.65")
                .endsWith("\n");
        String[] lines = searchBody.get().split("\n");
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(lines[1]).at("/query/bool/filter/1/terms/documentId").toString())
                .isEqualTo("[5,7]");
        assertThat(mapper.readTree(lines[3]).at("/knn/filter/bool/filter/1/terms/documentId").toString())
                .isEqualTo("[5,7]");
    }

    /**
     * 创建指向本地假服务的 Chunk Store。
     */
    private ElasticsearchKnowledgeChunkStore createStore(int port) {
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setUri("http://127.0.0.1:" + port);
        properties.setIndexName("test_knowledge_chunks");
        return new ElasticsearchKnowledgeChunkStore(
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    /**
     * 模拟本轮索引所需的少量 Elasticsearch REST 端点。
     */
    private void handleRequest(
            HttpExchange exchange,
            AtomicBoolean indexExists,
            AtomicReference<String> createBody,
            AtomicReference<String> bulkBody,
            AtomicReference<String> deleteBody,
            int dimensions
    ) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("HEAD".equals(method) && "/test_knowledge_chunks".equals(path)) {
            send(exchange, indexExists.get() ? 200 : 404, "");
            return;
        }
        if ("PUT".equals(method) && "/test_knowledge_chunks".equals(path)) {
            createBody.set(readBody(exchange));
            indexExists.set(true);
            send(exchange, 200, "{\"acknowledged\":true}");
            return;
        }
        if ("GET".equals(method) && "/test_knowledge_chunks/_mapping".equals(path)) {
            send(exchange, 200, """
                    {"test_knowledge_chunks":{"mappings":{"properties":{"contentVector":{"type":"dense_vector","dims":%d}}}}}
                    """.formatted(dimensions));
            return;
        }
        if ("PUT".equals(method) && "/test_knowledge_chunks/_mapping".equals(path)) {
            assertThat(readBody(exchange)).contains("\"metadata\"", "\"businessVersion\"");
            send(exchange, 200, "{\"acknowledged\":true}");
            return;
        }
        if ("POST".equals(method) && "/test_knowledge_chunks/_delete_by_query".equals(path)) {
            deleteBody.set(readBody(exchange));
            send(exchange, 200, "{\"failures\":[]}");
            return;
        }
        if ("POST".equals(method) && "/test_knowledge_chunks/_bulk".equals(path)) {
            bulkBody.set(readBody(exchange));
            send(exchange, 200, "{\"errors\":false,\"items\":[]}");
            return;
        }
        send(exchange, 404, "{}");
    }

    /**
     * 读取测试服务收到的 UTF-8 请求正文。
     */
    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 向测试客户端返回指定状态和 JSON 正文。
     */
    private void send(HttpExchange exchange, int status, String body) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
