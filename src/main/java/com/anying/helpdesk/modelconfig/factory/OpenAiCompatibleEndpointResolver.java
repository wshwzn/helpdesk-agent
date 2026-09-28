package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class OpenAiCompatibleEndpointResolver {

    public OpenAiCompatibleEndpoint resolve(String sdkBaseUrl) {
        try {
            URI uri = new URI(sdkBaseUrl);
            URI apiRoot = new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    null,
                    null,
                    null
            );
            String basePath = uri.getRawPath();
            if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
                basePath = "";
            } else if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            return new OpenAiCompatibleEndpoint(
                    apiRoot.toString(),
                    basePath + "/chat/completions",
                    basePath + "/embeddings"
            );
        } catch (URISyntaxException exception) {
            throw new InvalidModelConfigException("invalid OpenAI-compatible baseUrl");
        }
    }
}
