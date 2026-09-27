# AI 工单草稿与客户确认

## 实现功能与业务规则

- 新增 `prepare_create_ticket` 工具。AI 在客户要求人工处理或无法继续自动解决时生成工单草稿，不直接创建真实工单。
- 草稿包含标题、描述、建议优先级和优先级理由；优先级缺失时后端使用 `MEDIUM`。
- 客户可以修改待确认草稿的标题和描述，不能修改优先级、客户身份或操作类型。
- 客户确认后，待确认操作与真实工单在同一事务中完成；乐观锁和工单唯一关联保证重复或并发确认不会重复建单。
- 客户可以取消草稿。草稿创建 24 小时后过期，已执行、已取消或已过期的草稿不能再修改或执行。
- AI 最终回答生成失败或流被取消时，已经生成的草稿转为 `INVALIDATED`，不能继续确认。
- 同一条 AI 消息最多生成一个创建工单操作，避免模型重复调用工具产生多张确认卡片。
- 同步聊天通过 `pendingActions` 返回卡片；SSE 在 `done` 前发送 `action_required` 事件。页面刷新后可按对话重新查询操作状态。

## 新增文件与职责

- `V8__create_agent_pending_actions.sql`：创建待确认操作表、唯一约束和查询索引。
- `AgentPendingAction.java`、`PendingActionType.java`、`PendingActionStatus.java`：待确认操作实体和枚举。
- `AgentPendingActionMapper.java`：MyBatis-Plus 数据访问接口。
- `PendingActionService.java`：负责草稿生成、查询、修改、确认、取消、过期和幂等执行。
- `PendingActionController.java`：提供客户待确认操作 REST API。
- `PendingActionResponse.java`、`UpdatePendingActionRequest.java`、`PendingActionVersionRequest.java`、`PendingActionExecutionResponse.java`：接口请求和响应模型。
- `CustomerTicketActionTools.java`、`PrepareTicketToolResult.java`：向模型提供安全的工单草稿工具及结构化结果。
- 待确认操作异常类：区分未找到、状态冲突、过期和并发修改。
- `PendingActionControllerIntegrationTest.java`：验证修改、确认幂等、客户隔离、取消、过期和默认优先级。

## 修改文件与作用

- `SpringAiConversationModelClient.java`：注册草稿工具，并补充触发条件、确认要求和优先级规则。
- `ConversationServiceImpl.java`、`ChatExchangeResponse.java`、`ChatStreamEvent.java`：向同步响应和 SSE 输出结构化确认操作。
- `SecurityConfig.java`、`GlobalExceptionHandler.java`：限制客户角色访问并提供稳定错误码。
- `README.md`：补充 SSE 事件和草稿操作示例。
- `docs/database-schema.md`：增加待确认操作表字段、索引、关系和 V8 迁移说明。

## API 与数据库变化

- 新增 `GET /api/agent/actions?conversationId={id}&status={status}`。
- 新增 `PATCH /api/agent/actions/{id}`，只修改标题和描述。
- 新增 `POST /api/agent/actions/{id}/confirm` 和 `/cancel`。
- 同步聊天响应新增 `pendingActions`；SSE 新增 `action_required` 事件。
- 新增 `agent_pending_actions` 表，没有修改已有表。

## 验证

- 执行 `mvn -q clean test`，共 45 项测试通过，失败和错误均为 0。
- 验证 Flyway V8 能在 H2 测试库创建完整表结构。
- 验证客户修改后按最新草稿创建工单，重复确认只产生一张工单。
- 验证跨客户访问返回未找到，取消和过期草稿不能确认。
- 验证同步聊天返回 `pendingActions`，流式聊天按顺序发送 `action_required` 和 `done`。
- 验证 Spring AI 同时注册查询工具和 `prepare_create_ticket` 工具。

## 已知限制与后续工作

- 本轮只实现后端 API 和 SSE 事件，前端确认卡片仍需在对应前端项目中接入。
- 草稿有效期当前固定为 24 小时，后续可以改为配置项并增加定时清理任务。
- 当前待确认操作只支持创建工单；未来写操作可以复用同一状态机。
