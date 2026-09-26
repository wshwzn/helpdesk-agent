package com.anying.helpdesk.modelconfig;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptionException;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.mapper.AiModelConfigMapper;
import com.anying.helpdesk.modelconfig.service.ModelConnectionTester;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminModelConfigControllerIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AiModelConfigMapper modelConfigMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecretEncryptor secretEncryptor;

    @Autowired
    private DefaultChatModelProvider defaultChatModelProvider;

    @MockitoBean
    private ModelConnectionTester modelConnectionTester;

    @Test
    void adminCreatesAndReadsEncryptedModelConfig() throws Exception {
        String token = createUserAndLogin("model_admin_one", UserRole.ADMIN);
        String apiKey = "sk-test-secret-1234";

        String response = createConfig(token, "DeepSeek Main", apiKey, true);
        Number id = JsonPath.read(response, "$.data.id");

        assertThat(response).doesNotContain(apiKey);
        mockMvc.perform(get("/api/admin/model-configs/{id}", id.longValue())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.vendor").value("CUSTOM"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****1234"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(apiKey)
                )));

        AiModelConfig stored = modelConfigMapper.selectById(id.longValue());
        assertThat(stored.getApiKeyCiphertext()).doesNotContain(apiKey);
        assertThat(secretEncryptor.decrypt(stored.getApiKeyCiphertext())).isEqualTo(apiKey);
    }

    @Test
    void customerCannotManageModelConfigs() throws Exception {
        String customerToken = registerCustomer("model_customer");

        mockMvc.perform(get("/api/admin/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void updateKeepsExistingApiKeyAndDefaultModelCannotBeDisabled() throws Exception {
        String token = createUserAndLogin("model_admin_two", UserRole.ADMIN);
        String apiKey = "sk-preserved-secret-5678";
        String created = createConfig(token, "Primary Model", apiKey, true);
        long id = ((Number) JsonPath.read(created, "$.data.id")).longValue();
        String originalCiphertext = modelConfigMapper.selectById(id).getApiKeyCiphertext();

        mockMvc.perform(put("/api/admin/model-configs/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Primary Model Updated",
                                  "vendor": "CUSTOM",
                                  "baseUrl": "https://api.example.com/v1/",
                                  "modelName": "example-chat-v2",
                                  "supportsStreaming": true,
                                  "supportsTools": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.example.com/v1"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****5678"));

        assertThat(modelConfigMapper.selectById(id).getApiKeyCiphertext()).isEqualTo(originalCiphertext);

        mockMvc.perform(post("/api/admin/model-configs/{id}/default", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));

        assertThat(defaultChatModelProvider.get().configId()).isEqualTo(id);
        assertThat(defaultChatModelProvider.get().modelName()).isEqualTo("example-chat-v2");

        mockMvc.perform(post("/api/admin/model-configs/{id}/disable", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_CONFIG_CONFLICT"));
    }

    @Test
    void disabledModelMustBeEnabledBeforeBecomingDefault() throws Exception {
        String token = createUserAndLogin("model_admin_three", UserRole.ADMIN);
        String created = createConfig(token, "Disabled Model", "sk-disabled-9999", false);
        long id = ((Number) JsonPath.read(created, "$.data.id")).longValue();

        mockMvc.perform(post("/api/admin/model-configs/{id}/default", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_CONFIG_CONFLICT"));

        mockMvc.perform(post("/api/admin/model-configs/{id}/enable", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void connectionTestReturnsSanitizedResult() throws Exception {
        String token = createUserAndLogin("model_admin_four", UserRole.ADMIN);
        String created = createConfig(token, "Test Model", "sk-never-return-this", true);
        long id = ((Number) JsonPath.read(created, "$.data.id")).longValue();

        doNothing().when(modelConnectionTester).test(any(AiModelConfig.class));
        mockMvc.perform(post("/api/admin/model-configs/{id}/test", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        doThrow(new IllegalStateException("provider leaked sk-never-return-this"))
                .when(modelConnectionTester).test(any(AiModelConfig.class));
        mockMvc.perform(post("/api/admin/model-configs/{id}/test", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-never-return-this")
                )));
    }

    @Test
    void presetVendorUsesBackendUrlAndListsAvailableVendors() throws Exception {
        String token = createUserAndLogin("model_admin_five", UserRole.ADMIN);

        mockMvc.perform(get("/api/admin/model-configs/vendors")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("OPENAI"))
                .andExpect(jsonPath("$.data[1].code").value("DEEPSEEK"))
                .andExpect(jsonPath("$.data[1].baseUrl").value("https://api.deepseek.com"))
                .andExpect(jsonPath("$.data[4].customBaseUrlRequired").value(true));

        mockMvc.perform(post("/api/admin/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DeepSeek Preset",
                                  "vendor": "DEEPSEEK",
                                  "modelName": "deepseek-chat",
                                  "apiKey": "sk-deepseek-test",
                                  "supportsStreaming": true,
                                  "supportsTools": true,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.protocol").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.vendor").value("DEEPSEEK"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.deepseek.com"));
    }

    @Test
    void customVendorRequiresBaseUrlAndPresetRejectsManualUrl() throws Exception {
        String token = createUserAndLogin("model_admin_six", UserRole.ADMIN);

        mockMvc.perform(post("/api/admin/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Invalid Custom",
                                  "vendor": "CUSTOM",
                                  "modelName": "custom-chat",
                                  "apiKey": "sk-custom-test",
                                  "supportsStreaming": true,
                                  "supportsTools": true,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODEL_CONFIG"));

        mockMvc.perform(post("/api/admin/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Invalid Preset",
                                  "vendor": "OPENAI",
                                  "baseUrl": "https://proxy.example.com/v1",
                                  "modelName": "gpt-test",
                                  "apiKey": "sk-openai-test",
                                  "supportsStreaming": true,
                                  "supportsTools": true,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODEL_CONFIG"));
    }

    @Test
    void encryptionUsesRandomIvAndDetectsTampering() {
        String first = secretEncryptor.encrypt("same-secret");
        String second = secretEncryptor.encrypt("same-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(secretEncryptor.decrypt(first)).isEqualTo("same-secret");
        String tampered = first.substring(0, first.length() - 2) + "AA";
        assertThatThrownBy(() -> secretEncryptor.decrypt(tampered))
                .isInstanceOf(SecretEncryptionException.class);
    }

    private String createConfig(String token, String name, String apiKey, boolean enabled) throws Exception {
        return mockMvc.perform(post("/api/admin/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "vendor": "CUSTOM",
                                  "baseUrl": "https://api.example.com/v1",
                                  "modelName": "example-chat",
                                  "apiKey": "%s",
                                  "supportsStreaming": true,
                                  "supportsTools": true,
                                  "enabled": %s
                                }
                                """.formatted(name, apiKey, enabled)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.apiKeyMasked").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createUserAndLogin(String username, UserRole role) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("Model Administrator");
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return login(username);
    }

    private String registerCustomer(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s",
                                  "displayName": "Model Customer"
                                }
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    private String login(String username) throws Exception {
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

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
