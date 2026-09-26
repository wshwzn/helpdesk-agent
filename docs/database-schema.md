# 数据库表结构说明

本文档是 `helpdesk_agent` 数据库的字段字典。表结构以 `src/main/resources/db/migration/` 下的 Flyway 脚本为准；任何新增、修改或删除表、字段、索引、约束的迁移，都必须在同一次提交中更新本文档。

## 通用约定

- 主键统一使用 `BIGINT AUTO_INCREMENT`。
- 时间字段使用 `TIMESTAMP(6)`，保留微秒精度。
- Java 枚举以字符串名称入库，禁止直接修改已使用的枚举名称。
- `updated_at` 由应用服务在更新数据时写入，没有配置数据库 `ON UPDATE`。
- 当前业务表之间使用逻辑关联，尚未建立数据库外键；应用层必须校验数据归属与引用对象。
- `version` 是 MyBatis-Plus 乐观锁版本号，每次成功更新后递增。

## `users` 用户表

保存客户、客服和管理员的登录身份，由 Flyway V3 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 用户主键。 |
| `username` | `VARCHAR(32)` | 否 | 无 | 登录名，全局唯一。 |
| `password_hash` | `VARCHAR(100)` | 否 | 无 | BCrypt 密码哈希，不保存明文密码。 |
| `display_name` | `VARCHAR(50)` | 否 | 无 | 页面展示名称。 |
| `role` | `VARCHAR(20)` | 否 | 无 | 角色：`CUSTOMER`、`SUPPORT`、`ADMIN`。 |
| `enabled` | `BOOLEAN` | 否 | `TRUE` | 账号是否允许登录。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_users_username (username)`，防止登录名重复。
- 普通索引：`idx_users_role_enabled (role, enabled)`，用于按角色和启用状态筛选用户。

## `tickets` 工单表

保存客户提交的工单及客服处理结果，由 Flyway V1 创建、V2 扩展。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 工单主键，接口内部定位使用。 |
| `ticket_no` | `VARCHAR(32)` | 否 | 无 | 面向用户的唯一工单编号，例如 `HD20260926011826C67762A4`。 |
| `title` | `VARCHAR(100)` | 否 | 无 | 工单标题。 |
| `description` | `VARCHAR(2000)` | 否 | 无 | 客户提交的问题描述。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`PENDING`、`PROCESSING`、`RESOLVED`、`CANCELLED`。 |
| `priority` | `VARCHAR(20)` | 否 | 无 | 优先级：`LOW`、`MEDIUM`、`HIGH`。 |
| `creator_id` | `BIGINT` | 否 | 无 | 创建客户的 `users.id`，逻辑关联。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 工单创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间，由应用维护。 |
| `assignee_id` | `BIGINT` | 是 | `NULL` | 领取工单的客服 `users.id`；未领取时为空。 |
| `assigned_at` | `TIMESTAMP(6)` | 是 | `NULL` | 客服领取时间。 |
| `resolution` | `VARCHAR(2000)` | 是 | `NULL` | 客服填写的处理结果，解决后写入。 |
| `resolved_at` | `TIMESTAMP(6)` | 是 | `NULL` | 工单解决时间。 |
| `cancel_reason` | `VARCHAR(500)` | 是 | `NULL` | 客户取消工单时填写的原因。 |
| `cancelled_at` | `TIMESTAMP(6)` | 是 | `NULL` | 工单取消时间。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，防止领取、解决或取消操作并发覆盖。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_tickets_ticket_no (ticket_no)`。
- 普通索引：`idx_tickets_creator_status (creator_id, status)`，支持客户按状态查询自己的工单。
- 普通索引：`idx_tickets_assignee_status (assignee_id, status)`，支持客服查询已领取工单。

## `conversations` 对话表

保存客户与 AI 的对话会话，由 Flyway V6 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 对话主键。 |
| `customer_id` | `BIGINT` | 否 | 无 | 对话所属客户的 `users.id`，逻辑关联。 |
| `title` | `VARCHAR(100)` | 否 | 无 | 客户创建对话时填写的标题。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 对话状态：`ACTIVE`、`ARCHIVED`；当前只创建 `ACTIVE` 对话。 |
| `last_message_at` | `TIMESTAMP(6)` | 是 | `NULL` | 最近一次客户消息落库或 AI 生成结束的时间；新建空对话时为空。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，避免同一对话的并发发送互相覆盖。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 对话创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 对话最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_conversations_customer_status_updated (customer_id, status, updated_at)`，支持客户分页筛选自己的对话。

## `chat_messages` 对话消息表

保存客户消息、AI 回答及生成状态，由 Flyway V6 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 消息主键，也用于确定同一对话内的消息顺序。 |
| `conversation_id` | `BIGINT` | 否 | 无 | 所属 `conversations.id`，逻辑关联。 |
| `role` | `VARCHAR(20)` | 否 | 无 | 消息角色：`USER`、`ASSISTANT`；预留 `SYSTEM`、`TOOL`。 |
| `content` | `MEDIUMTEXT` | 是 | `NULL` | 消息正文；等待生成或生成失败的 AI 消息为空。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`PENDING`、`COMPLETED`、`FAILED`。客户消息直接为 `COMPLETED`。 |
| `model_config_id` | `BIGINT` | 是 | `NULL` | AI 消息使用的 `ai_model_configs.id` 快照，客户消息为空。 |
| `model_name` | `VARCHAR(100)` | 是 | `NULL` | AI 消息生成时实际使用的模型名称快照。 |
| `prompt_tokens` | `INT` | 是 | `NULL` | 厂商返回的输入 Token 数。 |
| `completion_tokens` | `INT` | 是 | `NULL` | 厂商返回的输出 Token 数。 |
| `total_tokens` | `INT` | 是 | `NULL` | 厂商返回的总 Token 数。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 生成失败时保存的内部脱敏错误码，例如 `MODEL_CALL_FAILED`。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，防止生成完成与失败状态互相覆盖。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息状态或内容最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_chat_messages_conversation_id (conversation_id, id)`，支持按对话顺序读取消息。

## `ai_model_configs` AI 模型配置表

保存运行时模型连接配置，由 Flyway V4 创建、V5 调整协议与厂商字段。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 模型配置主键。 |
| `name` | `VARCHAR(100)` | 否 | 无 | 管理端显示的配置名称，全局唯一。 |
| `protocol` | `VARCHAR(40)` | 否 | 无 | 调用协议，当前固定为 `OPENAI_COMPATIBLE`。 |
| `vendor` | `VARCHAR(40)` | 否 | `CUSTOM` | 厂商：`OPENAI`、`DEEPSEEK`、`QWEN`、`OLLAMA`、`CUSTOM`。 |
| `base_url` | `VARCHAR(500)` | 否 | 无 | OpenAI SDK 风格基础地址，不包含末尾 `/chat/completions`。内置厂商由后端写入预设值。 |
| `model_name` | `VARCHAR(100)` | 否 | 无 | 厂商侧模型标识，例如 `deepseek-chat`。 |
| `api_key_ciphertext` | `VARCHAR(2048)` | 否 | 无 | AES-256-GCM 加密后的 API Key 密文。 |
| `api_key_hint` | `VARCHAR(16)` | 否 | 无 | 用于界面识别密钥的脱敏尾号，例如 `****1234`。 |
| `encryption_key_version` | `INT` | 否 | `1` | 加密主密钥版本，为后续密钥轮换预留。 |
| `supports_streaming` | `BOOLEAN` | 否 | `TRUE` | 该配置是否声明支持流式输出。 |
| `supports_tools` | `BOOLEAN` | 否 | `TRUE` | 该配置是否声明支持工具调用。 |
| `enabled` | `BOOLEAN` | 否 | `TRUE` | 是否允许业务使用该模型配置。 |
| `is_default` | `BOOLEAN` | 否 | `FALSE` | 是否为默认聊天模型；唯一性由服务事务保证。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，也用于判断运行时模型缓存是否失效。 |
| `created_by` | `BIGINT` | 否 | 无 | 创建管理员的 `users.id`，逻辑关联。 |
| `updated_by` | `BIGINT` | 否 | 无 | 最近修改管理员的 `users.id`，逻辑关联。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 配置创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_ai_model_configs_name (name)`。
- 普通索引：`idx_ai_model_configs_enabled_default (enabled, is_default)`，用于解析启用的默认模型。
- 数据库暂未强制“只能有一个默认模型”，由 `AiModelConfigService` 在事务中清除旧默认项后设置新默认项。

## 表关系

```text
users.id ──< tickets.creator_id
users.id ──< tickets.assignee_id
users.id ──< ai_model_configs.created_by
users.id ──< ai_model_configs.updated_by
users.id ──< conversations.customer_id
conversations.id ──< chat_messages.conversation_id
ai_model_configs.id ──< chat_messages.model_config_id
```

这些关系目前均为逻辑关联，没有外键约束和级联删除行为。

## `flyway_schema_history` 迁移历史表

该表由 Flyway 自动创建和维护，不属于业务数据。字段包括迁移顺序 `installed_rank`、版本 `version`、说明 `description`、脚本名称 `script`、校验值 `checksum`、执行人和时间、耗时以及成功标记。不要手工插入、更新或删除记录；数据库结构变更必须新增更高版本的 `V{版本}__{说明}.sql`，不得修改已经执行过的迁移脚本。

当前业务迁移：

| 版本 | 脚本 | 作用 |
| --- | --- | --- |
| V1 | `V1__create_tickets.sql` | 创建工单表。 |
| V2 | `V2__add_ticket_lifecycle.sql` | 增加工单领取、解决、取消和乐观锁字段。 |
| V3 | `V3__create_users.sql` | 创建用户表。 |
| V4 | `V4__create_ai_model_configs.sql` | 创建模型配置表。 |
| V5 | `V5__add_model_vendor_presets.sql` | 拆分模型协议和厂商预设。 |
| V6 | `V6__create_conversations_and_messages.sql` | 创建客户对话和消息表。 |
