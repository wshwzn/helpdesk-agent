# SSE 流式对话功能变更说明

## 实现的功能和业务规则

- 新增与同步消息接口并列的 SSE 流式接口，按照 `start`、`delta`、`done` 或 `error` 顺序返回事件。
- 流式调用前校验对话归属、对话状态、默认模型以及 `supportsStreaming` 能力；预检失败时不保存消息。
- 客户消息和 `PENDING` AI 消息通过短事务先行保存，模型流式调用在数据库事务外执行。
- 正常结束后合并全部文本并保存模型快照和厂商返回的 Token 用量；模型异常时保存 `MODEL_CALL_FAILED`，客户端中断时尝试保存 `STREAM_CANCELLED`。
- 流开始后的错误通过脱敏 `error` 事件返回，不泄露 API Key 或厂商原始异常。
- MVC 异步请求默认超时设置为 5 分钟，并支持通过 `APP_CHAT_STREAM_TIMEOUT` 调整。
- 保留原同步消息接口，方便接口调试以及不支持流式输出的模型使用。
- 项目规范新增中文方法注释要求：新写方法使用简短中文 JavaDoc，测试方法优先使用中文 `@DisplayName`，方法内部只注释不直观的规则。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `ChatStreamEvent` | 定义 start、delta、done、error 四种流式事件的数据结构。 |
| `ChatStreamChunk` | 隔离 Spring AI 响应，承载单次文本增量和 Token 用量。 |
| `ModelStreamingNotSupportedException` | 表示默认模型配置未开启流式能力。 |
| `notes/10-SSE流式对话.md` | 记录本轮流式对话实现。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `AGENTS.md` | 增加新方法中文注释和测试中文名称规范。 |
| `ConversationController` | 新增 SSE 消息接口并设置事件名称。 |
| `ChatProperties`、`ConversationConfiguration` | 增加可配置的流式请求超时并应用到 MVC 异步处理。 |
| `ConversationService`、`ConversationServiceImpl` | 编排流式模型调用、结果合并、完成、失败和连接取消处理。 |
| `ConversationModelClient`、`SpringAiConversationModelClient` | 增加 Spring AI 流式调用和响应转换。 |
| `ResolvedChatModel`、`DefaultChatModelProvider` | 将模型配置的流式能力传递到对话服务。 |
| `GlobalExceptionHandler` | 增加模型不支持流式输出的 409 错误映射。 |
| `ConversationControllerIntegrationTest` | 覆盖流式成功、模型失败、消息持久化和能力校验。 |
| `SpringAiConversationModelClientTest` | 验证提示消息、文本增量和 Token 用量转换。 |
| `OpenApiDocumentationIntegrationTest` | 验证流式接口已进入 OpenAPI 文档。 |
| `README.md` | 增加流式接口调用方式、事件顺序和失败规则。 |

## API 和数据库变化

- 新增 `POST /api/conversations/{id}/messages/stream`，请求体沿用 `SendMessageRequest`，响应类型为 `text/event-stream`。
- 默认模型未开启流式能力时返回 `409 MODEL_STREAMING_NOT_SUPPORTED`；调用方应发送 `Accept: text/event-stream, application/json` 以兼容预检 JSON 错误。
- 未新增或修改数据库表、字段、索引和约束。

## 测试和验证

- 对话和模型客户端专项测试覆盖完整 SSE 顺序、中文文本、Token 用量、脱敏错误事件、失败消息入库和能力开关。
- `mvn test`：33 个测试通过，0 失败、0 错误、0 跳过。

## 当前限制与后续工作

- SSE 接口仍是单实例内的实时连接，尚未实现跨实例事件转发、断线续传和事件 ID 重放。
- 客户端断开后的失败标记采用异步尽力执行；后续仍需增加定时任务，兜底清理进程异常退出留下的长期 `PENDING` 消息。
- 不同 OpenAI 兼容厂商可能不返回流式 Token 用量，对应字段允许为空。
