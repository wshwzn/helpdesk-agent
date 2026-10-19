package com.anying.helpdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentIsPublicAndContainsJwtProtectedOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.components.schemas.SendMessageRequest.properties.mode.default").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.info.title").value("企业知识助手 API"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.summary").value("用户登录"))
                .andExpect(jsonPath("$.paths['/api/tickets'].post.summary").value("创建工单"))
                .andExpect(jsonPath("$.paths['/api/conversations/{id}/messages'].post.summary")
                        .value("发送消息并获取 AI 回答"))
                .andExpect(jsonPath("$.paths['/api/conversations/{id}/messages/stream'].post.summary")
                        .value("发送消息并流式获取 AI 回答"))
                .andExpect(jsonPath("$.paths['/api/admin/model-configs'].get.summary")
                        .value("查询全部模型配置"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases'].post.summary")
                        .value("创建知识库"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases/{id}/documents'].post"
                        + ".requestBody.content['multipart/form-data'].schema.properties.file.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases/{knowledgeBaseId}/documents/"
                        + "{documentId}/chunk-preview'].post.summary")
                        .value("预览文档分块"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases/{id}/search'].post.summary")
                        .value("调试知识库混合检索"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases/{id}/retrieval-settings'].put.summary")
                        .value("更新知识库检索参数"))
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-bases/{id}/default'].post.summary")
                        .value("设为默认知识库"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/tickets'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/conversations'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-answer-runs'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/admin/knowledge-answer-runs/{runId}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.password.writeOnly")
                        .value(true))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.default").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.username.default")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.password.default")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateModelConfigRequest.default")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateModelConfigRequest.properties.enabled.default")
                        .doesNotExist());
    }

    @Test
    void swaggerUiEntryIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }
}
