package com.anying.helpdesk.modelconfig;

import com.anying.helpdesk.modelconfig.factory.OpenAiCompatibleEndpoint;
import com.anying.helpdesk.modelconfig.factory.OpenAiCompatibleEndpointResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEndpointResolverTest {

    private final OpenAiCompatibleEndpointResolver resolver = new OpenAiCompatibleEndpointResolver();

    @Test
    void resolvesOpenAiStyleBaseUrlWithoutDuplicatingVersionPath() {
        OpenAiCompatibleEndpoint endpoint = resolver.resolve("https://proxy.example.com:8443/openai/v1");

        assertThat(endpoint.apiRoot()).isEqualTo("https://proxy.example.com:8443");
        assertThat(endpoint.chatCompletionsPath()).isEqualTo("/openai/v1/chat/completions");
    }

    @Test
    void resolvesProviderBaseUrlWithoutVersionPath() {
        OpenAiCompatibleEndpoint endpoint = resolver.resolve("https://api.deepseek.com");

        assertThat(endpoint.apiRoot()).isEqualTo("https://api.deepseek.com");
        assertThat(endpoint.chatCompletionsPath()).isEqualTo("/chat/completions");
    }
}
