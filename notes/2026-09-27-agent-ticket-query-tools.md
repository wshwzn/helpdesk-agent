# Agent 工单查询工具

## 实现功能与业务规则

- 为每次聊天创建不可变的 Agent 执行上下文，保存当前客户 ID 和对话 ID。
- Agent 可以调用 `list_my_tickets` 查询当前客户最近 20 张工单，并可按状态筛选。
- Agent 可以调用 `get_my_ticket` 查询当前客户的一张工单详情。
- 客户 ID 不属于模型可填写的工具参数，只能由后端从聊天请求中取得并通过 Spring AI `ToolContext` 传递。
- 工具仍调用原有 `TicketService`，详情查询继续执行工单归属校验，模型不能绕过业务权限。
- 本轮只提供只读工具，未开放创建、取消、领取或解决工单等写操作。

## 新增文件与职责

- `agent/AgentExecutionContext.java`：封装一次 Agent 调用的可信客户和对话身份，并负责写入、读取工具上下文。
- `agent/tool/CustomerTicketTools.java`：声明客户可用的两个 Spring AI 工单查询工具。
- `agent/tool/CustomerTicketToolResult.java`：定义模型可见的工单数据，排除客户 ID 和客服 ID 等内部身份字段。
- `CustomerTicketToolsTest.java`：验证身份字段不会出现在工具参数结构中，并验证可信身份传递和缺失身份拒绝逻辑。

## 修改文件与作用

- `ConversationModelClient.java`：同步和流式模型调用新增 Agent 执行上下文参数。
- `SpringAiConversationModelClient.java`：注册工单工具，将可信上下文放入模型调用选项，并补充工具使用提示词。
- `ConversationServiceImpl.java`：在进入模型调用前根据已鉴权的客户和已校验的对话创建执行上下文。
- `SpringAiConversationModelClientTest.java`：验证工具和上下文已加入同步、流式调用共用的提示对象。
- `ConversationControllerIntegrationTest.java`：适配模型客户端签名，并验证聊天请求传递正确对话上下文。

## API 与数据库变化

- REST API 路径、请求体和响应体没有变化。
- 没有新增或修改数据库表。
- 工具输入只包含 `status` 或 `ticketId`，不包含 `customerId`、`conversationId`、JWT 或其他认证信息。
- 工具结果也不会向模型返回 `creatorId` 或 `assigneeId`。

## 验证

- 工具参数结构测试覆盖身份字段隔离。
- 工具调用测试覆盖列表、详情和缺少可信上下文三种情况。
- 运行完整 Maven 测试，确认原有认证、工单、模型配置、普通对话和 SSE 对话功能不回归。

## 已知限制与后续工作

- 当前详情工具使用数据库工单主键，后续可增加按对外工单编号查询。
- 暂未保存独立的工具调用审计记录。
- 暂未提供需要客户二次确认的写操作工具。
