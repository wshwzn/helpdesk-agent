package com.anying.helpdesk.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "智能工单助手 API",
                version = "v1",
                description = "面向外部客户服务场景的认证、工单处理和 AI 模型配置接口"
        )
)
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "登录后获取的 JWT accessToken，无需手动添加 Bearer 前缀"
)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
