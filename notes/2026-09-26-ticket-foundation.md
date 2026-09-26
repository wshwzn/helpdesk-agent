# 工单基础能力变更说明

## 实现的功能

- 接入本地 MySQL、MyBatis-Plus 和 Flyway。
- 实现工单创建、详情查询和分页查询。
- 支持按创建人和状态筛选工单。
- 增加请求参数校验、统一响应格式和全局异常处理。
- 使用 H2 隔离自动化测试，避免测试依赖开发者本地数据库。
- 本地数据库密码存放在 Git 忽略的 `application-local.yml` 中。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `ApiResponse` | 统一接口成功和失败响应结构。 |
| `PageResponse` | 统一分页数据结构。 |
| `GlobalExceptionHandler` | 将校验、资源不存在和未知异常转换为 HTTP 响应。 |
| `MybatisPlusConfig` | 注册 MyBatis-Plus 分页拦截器。 |
| `Ticket` | 映射 `tickets` 数据库表。 |
| `TicketStatus` | 定义待处理、处理中、已解决和已取消状态。 |
| `TicketPriority` | 定义低、中、高三个优先级。 |
| `CreateTicketRequest` | 接收并校验创建工单参数。 |
| `TicketResponse` | 隔离数据库实体和对外接口响应。 |
| `TicketNotFoundException` | 表示指定工单不存在。 |
| `TicketMapper` | 提供工单数据库访问能力。 |
| `TicketService` | 实现工单创建、详情查询和分页查询业务。 |
| `TicketController` | 暴露客户侧工单 HTTP 接口。 |
| `V1__create_tickets.sql` | 创建工单表、唯一约束和查询索引。 |
| `application-local.example.yml` | 提供不含真实密码的本地配置模板。 |
| `application-test.yml` | 配置测试使用的 H2 内存数据库。 |
| `TicketControllerIntegrationTest` | 验证创建、详情查询和分页查询链路。 |

## 修改的文件

| 文件 | 修改内容 |
| --- | --- |
| `pom.xml` | 增加 MyBatis-Plus、Flyway、MySQL 和 H2 依赖。 |
| `application.yaml` | 增加 profile、MyBatis-Plus 和 Actuator 配置。 |
| `.gitignore` | 忽略包含本地数据库凭据的配置文件。 |
| `HelpdeskAgentApplicationTests` | 使用测试 profile 启动上下文。 |
| `README.md` | 增加中文启动步骤、数据库说明和接口示例。 |

## API 变化

```http
POST /api/tickets
GET  /api/tickets/{id}
GET  /api/tickets?page=1&size=20&creatorId=1&status=PENDING
```

## 数据库变化

- 创建 `helpdesk_agent` 数据库。
- Flyway V1 创建 `tickets` 业务表。
- Flyway 自动创建 `flyway_schema_history` 迁移历史表。
- 工单编号具有唯一约束，`(creator_id, status)` 具有联合查询索引。

## 验证结果

- 基础阶段的上下文测试和工单 HTTP 集成测试均通过。
- 本地 MySQL 已验证创建、详情和分页查询。
- 验证生成的临时工单已删除。

## 当前限制与后续工作

- 尚未实现工单领取、取消和完结。
- 尚未实现登录认证和权限控制。
- 尚未接入大模型、知识库和 Agent 工具。
