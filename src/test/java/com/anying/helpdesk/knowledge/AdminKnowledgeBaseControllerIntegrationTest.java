package com.anying.helpdesk.knowledge;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentIndexingService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .thenReturn(new ByteArrayInputStream(file.getBytes()));
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
     * 验证管理员可以提交自然语言问题并查看双路召回的融合排名。
     */
    @Test
    @DisplayName("管理员可以调试知识库混合检索")
    void adminSearchesKnowledgeBase() throws Exception {
        String token = createUserAndLogin("knowledge_admin_search", UserRole.ADMIN);
        when(knowledgeRetrievalService.search(anyLong(), any())).thenReturn(new KnowledgeSearchResponse(
                3L,
                "如何登录",
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
                                {"query":"如何登录","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bm25CandidateCount").value(2))
                .andExpect(jsonPath("$.data.vectorCandidateCount").value(3))
                .andExpect(jsonPath("$.data.results[0].rank").value(1))
                .andExpect(jsonPath("$.data.results[0].bm25Rank").value(2))
                .andExpect(jsonPath("$.data.results[0].vectorRank").value(1));
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
