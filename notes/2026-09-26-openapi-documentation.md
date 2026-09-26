# OpenAPI 接口文档功能变更说明

## 实现的功能和规则

- 接入 springdoc-openapi 2.8.17，自动生成 OpenAPI 3 文档和 Swagger UI。
- Swagger UI 地址为 `/swagger-ui.html`，原始 OpenAPI JSON 地址为 `/v3/api-docs`。
- 文档页面允许匿名访问，实际业务接口仍按照 Spring Security 的 JWT 和角色规则鉴权。
- Swagger UI 支持 JWT Bearer 授权，填写登录返回的 `accessToken` 时无需手动添加 `Bearer` 前缀。
- 使用 `OPENAPI_ENABLED` 同时控制 JSON 文档和 Swagger UI，默认开启，生产环境可设置为 `false`。
- 现有认证、客户工单、客服工单和模型配置接口已按业务分组，并补充用途、参数、权限和 DTO 字段说明。
- 密码和 API Key 在文档模型中标记为只写字段。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `OpenApiConfig` | 定义 API 标题、版本、说明和 JWT Bearer 安全方案。 |
| `OpenApiDocumentationIntegrationTest` | 验证 OpenAPI JSON、Swagger UI、JWT 安全声明和敏感字段只写标记。 |
| `notes/2026-09-26-openapi-documentation.md` | 记录本次接口文档功能变更。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `pom.xml` | 引入 `springdoc-openapi-starter-webmvc-ui` 2.8.17。 |
| `SecurityConfig` | 匿名放行 OpenAPI JSON 和 Swagger UI 静态资源。 |
| `application.yaml` | 增加文档开关、页面地址和排序配置。 |
| 四个 Controller | 增加接口分组、用途、权限和参数说明。 |
| 请求、响应及分页 DTO | 增加字段说明、示例及敏感字段只写标记。 |
| `README.md` | 增加访问地址、JWT 调试方式和生产关闭方式。 |

## API 和数据库变化

- 新增文档端点 `GET /v3/api-docs` 和 Swagger UI 入口 `GET /swagger-ui.html`。
- 未增加或修改业务 API，也没有数据库结构变化。

## 验证结果

- `mvn test`：22 个测试通过，0 失败；其中 OpenAPI 专项测试 2 个。
- 确认 OpenAPI 文档包含登录、工单和模型配置路径。
- 确认受保护接口声明 `bearerAuth`，公开登录接口不错误声明 JWT 要求。
- 确认密码字段生成 `writeOnly: true`，Swagger UI 入口、HTML 页面和页面配置均可匿名访问。

## 当前限制与后续工作

- 当前只记录主要成功响应；可在接口稳定后进一步细化每个操作的 400、401、403、404 和 409 响应示例。
- 暂未按用户角色拆分多份 OpenAPI 文档，所有接口在同一页面中按标签分组。
- 后续新增接口时，需要同步补充 `@Operation`、安全要求和 DTO 字段说明。
