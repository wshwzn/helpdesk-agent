package com.anying.helpdesk.modelconfig.factory;

public record OpenAiCompatibleEndpoint(
        String apiRoot,
        String chatCompletionsPath
) {
}
