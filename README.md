# 智能工单助手

一个面向 SaaS 外部客户服务场景的智能工单助手后端。当前阶段已完成工单领域基础能力，后续 Agent 将通过工具调用这些接口完成工单创建、查询和状态流转。

## 环境要求

- Java 21
- Maven 3.9+
- MySQL 8.0+，默认端口 `3306`

## 本地启动

创建本地数据库：

```sql
CREATE DATABASE helpdesk_agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

将 `src/main/resources/application-local.example.yml` 复制为 `application-local.yml`，然后填写本地数据库账号和密码。`application-local.yml` 已被 Git 忽略，不会提交到远程仓库。

启动前提供 JWT 签名密钥和模型配置加密主密钥。模型配置密钥必须是 32 个随机字节的 Base64 编码；生产环境必须使用安全随机值并妥善备份：

```powershell
$env:JWT_SECRET = "请替换为至少32字节的随机密钥"
$keyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
$env:MODEL_CONFIG_ENCRYPTION_KEY = [Convert]::ToBase64String($keyBytes)
mvn test
mvn spring-boot:run
```

应用启动时，Flyway 会自动执行 `src/main/resources/db/migration` 中尚未执行的数据库迁移。

## 接口文档

本地启动后可以通过 Swagger UI 查看和调试接口：

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

登录后复制响应中的 `accessToken`，点击 Swagger UI 右上角的 **Authorize**，直接粘贴令牌即可；无需手动添加 `Bearer` 前缀。接口文档页面本身可以公开访问，但执行客户、客服或管理员接口仍会校验 JWT 及角色。

生产环境可以关闭接口文档：

```powershell
$env:OPENAPI_ENABLED = "false"
```

## 数据库表

完整字段、索引、枚举取值和表关系说明见 [`docs/database-schema.md`](docs/database-schema.md)。

- `tickets`：工单业务表，保存工单编号、标题、问题描述、优先级、状态、创建人和创建时间等信息。
- `users`：用户表，保存登录账号、BCrypt 密码哈希、显示名称、角色和启用状态。
- `ai_model_configs`：模型配置表，保存协议、厂商、基础地址、模型名称、能力开关和 AES-GCM 加密后的 API Key。
- `conversations`：客户对话表，保存对话归属、标题、状态和最近消息时间。
- `chat_messages`：对话消息表，保存客户消息、AI 回答、生成状态、模型快照和 Token 用量。
- `flyway_schema_history`：Flyway 自动创建的迁移记录表，用于记录每个 SQL 迁移脚本是否执行成功，防止重复建表。请勿手动修改或删除其中的数据。

## 登录认证

公开注册只会创建 `CUSTOMER` 客户账号：

```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "customer01",
  "password": "Password123",
  "displayName": "测试客户"
}
```

登录接口返回 JWT：

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "customer01",
  "password": "Password123"
}
```

调用受保护接口时携带访问令牌：

```http
Authorization: Bearer <accessToken>
```

当前尚未实现管理员创建内部账号的接口。本地开发时可先注册账号，再在数据库中将其 `role` 更新为 `SUPPORT` 或 `ADMIN`，重新登录后即可获得对应令牌。

## 模型配置

模型统一使用 OpenAI 兼容协议。管理员选择内置厂商时，后端自动使用预设地址；选择 `CUSTOM` 时才需要填写 SDK 基础地址。创建配置时的 API Key 会使用 AES-256-GCM 加密，后续接口只返回尾号掩码。

查询可选厂商及其预设地址：

```http
GET /api/admin/model-configs/vendors
Authorization: Bearer <管理员 accessToken>
```

使用 DeepSeek 预设创建配置：

```http
POST /api/admin/model-configs
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "name": "DeepSeek 主模型",
  "vendor": "DEEPSEEK",
  "modelName": "deepseek-chat",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": true,
  "supportsTools": true,
  "enabled": true
}
```

当前内置厂商为 `OPENAI`、`DEEPSEEK`、`QWEN` 和 `OLLAMA`。接入代理服务或其他兼容厂商时使用 `CUSTOM`，并填写类似 `https://proxy.example.com/v1` 的基础地址；不要填写以 `/chat/completions` 结尾的完整接口地址：

```json
{
  "name": "自定义兼容模型",
  "vendor": "CUSTOM",
  "baseUrl": "https://proxy.example.com/v1",
  "modelName": "custom-chat",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": true,
  "supportsTools": true,
  "enabled": true
}
```

管理接口：

```http
GET  /api/admin/model-configs
GET  /api/admin/model-configs/vendors
GET  /api/admin/model-configs/{id}
PUT  /api/admin/model-configs/{id}
POST /api/admin/model-configs/{id}/test
POST /api/admin/model-configs/{id}/default
POST /api/admin/model-configs/{id}/enable
POST /api/admin/model-configs/{id}/disable
```

更新配置时省略 `apiKey` 会保留原密钥。接口响应中的 `protocol` 固定为 `OPENAI_COMPATIBLE`，`vendor` 用于区分地址预设；它们不是不同 SDK。默认模型必须处于启用状态，也不能直接禁用；应先切换默认模型。连接测试会向模型发送一个极短提示词，可能产生少量费用。

## 智能对话接口

发送消息前需要先配置并启用一个默认模型。创建客户对话：

```http
POST /api/conversations
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "title": "登录问题咨询"
}
```

向对话发送消息并同步等待 AI 回答：

```http
POST /api/conversations/{id}/messages
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "content": "为什么我无法登录控制台？"
}
```

其他对话接口：

```http
GET /api/conversations?page=1&size=20&status=ACTIVE
GET /api/conversations/{id}
GET /api/conversations/{id}/messages?page=1&size=50
```

客户只能访问自己的对话。系统默认携带最近 20 条已完成消息作为模型上下文，可通过 `CHAT_HISTORY_LIMIT` 调整为 1 至 100。模型调用失败时接口返回 `502 CHAT_GENERATION_FAILED`，客户消息和一条 `FAILED` AI 消息仍会保留，厂商原始异常不会返回客户端。当前接口为非流式响应。

## 工单接口

创建工单：

```http
POST /api/tickets
Content-Type: application/json
Authorization: Bearer <客户 accessToken>

{
  "title": "无法连接公司 VPN",
  "description": "VPN 客户端连接时提示超时。",
  "priority": "HIGH"
}
```

查询工单详情：

```http
GET /api/tickets/{id}
```

分页查询工单，可按创建人和状态筛选：

```http
GET /api/tickets?page=1&size=20&status=PENDING
```

客户只能查询自己的工单，创建人编号从 JWT 获取。优先级可选值为 `LOW`、`MEDIUM`、`HIGH`；当前新工单的初始状态为 `PENDING`。

## 工单处理流程

工单状态按照以下规则流转：

```text
PENDING（待处理）→ PROCESSING（处理中）→ RESOLVED（已解决）
       └────────→ CANCELLED（已取消）←────────┘
```

客户可以取消自己创建且处于待处理或处理中状态的工单。处理中工单被取消后会保留已分配的客服信息，以便审计；已解决和已取消的工单不能再次取消：

```http
POST /api/tickets/{id}/cancel
Authorization: Bearer <客户 accessToken>

{
  "reason": "问题已经自行解决"
}
```

客服可以查询工单队列并领取待处理工单：

```http
GET  /api/support/tickets?status=PENDING
POST /api/support/tickets/{id}/claim
Authorization: Bearer <客服 accessToken>
```

只有领取该工单的客服可以填写处理结果并完结工单：

```http
POST /api/support/tickets/{id}/resolve
Authorization: Bearer <客服 accessToken>

{
  "resolution": "已重置客户的上传配额"
}
```

Spring Security 根据 JWT 中的角色限制访问：`CUSTOMER` 可以操作自己的工单，`SUPPORT` 可以访问客服处理接口。客户和客服编号均从已验证的 JWT 中获取，不接受客户端传值。
