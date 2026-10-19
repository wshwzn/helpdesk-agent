package com.anying.helpdesk.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "企业知识助手 API",
                version = "v1",
                description = "面向企业内部的可信知识问答、资料维护与模型配置接口；保留旧工单接口兼容历史业务"
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
