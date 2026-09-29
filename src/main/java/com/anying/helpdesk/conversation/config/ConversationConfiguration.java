package com.anying.helpdesk.conversation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChatProperties.class)
@RequiredArgsConstructor
public class ConversationConfiguration implements WebMvcConfigurer {

    private final ChatProperties chatProperties;

    /**
     * 设置 MVC 异步请求超时，避免较慢的模型回答被容器提前中断。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(chatProperties.streamTimeout().toMillis());
    }
}
