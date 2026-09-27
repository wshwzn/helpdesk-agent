# 模型回复日志开关

## 实现功能与业务规则

- 新增 `app.chat.log-model-content` 配置，默认值为 `false`。
- 开关开启后，同步和 SSE 流式调用都会在模型生成完成时记录一次完整回复。
- 日志同时记录对话 ID、模型名称和 `sync`/`stream` 调用模式，便于观察 Agent 最终回答。
- 日志不会主动记录模型 API Key、JWT、Authorization 请求头或厂商完整 HTTP 请求。
- 模型回复可能包含客户问题和业务数据，因此该开关只应用于本地调试，不应在生产环境长期开启。

## 新增和修改文件

- `ChatProperties.java`：增加模型内容日志开关。
- `ConversationServiceImpl.java`：在同步完成和流式聚合完成后输出模型最终回复。
- `application-local.example.yml`：增加关闭状态的配置示例和安全提醒。
- 本机忽略提交的 `application-local.yml` 已将开关设为 `true`，重启程序后生效。

## API 与数据库变化

- REST API 没有变化。
- 数据库表和 Flyway 迁移没有变化。

## 验证与限制

- 运行完整 Maven 测试，确认日志开关不会影响聊天持久化和 SSE 事件。
- 当前记录的是工具执行完成后模型生成的最终文字，不包含模型原始 HTTP 报文和工具调用协议消息。
- 后续增加工具调用持久化后，工具名称、参数和结果应使用独立的审计日志，不与模型文字日志混在一起。
