# 工具调用持久化与最近一轮回放

## 实现功能与业务规则

- 每次 Tool Call 和对应 Tool Response 记录在 `agent_tool_calls` 的同一行。
- 工具执行前写入 `RUNNING`，成功后保存模型实际收到的结果并更新为 `SUCCESS`，失败后保存固定脱敏错误码并更新为 `FAILED`。
- 同一条最终 AI 回复触发的多次工具调用共享 `assistant_message_id`，通过线程安全的 `sequence_no` 保留执行顺序。
- 客户 ID、对话 ID 和 AI 消息 ID 只从服务端 `AgentExecutionContext` 取得，不属于模型工具参数。
- 下一轮对话只回放紧邻当前客户消息的上一条 AI 回复所关联的全部成功工具调用；失败调用只用于审计。
- 历史工具结果是快照，客户询问当前或最新状态时，系统提示词要求模型重新调用工具。

## 新增文件与职责

- `V7__create_agent_tool_calls.sql`：创建工具调用表、唯一约束和查询索引。
- `AgentToolCall.java`、`AgentToolCallStatus.java`：工具调用实体及状态枚举。
- `AgentToolCallMapper.java`：MyBatis-Plus 数据访问接口。
- `AgentToolCallService.java`：使用独立事务创建、完成、失败及查询工具记录。
- `RecordingToolCallback.java`：包装 Spring AI 工具回调，捕获模型参数和模型可见结果。
- `RecordingToolCallbackFactory.java`：批量创建记录型工具回调。
- `ConversationModelHistory.java`：封装普通消息和最近一轮成功工具调用。
- `RecordingToolCallbackTest.java`：验证成功、失败记录行为。
- `AgentToolCallServiceIntegrationTest.java`：验证 Flyway 表结构、多次调用顺序和成功记录查询。

## 修改文件与作用

- `AgentExecutionContext.java`：增加 AI 消息 ID 和线程安全工具序号。
- `ConversationPersistenceService.java`：准备对话时加载上一条 AI 回复的成功工具记录。
- `PreparedChatExchange.java`、`ConversationModelClient.java`：传递结构化模型历史。
- `SpringAiConversationModelClient.java`：注册记录型回调，并在最终 AI 回复前还原 Tool Call/Tool Response 消息对。
- `ConversationServiceImpl.java`：创建执行上下文时关联当前 AI 占位消息。
- `docs/database-schema.md`：增加工具调用表完整字段说明和 V7 迁移记录。

## API 与数据库变化

- REST API 请求和响应没有变化。
- 新增 `agent_tool_calls` 表；没有修改已有表。
- `call_id` 是应用生成的回放标识，不依赖厂商原始工具调用 ID。

## 验证

- 运行完整 Maven 测试，验证迁移、成功与失败落库、多次调用顺序、鉴权上下文以及最近一轮多工具回放。
- 原有认证、工单、模型配置、普通聊天和 SSE 流式聊天测试继续通过。

## 已知限制与后续工作

- 并行工具调用会按实际进入回调的顺序线性回放，不逐字复刻厂商的并行分组。
- 当前没有提供工具调用审计查询 API 或前端展示。
- 当前只回放最近一轮成功工具结果，更早记录保留在数据库用于审计。
- 写操作工具尚未开放；未来接入时还需增加客户确认和业务幂等控制。
