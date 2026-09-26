# 工单生命周期功能变更说明

## 实现的功能

- 客户可以取消自己创建且处于 `PENDING` 或 `PROCESSING` 状态的工单。
- 处理中工单取消后保留处理人和领取时间，记录取消原因与取消时间；客服不能再完结该工单。
- 客服可以查询处理队列并领取待处理工单，领取后状态变为 `PROCESSING`。
- 只有领取工单的客服可以填写处理结果并将工单变为 `RESOLVED`。
- 已解决或已取消的工单不能再次取消，已取消的工单不能再被客服完结。
- 使用 MyBatis-Plus 乐观锁避免两个客服并发更新同一工单。
- 越权操作返回 HTTP 403，非法状态流转或并发更新返回 HTTP 409。

状态流转规则：

```text
PENDING → PROCESSING → RESOLVED
   └────→ CANCELLED ←────┘
```

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `SupportTicketController` | 提供客服工单列表、领取和完结接口。 |
| `ClaimTicketRequest` | 校验领取工单时提交的客服编号。 |
| `ResolveTicketRequest` | 校验客服编号和处理结果。 |
| `CancelTicketRequest` | 校验客户编号和取消原因。 |
| `InvalidTicketStatusException` | 表示当前状态不允许执行目标操作。 |
| `TicketAccessDeniedException` | 表示客户或客服没有当前工单的操作权限。 |
| `ConcurrentTicketUpdateException` | 表示乐观锁检测到并发更新。 |
| `V2__add_ticket_lifecycle.sql` | 增加处理人、处理结果、取消原因、生命周期时间和版本号字段。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `Ticket` | 增加 `assigneeId`、`assignedAt`、`resolution`、`resolvedAt`、`cancelReason`、`cancelledAt` 和 `version` 字段。 |
| `TicketResponse` | 向接口响应公开工单处理和取消信息，但不公开内部乐观锁版本号。 |
| `TicketService` | 实现客服领取、客服完结、客户取消、客服队列查询、权限检查和状态检查；允许取消待处理或处理中工单。 |
| `TicketController` | 增加客户取消工单接口。 |
| `MybatisPlusConfig` | 注册 `OptimisticLockerInnerInterceptor`。 |
| `GlobalExceptionHandler` | 增加 403 和 409 异常响应映射。 |
| `TicketControllerIntegrationTest` | 增加领取、完结、待处理取消、处理中取消、取消后禁止完结、终态冲突和越权处理测试。 |
| `README.md` | 更新产品定位、工单状态流转及接口调用示例。 |

## API 变化

```http
POST /api/tickets/{id}/cancel
GET  /api/support/tickets
POST /api/support/tickets/{id}/claim
POST /api/support/tickets/{id}/resolve
```

## 数据库变化

Flyway V2 为 `tickets` 表增加以下字段：

```text
assignee_id, assigned_at, resolution, resolved_at,
cancel_reason, cancelled_at, version
```

同时增加 `(assignee_id, status)` 联合索引，用于客服工作队列查询。原有 V1 脚本没有修改。

## 验证结果

- `mvn test`：6 个测试通过，0 失败。
- 本地 MySQL：V1、V2 迁移均执行成功。
- 手工验证：真实数据库已跑通“创建 → 客服领取 → 客服完结”。
- 验证生成的数据已删除，原有用户工单未修改。

## 当前限制与后续工作

- `customerId` 和 `supportId` 暂时由客户端传入，尚未接入可信登录身份。
- 尚未实现用户、客服、角色和权限模型。
- 尚未实现 AI 辅助填写、工单分类和知识库检索。
