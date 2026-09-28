# 智能工单助手

一个面向 SaaS 外部客户服务场景的智能工单助手后端。Agent 可以查询客户工单，并在需要人工处理时生成可修改、可确认的工单草稿；只有客户确认后才会创建真实工单。

## 环境要求

- Java 21
- Maven 3.9+
- MySQL 8.0+，默认端口 `3306`
- Elasticsearch 9.x，用于保存全文和向量检索 Chunk
- 可选：阿里云 OSS，用于保存知识库原始文档；未配置时使用本地目录

## 本地启动

创建本地数据库：

```sql
CREATE DATABASE helpdesk_agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

将 `src/main/resources/application-local.example.yml` 复制为 `application-local.yml`，然后填写本地数据库账号和密码。再将 `.env.example` 复制为 `.env`，按需填写安全配置；这两个本地文件均已被 Git 忽略，不会提交到远程仓库。Spring Boot 通过 `optional:file:.env[.properties]` 读取扩展名为空的 `.env` 文件。

启动前提供 JWT 签名密钥和模型配置加密主密钥。模型配置密钥必须是 32 个随机字节的 Base64 编码；生产环境必须使用安全随机值并妥善备份：

```powershell
$env:JWT_SECRET = "请替换为至少32字节的随机密钥"
$keyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
$env:MODEL_CONFIG_ENCRYPTION_KEY = [Convert]::ToBase64String($keyBytes)
mvn test
mvn spring-boot:run
```

知识库文档默认写入 `./data/objects`。使用阿里云 OSS 时，在 `.env` 中配置以下项目；AccessKey 建议使用仅允许操作目标 Bucket 指定前缀的 RAM 用户凭据：

```properties
STORAGE_PROVIDER=aliyun-oss
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
OSS_BUCKET=your-private-bucket
OSS_ACCESS_KEY_ID=
OSS_ACCESS_KEY_SECRET=
```

本地 Elasticsearch 开启 HTTPS 时，填写连接账号以及安装目录生成的 CA 证书路径：

```properties
ELASTIC_URI=https://localhost:9200
ELASTIC_USERNAME=elastic
ELASTIC_PASSWORD=<本地 Elasticsearch 密码>
ELASTIC_CA_CERT_PATH=D:/elasticsearch-9.5.4/config/certs/http_ca.crt
ELASTIC_INDEX_NAME=helpdesk_knowledge_chunks_v1
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
- `agent_tool_calls`：工具调用记录表，保存工具参数、模型可见结果、状态和执行顺序。
- `agent_pending_actions`：待确认操作表，保存 AI 生成的工单草稿及确认、取消、过期和执行状态。
- `knowledge_bases`：知识库表，保存名称、说明和创建信息。
- `knowledge_documents`：知识库文档元数据表，保存对象位置、文件摘要和处理状态；原始内容不进入 MySQL。
- `flyway_schema_history`：Flyway 自动创建的迁移记录表，用于记录每个 SQL 迁移脚本是否执行成功，防止重复建表。请勿手动修改或删除其中的数据。

## 知识库管理

管理员可以管理知识库、上传原始文档并异步建立 Elasticsearch 索引。上传支持 `txt`、`md`、`pdf`、`docx`，单文件最大 20 MB；上传成功后状态为 `PENDING`，触发索引后依次进入 `PROCESSING`、`READY` 或 `FAILED`。

```http
POST   /api/admin/knowledge-bases
GET    /api/admin/knowledge-bases
GET    /api/admin/knowledge-bases/{id}
PUT    /api/admin/knowledge-bases/{id}/retrieval-settings
DELETE /api/admin/knowledge-bases/{id}
POST   /api/admin/knowledge-bases/{id}/documents
GET    /api/admin/knowledge-bases/{id}/documents?page=1&size=20
DELETE /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}
POST   /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunk-preview?page=1&size=20
POST   /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/index
POST   /api/admin/knowledge-bases/{id}/search
```

文档上传接口使用 `multipart/form-data`，字段名为 `file`。知识库中存在文档时不能直接删除；应先逐个删除文档，确保对象存储内容同步清理。

分块预览接口会从本地目录或 OSS 下载原文，通过 Apache Tika 解析为纯文本，再分页返回实际分块。默认每块最多 1000 个字符，相邻块重叠 150 个字符，并优先在段落、换行和中英文句末处分割。可以通过 `.env` 调整：

```properties
KNOWLEDGE_MAX_EXTRACTED_CHARACTERS=2000000
KNOWLEDGE_CHUNK_SIZE=1000
KNOWLEDGE_CHUNK_OVERLAP=150
KNOWLEDGE_EMBEDDING_BATCH_SIZE=16
```

预览只用于检查解析和切块效果，会在每次请求时重新处理文档，不修改处理状态。索引接口会预检默认 Embedding 模型和 ES 映射，然后返回 `202 Accepted`；后台完成下载、解析、分块、批量向量化和写入。重复触发 `READY` 或 `FAILED` 文档会覆盖该文档原有 Chunk；`PROCESSING` 文档拒绝重复触发和删除。

混合检索接口会为问题生成查询向量，通过一次 Elasticsearch `_msearch` 分别执行 BM25 全文检索和 KNN 向量检索，再在应用层使用 RRF 去重融合。当前本地 ES 的 Basic 许可证不包含原生 RRF，因此应用层融合可避免商业许可证依赖。每个知识库独立维护检索参数，默认单路候选上限为 50、向量相似度下限为 0、最终结果上限为 5：

```http
PUT /api/admin/knowledge-bases/{id}/retrieval-settings
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "candidateLimit": 50,
  "vectorMinSimilarity": 0.650,
  "resultLimit": 5
}
```

`candidateLimit` 允许 1～200，`vectorMinSimilarity` 必填且允许 0～1，`resultLimit` 允许 1～50 且不能超过候选上限。相似度过滤始终启用，0 表示过滤负相关向量。KNN 的 `num_candidates` 不开放配置，由后端固定为 `candidateLimit × 2`。参数修改后立即影响后续查询，不需要重新索引。

```http
POST /api/admin/knowledge-bases/{id}/search
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "query": "如何配置模型接口？"
}
```

响应会回显本次生效的三个参数，并给出融合名次、RRF 分数以及 Chunk 在 BM25、KNN 两路中的名次和原始分数，便于调试召回效果。向量检索只使用由当前默认 Embedding 配置生成的 Chunk；切换默认向量模型后，应重新索引文档并重新评估相似度阈值。

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

当前访问令牌有效期为 240 小时，方便本地联调时保持登录状态；生产部署应根据安全要求缩短有效期，并补充刷新令牌和主动撤销机制。修改有效期后，已经签发的旧令牌不会自动延长，需要重新登录。

调用受保护接口时携带访问令牌：

```http
Authorization: Bearer <accessToken>
```

当前尚未实现管理员创建内部账号的接口。本地开发时可先注册账号，再在数据库中将其 `role` 更新为 `SUPPORT` 或 `ADMIN`，重新登录后即可获得对应令牌。

## 模型配置

模型统一使用 OpenAI 兼容协议。管理员选择内置厂商时，后端自动使用预设地址；选择 `CUSTOM` 时才需要填写 SDK 基础地址。创建配置时的 API Key 会使用 AES-256-GCM 加密，后续接口只返回尾号掩码。

按模型用途查询可选厂商及其预设地址；省略 `modelType` 时默认查询聊天模型厂商：

```http
GET /api/admin/model-configs/vendors?modelType=CHAT
Authorization: Bearer <管理员 accessToken>
```

使用 DeepSeek 预设创建配置：

```http
POST /api/admin/model-configs
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "name": "DeepSeek 主模型",
  "modelType": "CHAT",
  "vendor": "DEEPSEEK",
  "modelName": "deepseek-chat",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": true,
  "supportsTools": true,
  "enabled": true
}
```

Embedding 模型不使用聊天厂商的地址预设，必须选择 `CUSTOM`、自行填写 OpenAI 兼容基础地址并声明实际输出维度；流式和工具能力必须为 `false`：

```json
{
  "name": "知识库向量模型",
  "modelType": "EMBEDDING",
  "vendor": "CUSTOM",
  "baseUrl": "https://proxy.example.com/v1",
  "modelName": "text-embedding-model",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": false,
  "supportsTools": false,
  "embeddingDimensions": 1024,
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

更新配置时省略 `apiKey` 会保留原密钥，更新 Embedding 配置时省略 `embeddingDimensions` 会保留原维度。创建请求省略 `modelType` 时按 `CHAT` 兼容，更新时省略则保留当前类型。接口响应中的 `protocol` 固定为 `OPENAI_COMPATIBLE`；`vendor` 只为聊天模型提供地址预设，Embedding 固定使用 `CUSTOM`。`CHAT` 和 `EMBEDDING` 分别维护一个默认模型。默认模型必须处于启用状态，也不能直接禁用；应先切换默认模型。连接测试会按类型发送一个极短对话或向量请求，可能产生少量费用。

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

默认模型配置的 `supportsStreaming` 为 `true` 时，可以通过 SSE 流式获取回答。调用方应同时声明接受 SSE 和 JSON：正常生成时返回事件流，权限、参数或模型能力预检失败时仍可接收标准 JSON 错误响应。

```http
POST /api/conversations/{id}/messages/stream
Authorization: Bearer <客户 accessToken>
Content-Type: application/json
Accept: text/event-stream, application/json

{
  "content": "为什么我无法登录控制台？"
}
```

事件按照以下顺序返回：

```text
event: start   已保存客户消息，并创建 PENDING 状态的 AI 消息
event: delta   本次新增的回答文本，可能出现多次
event: action_required   AI 已生成需要客户确认的工单草稿
event: done    回答完成，携带最终保存的 AI 消息
event: error   生成失败，携带脱敏错误码和说明
```

流开始后的模型异常不会改写已经发送的 HTTP 状态码，而是通过 `error` 事件通知客户端；对应 AI 消息会保存为 `FAILED`。客户端主动中断连接时，服务端会尝试将尚未完成的 AI 消息标记为 `STREAM_CANCELLED`。

流式请求默认允许持续 5 分钟，可通过环境变量 `APP_CHAT_STREAM_TIMEOUT` 调整，例如设置为 `10m`。

其他对话接口：

```http
GET /api/conversations?page=1&size=20&status=ACTIVE
GET /api/conversations/{id}
GET /api/conversations/{id}/messages?page=1&size=50
```

客户只能访问自己的对话。系统默认携带最近 20 条已完成消息作为模型上下文，可通过 `CHAT_HISTORY_LIMIT` 调整为 1 至 100。同步接口调用失败时返回 `502 CHAT_GENERATION_FAILED`；流式接口开始传输后调用失败时返回 `error` 事件。两种方式都会保留客户消息和一条 `FAILED` AI 消息，且不会向客户端暴露厂商原始异常。

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

## AI 工单确认流程

当 AI 判断需要人工处理时，会调用 `prepare_create_ticket` 工具生成草稿。同步对话响应通过 `pendingActions` 返回草稿，流式对话通过 `action_required` 事件返回；此时真实工单尚未创建。

查询对话中的操作卡片：

```http
GET /api/agent/actions?conversationId={conversationId}&status=PENDING
Authorization: Bearer <客户 accessToken>
```

客户可以在确认前修改标题和描述，不能修改 AI 建议的优先级：

```http
PATCH /api/agent/actions/{actionId}
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "title": "重置密码后仍无法登录",
  "description": "今天重置两次密码后仍提示密码错误。",
  "version": 0
}
```

确认或取消时必须携带卡片最新的 `version`：

```http
POST /api/agent/actions/{actionId}/confirm
POST /api/agent/actions/{actionId}/cancel

{
  "version": 1
}
```

草稿有效期为 24 小时。确认接口具有幂等性，重复确认只返回第一次创建的工单，不会重复建单。

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
