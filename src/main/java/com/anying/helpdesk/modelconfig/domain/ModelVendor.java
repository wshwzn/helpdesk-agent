package com.anying.helpdesk.modelconfig.domain;

public enum ModelVendor {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    OLLAMA("Ollama", "http://localhost:11434/v1"),
    CUSTOM("自定义", null);

    private final String displayName;
    private final String presetBaseUrl;

    ModelVendor(String displayName, String presetBaseUrl) {
        this.displayName = displayName;
        this.presetBaseUrl = presetBaseUrl;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPresetBaseUrl() {
        return presetBaseUrl;
    }

    public boolean isCustomBaseUrlRequired() {
        return presetBaseUrl == null;
    }
}
