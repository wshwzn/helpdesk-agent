# Agent 工单查询工具

## 实现功能与业务规则

- 为每次聊天创建不可变的 Agent 执行上下文，保存当前客户 ID 和对话 ID。
- Agent 可以调用 `list_my_tickets` 查询当前客户最近 20 张工单，并可按状态筛选。
- Agent 可以调用 `get_my_ticket` 按客户可见的唯一工单号查询当前客户的一张工单详情。
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
- `TicketService.java`：增加按唯一工单号查询并校验客户归属的方法。
- `TicketNotFoundException.java`：支持使用工单号描述未找到错误。

## API 与数据库变化

- REST API 路径、请求体和响应体没有变化。
- 没有新增或修改数据库表。
- 工具输入只包含 `status` 或 `ticketNo`，不包含数据库主键、`customerId`、`conversationId`、JWT 或其他认证信息。
- 工具结果不会向模型返回内部 `id`、`creatorId` 或 `assigneeId`。

## 验证

- 工具参数结构测试覆盖身份字段隔离。
- 工具调用测试覆盖列表、详情和缺少可信上下文三种情况。
- 工单集成测试覆盖工单号大小写标准化和跨客户归属校验。
- 运行 `mvn -q clean test`，共 46 个测试通过，确认原有认证、工单、模型配置、普通对话和 SSE 对话功能不回归。

## 已知限制与后续工作

- 列表工具固定返回最近 20 张工单，暂未让模型控制分页。
- 详情工具要求完整工单号，不提供模糊匹配，避免误查其他工单。
