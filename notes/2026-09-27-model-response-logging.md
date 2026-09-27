# 模型调用协议日志开关

## 实现功能与业务规则

- 新增 `app.chat.log-model-protocol` 配置，默认值为 `false`。
- 开关开启后，记录 OpenAI 兼容协议的完整请求、同步响应和流式响应数据块。
- 可以观察模型返回的 `assistant.tool_calls`，以及工具执行后第二次请求中的 `role: "tool"` 消息。
- 日志在 Spring AI 协议对象层生成，不读取 HTTP 请求头，因此不会记录模型 API Key、JWT 或 Authorization 请求头。
- 协议日志包含客户问题、工具参数和工具结果，只应用于本地调试，不应在生产环境长期开启。

## 新增和修改文件

- `ChatProperties.java`：增加模型协议日志开关。
- `ProtocolLoggingOpenAiApi.java`：在同步和流式 OpenAI 兼容调用边界序列化请求、响应对象。
- `OpenAiCompatibleChatModelFactory.java`：开关开启时创建协议日志客户端。
- `application-local.example.yml`：增加关闭状态的配置示例和安全提醒。
- 本机忽略提交的 `application-local.yml` 已将开关设为 `true`，重启程序后生效。

## API 与数据库变化

- REST API 没有变化。
- 数据库表和 Flyway 迁移没有变化。

## 验证与限制

- 运行完整 Maven 测试，确认日志开关不会影响聊天持久化和 SSE 事件。
- 当前记录 Spring AI 发送和接收的协议 JSON，不记录 HTTP 状态行和响应头。
- 流式模式会逐条记录 SSE 数据块，日志量明显大于普通业务日志。
- 后续增加工具调用持久化后，工具名称、参数和结果应使用独立的审计日志，不与模型文字日志混在一起。
