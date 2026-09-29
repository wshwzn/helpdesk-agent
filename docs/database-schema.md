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
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 生成失败时保存的内部脱敏错误码，例如模型失败 `MODEL_CALL_FAILED`、进程中断超时恢复 `GENERATION_INTERRUPTED`。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，防止生成完成与失败状态互相覆盖。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息状态或内容最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_chat_messages_conversation_id (conversation_id, id)`，支持按对话顺序读取消息。
- 普通索引：`idx_chat_messages_status_role_updated (status, role, updated_at)`，支持后台快速扫描超时的 `PENDING` 助手消息。
- 超过配置阈值仍为 `PENDING` 的助手消息会被条件更新为 `FAILED` 并递增版本号，防止迟到的模型结果覆盖恢复状态。

## `agent_tool_calls` Agent 工具调用表

保存 Agent 一次工具请求及其对应响应，由 Flyway V7 创建。一行表示一对 Tool Call/Tool Response；同一条最终 AI 回复可以关联多行。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 工具调用记录主键。 |
| `conversation_id` | `BIGINT` | 否 | 无 | 所属 `conversations.id`，用于按对话审计，逻辑关联。 |
| `assistant_message_id` | `BIGINT` | 否 | 无 | 本次工具调用最终归属的 `chat_messages.id` AI 消息，逻辑关联。 |
| `customer_id` | `BIGINT` | 否 | 无 | 执行时已鉴权客户的 `users.id`，只由后端上下文写入。 |
| `call_id` | `VARCHAR(64)` | 否 | 无 | 应用生成的唯一调用标识，用于回放时配对 Tool Call 与 Tool Response。 |
| `sequence_no` | `INT` | 否 | 无 | 同一 AI 回复内从 1 开始的工具执行顺序。 |
| `tool_name` | `VARCHAR(100)` | 否 | 无 | Spring AI 工具名称，例如 `list_my_tickets`。 |
| `arguments_json` | `MEDIUMTEXT` | 否 | 无 | 模型提供的工具参数 JSON，不包含服务端身份上下文。 |
| `result_json` | `MEDIUMTEXT` | 是 | `NULL` | 工具成功后模型实际收到的脱敏结果；运行中或失败时为空。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`RUNNING`、`SUCCESS`、`FAILED`。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 失败时保存固定脱敏错误码 `TOOL_EXECUTION_FAILED`。 |
| `duration_ms` | `BIGINT` | 是 | `NULL` | 工具完成或失败后的执行耗时，单位毫秒。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，避免成功和失败状态互相覆盖。 |
| `started_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 工具开始时间。 |
| `completed_at` | `TIMESTAMP(6)` | 是 | `NULL` | 工具成功或失败的完成时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_agent_tool_calls_call_id (call_id)`，保证回放配对标识唯一。
- 唯一约束：`uk_agent_tool_calls_message_sequence (assistant_message_id, sequence_no)`，保证同一回答内顺序不重复。
- 普通索引：`idx_agent_tool_calls_conversation_id (conversation_id, id)`，支持按对话审计。
- 普通索引：`idx_agent_tool_calls_message_status_sequence (assistant_message_id, status, sequence_no)`，支持加载上一轮成功工具结果。
- `arguments_json` 和 `result_json` 保存应用标准化内容，不保存 JWT、API Key、HTTP 请求头或异常正文。
- 生成失败或连接中断不会删除已完成的工具记录；长期停留在 `RUNNING` 的记录可用于识别进程异常中断。

## `agent_pending_actions` Agent 待确认操作表

保存 AI 生成、但必须由客户确认后才能执行的业务操作，由 Flyway V8 创建。当前只支持创建工单草稿，一条 AI 消息最多生成一个创建工单操作。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 待确认操作主键，也是确认、修改和取消接口使用的标识。 |
| `conversation_id` | `BIGINT` | 否 | 无 | 所属 `conversations.id`，逻辑关联。 |
| `assistant_message_id` | `BIGINT` | 否 | 无 | 生成草稿的 `chat_messages.id` AI 消息，逻辑关联。 |
| `customer_id` | `BIGINT` | 否 | 无 | 操作所属客户的 `users.id`，只从服务端执行上下文写入。 |
| `action_type` | `VARCHAR(40)` | 否 | 无 | 操作类型，当前为 `CREATE_TICKET`。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`PENDING`、`EXECUTED`、`CANCELLED`、`EXPIRED`、`INVALIDATED`。 |
| `title` | `VARCHAR(100)` | 否 | 无 | 当前工单标题草稿；客户确认前可以修改。 |
| `description` | `VARCHAR(2000)` | 否 | 无 | 当前问题描述草稿；客户确认前可以修改。 |
| `priority` | `VARCHAR(20)` | 否 | 无 | AI 建议的优先级：`LOW`、`MEDIUM`、`HIGH`；缺失时应用写入 `MEDIUM`。 |
| `priority_reason` | `VARCHAR(500)` | 是 | `NULL` | AI 给出的优先级判断理由。 |
| `ticket_id` | `BIGINT` | 是 | `NULL` | 执行后创建的 `tickets.id`；未执行时为空，逻辑关联。 |
| `expires_at` | `TIMESTAMP(6)` | 否 | 无 | 客户确认截止时间，当前为草稿创建后 24 小时。 |
| `executed_at` | `TIMESTAMP(6)` | 是 | `NULL` | 确认并成功创建工单的时间。 |
| `cancelled_at` | `TIMESTAMP(6)` | 是 | `NULL` | 客户取消草稿的时间。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，防止旧卡片覆盖草稿或重复执行。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 草稿创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 草稿内容或状态最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_agent_pending_actions_message_type (assistant_message_id, action_type)`，防止同一轮模型重复生成相同操作。
- 唯一约束：`uk_agent_pending_actions_ticket_id (ticket_id)`，保证一个待确认操作最多关联一张真实工单。
- 普通索引：`idx_agent_pending_actions_customer_conversation_status (customer_id, conversation_id, status, id)`，支持客户按对话恢复卡片。
- 普通索引：`idx_agent_pending_actions_status_expires (status, expires_at)`，支持识别超时草稿。
- 客户只能修改 `PENDING` 状态的标题和描述；优先级和身份关联字段不能通过客户接口修改。
- 确认操作与创建工单处于同一事务；并发确认中只有一次能通过乐观锁，失败事务创建的工单会回滚。
- AI 消息生成失败或流被取消时，已生成的草稿转为 `INVALIDATED`，不能继续确认。

## `knowledge_bases` 知识库表

保存管理员创建的知识库容器，由 Flyway V9 创建。原始文件和检索分块不存入该表。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 知识库主键。 |
| `name` | `VARCHAR(100)` | 否 | 无 | 知识库名称，全局唯一。 |
| `description` | `VARCHAR(500)` | 是 | `NULL` | 知识库用途说明。 |
| `retrieval_candidate_limit` | `INT` | 否 | `50` | BM25 和 KNN 各自送入 RRF 的候选数量上限，应用限制为 1～200。 |
| `vector_min_similarity` | `DECIMAL(4,3)` | 否 | `0.000` | 始终生效的 KNN 原始向量相似度下限，范围 0～1；0 会过滤负相关向量。 |
| `retrieval_result_limit` | `INT` | 否 | `5` | RRF 融合后最终返回数量上限，应用限制为 1～50。 |
| `is_default` | `BOOLEAN` | 否 | `FALSE` | 是否为客户对话 RAG 工具当前使用的默认知识库。 |
| `created_by` | `BIGINT` | 否 | 无 | 创建管理员的 `users.id`，逻辑关联。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，为后续知识库编辑预留。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_knowledge_bases_name (name)`。
- 普通索引：`idx_knowledge_bases_default_updated (is_default, updated_at)`，用于读取默认知识库。
- 只有不含文档记录的知识库可以删除，该规则由应用层强制。
- `retrieval_result_limit` 不能超过 `retrieval_candidate_limit`；KNN 的 `num_candidates` 由应用按候选上限的 2 倍计算。
- 应用层设置新默认项时会先清除旧默认项，客户对话只选择一个默认知识库。

## `knowledge_documents` 知识库文档表

保存原始文档的对象存储位置、完整性信息和处理状态，由 Flyway V9 创建。文件正文存放在本地开发目录或阿里云 OSS；后续生成的检索分块和向量存放在 Elasticsearch，不在 MySQL 重复保存。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 文档主键。 |
| `knowledge_base_id` | `BIGINT` | 否 | 无 | 所属 `knowledge_bases.id`，逻辑关联。 |
| `original_name` | `VARCHAR(255)` | 否 | 无 | 上传时经过路径清理的原始文件名。 |
| `storage_provider` | `VARCHAR(30)` | 否 | 无 | 对象存储提供方：当前为 `local` 或 `aliyun-oss`。 |
| `bucket_name` | `VARCHAR(128)` | 否 | 无 | OSS Bucket 名；本地开发固定为逻辑值 `local`。 |
| `object_key` | `VARCHAR(512)` | 否 | 无 | 后端生成的对象键，不通过管理接口返回。 |
| `content_type` | `VARCHAR(100)` | 否 | 无 | 由扩展名确定的可信 MIME 类型。 |
| `file_extension` | `VARCHAR(20)` | 否 | 无 | 小写扩展名，当前允许 `txt`、`md`、`pdf`、`docx`。 |
| `size_bytes` | `BIGINT` | 否 | 无 | 原始文件大小，单位字节；当前最大 20 MB。 |
| `sha256` | `CHAR(64)` | 否 | 无 | 原始文件内容的 SHA-256 十六进制摘要。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`UPLOADING`、`PENDING`、`PROCESSING`、`READY`、`FAILED`。 |
| `chunk_count` | `INT` | 否 | `0` | Elasticsearch 中当前有效分块数量。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 失败时的脱敏错误码，例如 `STORAGE_UPLOAD_FAILED`。 |
| `created_by` | `BIGINT` | 否 | 无 | 上传管理员的 `users.id`，逻辑关联。 |
| `version` | `INT` | 否 | `0` | 文档状态推进使用的乐观锁版本号。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 元数据创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 状态或统计信息最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_knowledge_documents_object_key (object_key)`。
- 普通索引：`idx_knowledge_documents_base_status_id (knowledge_base_id, status, id)`，支持按知识库和处理状态读取文档。
- 普通索引：`idx_knowledge_documents_base_sha256 (knowledge_base_id, sha256)`，支持后续识别同一知识库中的重复内容；当前不禁止重复上传。
- 上传时先写入 `UPLOADING`，对象保存成功后改为 `PENDING`；触发索引后进入 `PROCESSING`，后台成功写入 ES 后改为 `READY`，失败则改为 `FAILED` 并保存固定错误码。
- `PROCESSING` 状态禁止重复触发和删除；状态推进及并发覆盖由应用层和 `version` 乐观锁共同限制。
- 被当前 RAG 评测用例选为期望文档时禁止删除；应先修改或删除对应评测用例。

## `rag_evaluation_cases` RAG 检索评测用例表

保存知识库的固定测试问题，由 Flyway V14 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 评测用例主键。 |
| `knowledge_base_id` | `BIGINT` | 否 | 无 | 所属 `knowledge_bases.id`，逻辑关联。 |
| `question` | `VARCHAR(2000)` | 否 | 无 | 模拟客户提问的检索问题。 |
| `created_by` | `BIGINT` | 否 | 无 | 创建管理员的 `users.id`，逻辑关联。 |
| `version` | `INT` | 否 | `0` | 编辑用例时使用的乐观锁版本号。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间。 |

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_rag_evaluation_cases_base_id (knowledge_base_id, id)`。
- 单个知识库每次最多运行 100 条用例，该限制由应用层强制。

## `rag_evaluation_case_documents` 评测期望文档关系表

保存每条问题的一个或多个相关文档，由 Flyway V14 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `case_id` | `BIGINT` | 否 | 无 | `rag_evaluation_cases.id`，逻辑关联。 |
| `document_id` | `BIGINT` | 否 | 无 | 期望命中的 `knowledge_documents.id`，逻辑关联。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 关系创建时间。 |

- 复合主键：`PRIMARY KEY (case_id, document_id)`，同一用例不能重复选择同一文档。
- 普通索引：`idx_rag_evaluation_case_documents_document (document_id, case_id)`，用于删除文档前检查引用。
- 应用要求期望文档属于当前知识库且状态为 `READY`，每条用例允许 1～20 个期望文档。

## `rag_evaluation_runs` RAG 评测运行表

保存一次批量检索的参数快照和汇总指标，由 Flyway V14 创建。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 运行主键。 |
| `knowledge_base_id` | `BIGINT` | 否 | 无 | 被评测的知识库主键。 |
| `candidate_limit` | `INT` | 否 | 无 | 本次运行使用的单路候选数量。 |
| `vector_min_similarity` | `DECIMAL(4,3)` | 否 | 无 | 本次运行使用的向量相似度下限。 |
| `result_limit` | `INT` | 否 | 无 | 本次运行最终返回数量，也是 Hit@K、Recall@K 中的 K。 |
| `total_cases` | `INT` | 否 | 无 | 运行的问题总数。 |
| `hit_cases` | `INT` | 否 | 无 | 至少命中一个期望文档的问题数。 |
| `hit_rate` | `DECIMAL(8,6)` | 否 | 无 | `hit_cases / total_cases`。 |
| `recall_at_k` | `DECIMAL(8,6)` | 否 | 无 | 所有问题文档级 Recall@K 的平均值。 |
| `mean_reciprocal_rank` | `DECIMAL(8,6)` | 否 | 无 | 所有问题首个相关 Chunk 倒数排名的平均值。 |
| `average_latency_ms` | `BIGINT` | 否 | 无 | 每个问题完整检索耗时的平均值，单位毫秒。 |
| `created_by` | `BIGINT` | 否 | 无 | 发起运行的管理员主键。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 运行完成并保存的时间。 |

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_rag_evaluation_runs_base_created (knowledge_base_id, created_at, id)`，用于读取最近运行。

## `rag_evaluation_run_results` RAG 逐题评测结果表

保存每道问题运行时的期望文档和实际 Chunk 快照，保证用例后续修改或删除不会改变历史结果。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 逐题结果主键。 |
| `run_id` | `BIGINT` | 否 | 无 | 所属 `rag_evaluation_runs.id`。 |
| `case_id` | `BIGINT` | 否 | 无 | 运行时的评测用例主键快照。 |
| `question` | `VARCHAR(2000)` | 否 | 无 | 运行时的问题快照。 |
| `expected_documents_json` | `LONGTEXT` | 否 | 无 | 期望文档主键和文件名 JSON 快照。 |
| `retrieved_results_json` | `LONGTEXT` | 否 | 无 | 实际召回 Chunk 及其排名、分数的 JSON 快照。 |
| `expected_document_count` | `INT` | 否 | 无 | 期望文档数量。 |
| `matched_document_count` | `INT` | 否 | 无 | 被前 K 个 Chunk 覆盖的不同期望文档数量。 |
| `first_relevant_rank` | `INT` | 是 | `NULL` | 第一个相关 Chunk 名次，未命中时为空。 |
| `hit` | `BOOLEAN` | 否 | 无 | 是否至少命中一个期望文档。 |
| `recall_at_k` | `DECIMAL(8,6)` | 否 | 无 | `matched_document_count / expected_document_count`。 |
| `reciprocal_rank` | `DECIMAL(8,6)` | 否 | 无 | `1 / first_relevant_rank`，未命中为 0。 |
| `latency_ms` | `BIGINT` | 否 | 无 | 本题完整检索耗时，单位毫秒。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 结果保存时间。 |

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_rag_evaluation_run_results_run_case (run_id, case_id)`。
- 同一文档召回多个 Chunk 时，文档级 Recall@K 只计算一次；MRR 使用第一个相关 Chunk 的排名。

## `ai_model_configs` AI 模型配置表

保存运行时模型连接配置，由 Flyway V4 创建、V5 调整协议与厂商字段、V10 增加模型用途和向量维度。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 模型配置主键。 |
| `name` | `VARCHAR(100)` | 否 | 无 | 管理端显示的配置名称，全局唯一。 |
| `model_type` | `VARCHAR(20)` | 否 | `CHAT` | 模型用途：`CHAT`、`EMBEDDING`；历史配置迁移为 `CHAT`。 |
| `protocol` | `VARCHAR(40)` | 否 | 无 | 调用协议，当前固定为 `OPENAI_COMPATIBLE`。 |
| `vendor` | `VARCHAR(40)` | 否 | `CUSTOM` | 厂商：`OPENAI`、`DEEPSEEK`、`QWEN`、`OLLAMA`、`CUSTOM`。 |
| `base_url` | `VARCHAR(500)` | 否 | 无 | OpenAI SDK 风格基础地址，不包含末尾 `/chat/completions` 或 `/embeddings`。内置厂商由后端写入预设值。 |
| `model_name` | `VARCHAR(100)` | 否 | 无 | 厂商侧模型标识，例如 `deepseek-chat`。 |
| `api_key_ciphertext` | `VARCHAR(2048)` | 否 | 无 | AES-256-GCM 加密后的 API Key 密文。 |
| `api_key_hint` | `VARCHAR(16)` | 否 | 无 | 用于界面识别密钥的脱敏尾号，例如 `****1234`。 |
| `encryption_key_version` | `INT` | 否 | `1` | 加密主密钥版本，为后续密钥轮换预留。 |
| `supports_streaming` | `BOOLEAN` | 否 | `TRUE` | 该配置是否声明支持流式输出。 |
| `supports_tools` | `BOOLEAN` | 否 | `TRUE` | 该配置是否声明支持工具调用。 |
| `embedding_dimensions` | `INT` | 是 | `NULL` | Embedding 输出向量维度；`EMBEDDING` 必填，`CHAT` 为空。 |
| `enabled` | `BOOLEAN` | 否 | `TRUE` | 是否允许业务使用该模型配置。 |
| `is_default` | `BOOLEAN` | 否 | `FALSE` | 是否为该模型用途的默认配置；唯一性由服务事务保证。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，也用于判断运行时模型缓存是否失效。 |
| `created_by` | `BIGINT` | 否 | 无 | 创建管理员的 `users.id`，逻辑关联。 |
| `updated_by` | `BIGINT` | 否 | 无 | 最近修改管理员的 `users.id`，逻辑关联。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 配置创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_ai_model_configs_name (name)`。
- 普通索引：`idx_ai_model_configs_enabled_default (enabled, is_default)`，用于解析启用的默认模型。
- 普通索引：`idx_ai_model_configs_type_enabled_default (model_type, enabled, is_default)`，用于分别解析默认聊天和向量模型。
- 数据库暂未强制“每种用途只能有一个默认模型”，由 `AiModelConfigService` 在事务中按 `model_type` 清除旧默认项后设置新默认项。
- 应用层要求 `CHAT` 的 `embedding_dimensions` 为空；`EMBEDDING` 的维度必须为 1～4096，且 `supports_streaming`、`supports_tools` 必须为 `FALSE`。
- 厂商预设只适用于 `CHAT`；`EMBEDDING` 的 `vendor` 必须为 `CUSTOM`，并由管理员填写实际 `base_url`。

## 表关系

```text
users.id ──< tickets.creator_id
users.id ──< tickets.assignee_id
users.id ──< ai_model_configs.created_by
users.id ──< ai_model_configs.updated_by
users.id ──< conversations.customer_id
conversations.id ──< chat_messages.conversation_id
ai_model_configs.id ──< chat_messages.model_config_id
users.id ──< agent_tool_calls.customer_id
conversations.id ──< agent_tool_calls.conversation_id
chat_messages.id ──< agent_tool_calls.assistant_message_id
users.id ──< agent_pending_actions.customer_id
conversations.id ──< agent_pending_actions.conversation_id
chat_messages.id ──< agent_pending_actions.assistant_message_id
agent_pending_actions.ticket_id >── tickets.id
users.id ──< knowledge_bases.created_by
knowledge_bases.id ──< knowledge_documents.knowledge_base_id
users.id ──< knowledge_documents.created_by
knowledge_bases.id ──< rag_evaluation_cases.knowledge_base_id
rag_evaluation_cases.id ──< rag_evaluation_case_documents.case_id
knowledge_documents.id ──< rag_evaluation_case_documents.document_id
knowledge_bases.id ──< rag_evaluation_runs.knowledge_base_id
rag_evaluation_runs.id ──< rag_evaluation_run_results.run_id
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
| V7 | `V7__create_agent_tool_calls.sql` | 创建 Agent 工具调用记录表。 |
| V8 | `V8__create_agent_pending_actions.sql` | 创建 Agent 待确认操作和工单草稿表。 |
| V9 | `V9__create_knowledge_bases_and_documents.sql` | 创建知识库和原始文档元数据表。 |
| V10 | `V10__add_model_type_and_embedding_dimensions.sql` | 为模型配置增加聊天/向量用途和 Embedding 维度。 |
| V11 | `V11__add_knowledge_retrieval_settings.sql` | 为知识库增加候选窗口、向量阈值和最终结果上限。 |
| V12 | `V12__require_vector_min_similarity.sql` | 将历史空阈值迁移为 0，并要求向量相似度下限始终存在。 |
| V13 | `V13__add_default_knowledge_base.sql` | 为知识库增加客户对话使用的默认项标记和查询索引。 |
| V14 | `V14__create_rag_evaluation_tables.sql` | 创建 RAG 评测用例、期望文档关系、运行汇总和逐题结果表。 |
| V15 | `V15__add_chat_pending_recovery_index.sql` | 增加按状态、角色和更新时间扫描中断消息的索引。 |
