package com.anying.helpdesk.knowledge;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentIndexingService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.anying.helpdesk.storage.ObjectStorageService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminKnowledgeBaseControllerIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @MockitoBean
    private KnowledgeDocumentIndexingService knowledgeDocumentIndexingService;

    @MockitoBean
    private KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 为每个测试准备稳定的对象存储标识。
     */
    @BeforeEach
    void setUpObjectStorage() {
        when(objectStorageService.provider()).thenReturn("local");
        when(objectStorageService.bucketName()).thenReturn("local");
    }

    /**
     * 验证管理员可以完成知识库和文档的完整管理流程。
     */
    @Test
    @DisplayName("管理员可以创建知识库并上传、查询和删除文档")
    void adminManagesKnowledgeBaseAndDocument() throws Exception {
        String token = createUserAndLogin("knowledge_admin_one", UserRole.ADMIN);
        long knowledgeBaseId = createKnowledgeBase(token, "产品帮助中心");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.md",
                "text/markdown",
                "# 使用说明\n请先登录。".getBytes()
        );

        String uploadResponse = mockMvc.perform(multipart(
                                "/api/admin/knowledge-bases/{id}/documents",
                                knowledgeBaseId
                        )
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalName").value("guide.md"))
                .andExpect(jsonPath("$.data.fileExtension").value("md"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sha256").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("objectKey")
                )))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long documentId = ((Number) JsonPath.read(uploadResponse, "$.data.id")).longValue();

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).upload(
                objectKeyCaptor.capture(),
                any(InputStream.class),
                anyLong(),
                anyString()
        );
        assertThat(objectKeyCaptor.getValue())
                .startsWith("knowledge-bases/" + knowledgeBaseId + "/documents/")
                .endsWith(".md");

        when(objectStorageService.download("local", objectKeyCaptor.getValue()))
                .thenAnswer(invocation -> new ByteArrayInputStream(file.getBytes()));

        mockMvc.perform(get(
                                "/api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}",
                                knowledgeBaseId,
                                documentId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(documentId))
                .andExpect(jsonPath("$.data.originalName").value("guide.md"));

        mockMvc.perform(get(
                                "/api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/content",
                                knowledgeBaseId,
                                documentId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown"))
                .andExpect(content().bytes(file.getBytes()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("guide.md")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"));
        mockMvc.perform(post(
                                "/api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunk-preview",
                                knowledgeBaseId,
                                documentId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.totalChunks").value(1))
                .andExpect(jsonPath("$.data.chunks[0].content").value(
                        org.hamcrest.Matchers.containsString("使用说明")
                ));

        mockMvc.perform(get("/api/admin/knowledge-bases/{id}/documents", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(documentId));

        mockMvc.perform(delete(
                                "/api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}",
                                knowledgeBaseId,
                                documentId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        verify(objectStorageService).delete("local", objectKeyCaptor.getValue());

        mockMvc.perform(delete("/api/admin/knowledge-bases/{id}", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
    }

    /**
     * 验证非空知识库不能删除，且非法文件不会进入对象存储。
     */
    @Test
    @DisplayName("非空知识库拒绝删除且不支持的文件类型拒绝上传")
    void rejectsNonEmptyDeletionAndUnsupportedFile() throws Exception {
        String token = createUserAndLogin("knowledge_admin_two", UserRole.ADMIN);
        long knowledgeBaseId = createKnowledgeBase(token, "售后知识库");
        MockMultipartFile validFile = new MockMultipartFile(
                "file",
                "faq.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "常见问题".getBytes()
        );
        mockMvc.perform(multipart("/api/admin/knowledge-bases/{id}/documents", knowledgeBaseId)
                        .file(validFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/admin/knowledge-bases/{id}", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_CONFLICT"));

        MockMultipartFile unsupportedFile = new MockMultipartFile(
                "file",
                "script.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                new byte[]{1, 2, 3}
        );
        mockMvc.perform(multipart("/api/admin/knowledge-bases/{id}/documents", knowledgeBaseId)
                        .file(unsupportedFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KNOWLEDGE_DOCUMENT"));
    }

    /**
     * 验证客户角色不能访问管理员知识库接口。
     */
    @Test
    @DisplayName("客户不能管理知识库")
    void customerCannotManageKnowledgeBases() throws Exception {
        String token = createUserAndLogin("knowledge_customer", UserRole.CUSTOMER);

        mockMvc.perform(get("/api/admin/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /**
     * 验证管理员触发索引后立即获得 PROCESSING 状态和 202 响应。
     */
    @Test
    @DisplayName("管理员可以异步触发文档索引")
    void adminStartsDocumentIndexing() throws Exception {
        String token = createUserAndLogin("knowledge_admin_index", UserRole.ADMIN);
        LocalDateTime now = LocalDateTime.now();
        when(knowledgeDocumentIndexingService.start(3L, 7L)).thenReturn(new KnowledgeDocumentResponse(
                7L,
                3L,
                "guide.md",
                "text/markdown",
                "md",
                1024L,
                "abc123",
                KnowledgeDocumentStatus.PROCESSING,
                0,
                null,
                2,
                now,
                now
        ));

        mockMvc.perform(post("/api/admin/knowledge-bases/3/documents/7/index")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    /**
     * 验证管理员可以更新知识库级检索参数，相似度必填且最终数量不能超过候选数量。
     */
    @Test
    @DisplayName("管理员可以更新知识库检索参数")
    void adminUpdatesKnowledgeRetrievalSettings() throws Exception {
        String token = createUserAndLogin("knowledge_admin_settings", UserRole.ADMIN);
        long knowledgeBaseId = createKnowledgeBase(token, "检索参数知识库");

        mockMvc.perform(put("/api/admin/knowledge-bases/{id}/retrieval-settings", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateLimit": 40,
                                  "vectorMinSimilarity": 0.650,
                                  "resultLimit": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateLimit").value(40))
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.650))
                .andExpect(jsonPath("$.data.resultLimit").value(8));

        mockMvc.perform(get("/api/admin/knowledge-bases/{id}", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievalCandidateLimit").value(40))
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.650))
                .andExpect(jsonPath("$.data.retrievalResultLimit").value(8));

        mockMvc.perform(put("/api/admin/knowledge-bases/{id}/retrieval-settings", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateLimit": 40,
                                  "vectorMinSimilarity": 0.000,
                                  "resultLimit": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.000));

        mockMvc.perform(get("/api/admin/knowledge-bases/{id}", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.000));

        mockMvc.perform(put("/api/admin/knowledge-bases/{id}/retrieval-settings", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateLimit": 40,
                                  "vectorMinSimilarity": null,
                                  "resultLimit": 8
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/admin/knowledge-bases/{id}/retrieval-settings", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateLimit": 5,
                                  "vectorMinSimilarity": 0.000,
                                  "resultLimit": 6
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KNOWLEDGE_RETRIEVAL_SETTINGS"));
    }

    /**
     * 验证后设置的知识库会成为唯一默认项，供客户对话 RAG 工具选择。
     */
    @Test
    @DisplayName("管理员可以切换唯一默认知识库")
    void adminSetsDefaultKnowledgeBase() throws Exception {
        String token = createUserAndLogin("knowledge_admin_default", UserRole.ADMIN);
        long firstId = createKnowledgeBase(token, "默认知识库一");
        long secondId = createKnowledgeBase(token, "默认知识库二");

        mockMvc.perform(post("/api/admin/knowledge-bases/{id}/default", firstId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        mockMvc.perform(post("/api/admin/knowledge-bases/{id}/default", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(secondId))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        mockMvc.perform(get("/api/admin/knowledge-bases/{id}", firstId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(false));
        mockMvc.perform(get("/api/admin/knowledge-bases/{id}", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    /**
     * 验证管理员可以提交自然语言问题并查看双路召回的融合排名。
     */
    @Test
    @DisplayName("管理员可以调试知识库混合检索")
    void adminSearchesKnowledgeBase() throws Exception {
        String token = createUserAndLogin("knowledge_admin_search", UserRole.ADMIN);
        when(knowledgeRetrievalService.search(anyLong(), any())).thenReturn(new KnowledgeSearchResponse(
                3L,
                "如何登录",
                50,
                new java.math.BigDecimal("0.650"),
                5,
                2,
                3,
                List.of(new KnowledgeSearchResultResponse(
                        1,
                        7L,
                        2,
                        "guide.md",
                        "请使用账号和密码登录。",
                        0.0325D,
                        2,
                        4.2D,
                        1,
                        0.91D
                ))
        ));

        mockMvc.perform(post("/api/admin/knowledge-bases/3/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"如何登录"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateLimit").value(50))
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.650))
                .andExpect(jsonPath("$.data.resultLimit").value(5))
                .andExpect(jsonPath("$.data.bm25CandidateCount").value(2))
                .andExpect(jsonPath("$.data.vectorCandidateCount").value(3))
                .andExpect(jsonPath("$.data.results[0].rank").value(1))
                .andExpect(jsonPath("$.data.results[0].bm25Rank").value(2))
                .andExpect(jsonPath("$.data.results[0].vectorRank").value(1));
    }

    /**
     * 验证多期望文档评测用例可以运行、保存指标并保护被引用文档。
     */
    @Test
    @DisplayName("管理员可以运行包含多个期望文档的 RAG 检索评测")
    void adminRunsRagEvaluationWithMultipleExpectedDocuments() throws Exception {
        String token = createUserAndLogin("knowledge_admin_evaluation", UserRole.ADMIN);
        long knowledgeBaseId = createKnowledgeBase(token, "检索评测知识库");
        long firstDocumentId = uploadReadyDocument(token, knowledgeBaseId, "account.md", "账号规则");
        long secondDocumentId = uploadReadyDocument(token, knowledgeBaseId, "admin.md", "管理规则");

        String caseResponse = mockMvc.perform(post(
                                "/api/admin/knowledge-bases/{id}/evaluation/cases",
                                knowledgeBaseId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "连续输错密码会怎么样？",
                                  "expectedDocumentIds": [%d, %d]
                                }
                                """.formatted(firstDocumentId, secondDocumentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expectedDocuments.length()").value(2))
                .andExpect(jsonPath("$.data.expectedDocuments[0].id").value(firstDocumentId))
                .andExpect(jsonPath("$.data.expectedDocuments[1].id").value(secondDocumentId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long caseId = ((Number) JsonPath.read(caseResponse, "$.data.id")).longValue();

        when(knowledgeRetrievalService.search(eq(knowledgeBaseId), any(), any()))
                .thenReturn(new KnowledgeSearchResponse(
                        knowledgeBaseId,
                        "连续输错密码会怎么样？",
                        50,
                        new java.math.BigDecimal("0.000"),
                        5,
                        2,
                        2,
                        List.of(
                                new KnowledgeSearchResultResponse(
                                        1,
                                        999L,
                                        1,
                                        "other.md",
                                        "其他内容",
                                        0.03D,
                                        1,
                                        3.0D,
                                        1,
                                        0.8D
                                ),
                                new KnowledgeSearchResultResponse(
                                        2,
                                        firstDocumentId,
                                        3,
                                        "account.md",
                                        "连续输错密码后账号会被锁定。",
                                        0.02D,
                                        2,
                                        2.0D,
                                        2,
                                        0.7D
                                )
                        )
                ));

        String runResponse = mockMvc.perform(post(
                                "/api/admin/knowledge-bases/{id}/evaluation/runs",
                                knowledgeBaseId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary.totalCases").value(1))
                .andExpect(jsonPath("$.data.summary.hitCases").value(1))
                .andExpect(jsonPath("$.data.summary.hitRate").value(1.0))
                .andExpect(jsonPath("$.data.summary.recallAtK").value(0.5))
                .andExpect(jsonPath("$.data.summary.meanReciprocalRank").value(0.5))
                .andExpect(jsonPath("$.data.results[0].caseId").value(caseId))
                .andExpect(jsonPath("$.data.results[0].matchedDocumentCount").value(1))
                .andExpect(jsonPath("$.data.results[0].firstRelevantRank").value(2))
                .andExpect(jsonPath("$.data.results[0].retrievedResults.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long runId = ((Number) JsonPath.read(runResponse, "$.data.summary.id")).longValue();

        mockMvc.perform(get("/api/admin/knowledge-bases/{id}/evaluation/runs", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(runId));
        mockMvc.perform(get(
                                "/api/admin/knowledge-bases/{id}/evaluation/runs/{runId}",
                                knowledgeBaseId,
                                runId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].expectedDocuments.length()").value(2));

        mockMvc.perform(delete(
                                "/api/admin/knowledge-bases/{id}/documents/{documentId}",
                                knowledgeBaseId,
                                firstDocumentId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RAG_EVALUATION_CONFLICT"));

        long anotherKnowledgeBaseId = createKnowledgeBase(token, "其他知识库");
        mockMvc.perform(delete(
                                "/api/admin/knowledge-bases/{id}/evaluation/runs/{runId}",
                                anotherKnowledgeBaseId,
                                runId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RAG_EVALUATION_NOT_FOUND"));

        mockMvc.perform(delete(
                                "/api/admin/knowledge-bases/{id}/evaluation/runs/{runId}",
                                knowledgeBaseId,
                                runId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(
                                "/api/admin/knowledge-bases/{id}/evaluation/runs/{runId}",
                                knowledgeBaseId,
                                runId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RAG_EVALUATION_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/knowledge-bases/{id}/evaluation/runs", knowledgeBaseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete(
                                "/api/admin/knowledge-bases/{id}/evaluation/cases/{caseId}",
                                knowledgeBaseId,
                                caseId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
    }

    /**
     * 上传测试文档并直接推进到 READY，模拟已经完成 Elasticsearch 索引。
     */
    private long uploadReadyDocument(
            String token,
            long knowledgeBaseId,
            String filename,
            String content
    ) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes()
        );
        String response = mockMvc.perform(multipart(
                                "/api/admin/knowledge-bases/{id}/documents",
                                knowledgeBaseId
                        )
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long documentId = ((Number) JsonPath.read(response, "$.data.id")).longValue();
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setChunkCount(1);
        document.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.updateById(document);
        return documentId;
    }

    /**
     * 创建知识库并返回接口生成的主键。
     */
    private long createKnowledgeBase(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/admin/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "用于集成测试"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentCount").value(0))
                .andExpect(jsonPath("$.data.retrievalCandidateLimit").value(50))
                .andExpect(jsonPath("$.data.vectorMinSimilarity").value(0.000))
                .andExpect(jsonPath("$.data.retrievalResultLimit").value(5))
                .andExpect(jsonPath("$.data.isDefault").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /**
     * 直接创建指定角色用户并通过登录接口获取令牌。
     */
    private String createUserAndLogin(String username, UserRole role) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("知识库测试用户");
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    /**
     * 将访问令牌转换为标准 Authorization 请求头值。
     */
    private String bearer(String token) {
        return "Bearer " + token;
    }
}
