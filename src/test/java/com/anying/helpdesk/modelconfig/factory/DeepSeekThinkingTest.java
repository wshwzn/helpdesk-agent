package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeepSeekThinkingTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 通过本地 HTTP 接收实际序列化请求，确认扩展字段位于厂商要求的 JSON 顶层。 */
    @ParameterizedTest
    @CsvSource({"enabled,low", "enabled,high", "disabled,high"})
    @DisplayName("DeepSeek 实际请求携带思考开关，开启时携带配置强度")
    void sendsConfiguredThinking(String type, String effort) throws Exception {
        var body = json.readTree(request(ModelVendor.DEEPSEEK, new DeepSeekThinkingProperties(type, effort)));
        assertThat(body.path("thinking").path("type").asText()).isEqualTo(type);
        assertThat(body.has("extra_body")).isFalse();
        if (type.equals("enabled")) assertThat(body.path("reasoning_effort").asText()).isEqualTo(effort);
        else assertThat(body.hasNonNull("reasoning_effort")).isFalse();
    }

    /** 共用 OpenAI 兼容工厂的其他厂商不能收到 DeepSeek 专用参数。 */
    @ParameterizedTest
    @EnumSource(value = ModelVendor.class, names = "DEEPSEEK", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("其他厂商不携带思考扩展字段")
    void leavesOtherVendorsUnchanged(ModelVendor vendor) throws Exception {
        var body = json.readTree(request(vendor, new DeepSeekThinkingProperties("enabled", "low")));
        assertThat(body.hasNonNull("thinking")).isFalse();
        assertThat(body.hasNonNull("reasoning_effort")).isFalse();
    }

    /** 检查默认值、覆盖值及非法开关，避免配置写错后静默启用另一种模式。 */
    @Test
    @DisplayName("思考配置支持默认值和覆盖值并拒绝非法开关")
    void bindsAndValidatesConfiguration() {
        var context = new ApplicationContextRunner().withUserConfiguration(BindingConfiguration.class);
        context.run(app -> assertThat(app.getBean(DeepSeekThinkingProperties.class))
                .isEqualTo(new DeepSeekThinkingProperties("enabled", "low")));
        context.withPropertyValues("app.model-config.deepseek.thinking-type=disabled",
                "app.model-config.deepseek.reasoning-effort=high")
                .run(app -> assertThat(app.getBean(DeepSeekThinkingProperties.class))
                        .isEqualTo(new DeepSeekThinkingProperties("disabled", "high")));
        context.withPropertyValues("app.model-config.deepseek.thinking-type=typo")
                .run(app -> assertThat(app).hasFailed());
    }

    /** 启动随机本地端口模拟厂商，验证真实客户端请求而不访问付费模型。 */
    private String request(ModelVendor vendor, DeepSeekThinkingProperties properties) throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"test","object":"chat.completion","created":1,"model":"test",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var secret = mock(SecretEncryptor.class);
            when(secret.decrypt("test-encrypted")).thenReturn("test-only-key");
            var config = new AiModelConfig();
            config.setVendor(vendor);
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setModelName("test");
            config.setApiKeyCiphertext("test-encrypted");
            var factory = new OpenAiCompatibleChatModelFactory(secret, new OpenAiCompatibleEndpointResolver(),
                    mock(ChatProperties.class), json, properties);
            assertThat(factory.create(config).call("测试请求")).isEqualTo("ok");
            return captured.get();
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeepSeekThinkingProperties.class)
    static class BindingConfiguration { }
}
