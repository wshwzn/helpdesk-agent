# 数据库表结构说明

本文档是 `helpdesk_agent` 数据库的字段字典。表结构以 `src/main/resources/db/migration/` 下的 Flyway 脚本为准；任何新增、修改或删除表、字段、索引、约束的迁移，都必须在同一次提交中更新本文档。

## 通用约定

- 业务实体主键通常使用 `BIGINT AUTO_INCREMENT`；一对一任务表使用所属文档 ID 作为主键。
- 时间字段使用 `TIMESTAMP(6)` 或 `DATETIME(6)`，保留微秒精度；业务时间按 Asia/Shanghai 解释，任务租约使用数据库时钟。
- Java 枚举以字符串名称入库，禁止直接修改已使用的枚举名称。
- `updated_at` 由应用服务在更新数据时写入，没有配置数据库 `ON UPDATE`。
- 业务表主要使用逻辑关联；V20 的索引任务对所属文档建立级联删除外键，V21 的版本链建立限制删除的自引用外键，V22 为来源、对象映射与文档追溯建立外键。应用层仍必须校验数据归属与引用对象。
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
| `status` | `VARCHAR(20)` | 否 | 无 | 对话状态：`ACTIVE`、`ARCHIVED`；客户删除对话时转为 `ARCHIVED` 并从活动列表隐藏。 |
| `last_message_at` | `TIMESTAMP(6)` | 是 | `NULL` | 最近一次客户消息落库或 AI 生成结束的时间；新建空对话时为空。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，避免同一对话的并发发送互相覆盖。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 对话创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 对话最后更新时间，由应用维护。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_conversations_customer_status_updated (customer_id, status, updated_at)`，支持客户分页筛选自己的对话。
- 删除对话采用应用层归档，不物理删除消息、工具调用和待确认操作；归档对话不能继续发送消息。

## `chat_messages` 对话消息表

保存客户消息、AI 回答及生成状态，由 Flyway V6 创建，V17 增加知识问答模式与证据报告，V18 增加诊断运行编号。

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
| `mode` | `VARCHAR(20)` | 否 | `AGENT` | 本条消息的模式：`AGENT` 旧兼容工具对话、`KNOWLEDGE` 强制证据核验；历史记录及数据库默认值仍为 AGENT，应用新请求默认显式写入 KNOWLEDGE。 |
| `applicability_scope` | `VARCHAR(40)` | 是 | `NULL` | 本轮请求显式选择的业务范围快照。KNOWLEDGE 未指定时为 `ALL`，AGENT 为空；非权限字段。 |
| `knowledge_answer` | `MEDIUMTEXT` | 是 | `NULL` | 核验报告 JSON；仅成功完成的 KNOWLEDGE 助手消息写入。包含 `schemaVersion=1`（普通结果）或 `2`（结构化澄清/续答）、原因、知识库身份快照、范围、核验时间、结构化回答及本轮证据快照。 |
| `knowledge_run_id` | `VARCHAR(36)` | 是 | `NULL` | V18 新增，服务端为新 KNOWLEDGE 助手消息分配的 UUID，逻辑关联 `knowledge_answer_runs.run_id`。客户消息、AGENT 消息及升级前历史消息为空；成功或失败后均保留。 |
| `version` | `INT` | 否 | `0` | 乐观锁版本号，防止生成完成与失败状态互相覆盖。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 消息状态或内容最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 普通索引：`idx_chat_messages_conversation_id (conversation_id, id)`，支持按对话顺序读取消息。
- V18 唯一约束：`uk_chat_messages_knowledge_run (knowledge_run_id)`，非空运行编号只能属于一条助手消息；允许多个 NULL。
- 普通索引：`idx_chat_messages_status_role_updated (status, role, updated_at)`，支持后台快速扫描超时的 `PENDING` 助手消息。
- 超过配置阈值仍为 `PENDING` 的助手消息会被条件更新为 `FAILED` 并递增版本号，防止迟到的模型结果覆盖恢复状态。
- V17 检查约束：`ck_chat_message_mode` 限定 `AGENT`、`KNOWLEDGE`。本次未新增索引。
- `knowledge_answer` 中 `reason` 为 `REVIEWED`、`NO_MATCH`、`NOT_CONFIGURED`、`APPLICABILITY_REQUIRED`；`answer.status` 为 `ANSWERABLE`、`NEEDS_CLARIFICATION`、`INSUFFICIENT_EVIDENCE`、`CONFLICT`。无命中/未配置时状态为证据不足，但不调用回答模型。
- 报告的 `evidence[].id` 为本轮临时编号 E1/E2；`documentId`、`chunkSequence` 逻辑关联知识文档及 ES Chunk。`sourceName`、正文与业务元数据是历史快照，文档撤回或删除后仍保留，不用外键级联删除。
- 应用保证每项事实结论有本轮证据引用，`quote` 是连续原文；冲突双方至少涉及两份文档。正文与报告在同一个带乐观锁的完成事务写入。JSON 结构和模式/范围关系由应用约束，数据库不校验 JSON 内部结构。
- 知识链路技术失败保存固定阶段码，如 `KNOWLEDGE_RETRIEVAL_FAILED`、`KNOWLEDGE_DRAFT_CALL_FAILED`、`KNOWLEDGE_PROTOCOL_INVALID`、`KNOWLEDGE_CITATION_INVALID`、`KNOWLEDGE_REVIEW_REJECTED`、`KNOWLEDGE_REVIEW_WRONG_STATUS`、`KNOWLEDGE_EVIDENCE_CHANGED`；未知或历史旧版本失败保留 `KNOWLEDGE_ANSWER_FAILED`。取消使用 `STREAM_CANCELLED`。全部码不超过 50 字符，完整分类见 `docs/evaluation/README.md`；无新增列或约束。失败消息不保存草案或成功报告。核验时间采用 Asia/Shanghai；历史报告表示当时的结果，不保证资料现在仍有效。
- KNOWLEDGE 的 Token 用量合计草案与复核两次回答模型调用，任一调用未返回某项用量时该项为 NULL；无回答模型调用时为 0，不含检索 Embedding 用量。

- V19 起，`knowledge_answer.clarification` 可保存 `originalQuestion`（最多 2000 字）、`policyRevision`、`topicIds`、`scopeOptions[{value,label}]`；仅 `APPLICABILITY_REQUIRED / NEEDS_CLARIFICATION` 使用，没有事实结论或证据。旧 JSON 新字段为空。
- `knowledge_answer.continuationOfMessageId` 逻辑关联同会话的澄清助手消息，无数据库外键或新增索引。续答前校验该消息是会话最新消息、已完成、属于 KNOWLEDGE 且包含结构化澄清；用户仍须通过会话归属检查。原问题及知识库由此恢复，用户选择范围必须在快照选项内、配置修订仍相同。消息并发沿用会话乐观锁；失败后重新发送原问题，不能重复消费旧卡片。

## `knowledge_answer_runs` 知识问答诊断表

由 Flyway V18 创建，保存已接受知识消息的运行快照。仅管理员诊断接口可读取；即使草案被拒绝，普通客户历史消息也只返回运行编号，不返回该表内容。诊断与客户消息使用独立事务，因此消息有运行编号不保证诊断表一定存在对应记录。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增 | 内部主键，按插入顺序分页。 |
| `run_id` | `VARCHAR(36)` | 否 | 无 | 服务端 UUID，外部诊断查询编号，逻辑关联消息中的 `knowledge_run_id`。 |
| `assistant_message_id` | `BIGINT` | 否 | 无 | 对应 `chat_messages.id`；一条助手消息最多一条运行记录。 |
| `conversation_id` | `BIGINT` | 否 | 无 | 所属 `conversations.id` 快照，用于按会话定位。 |
| `status` | `VARCHAR(20)` | 否 | 无 | `RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。成功指最终消息已落库；未结束快照不能视为模型还在运行的实时证明。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 固定失败码，无自由文本。除知识阶段码外可为 `STREAM_CANCELLED`、诊断专用的 `KNOWLEDGE_MESSAGE_SAVE_FAILED`；客户消息仍按原协议记录错误。 |
| `revision` | `BIGINT` | 否 | 无 | 本次运行内递增的快照修订号，从 1 开始；不是 MyBatis 实体乐观锁。写入失败后下次修订可跳号。 |
| `snapshot` | `MEDIUMTEXT` | 否 | 无 | `schemaVersion=4` JSON（兼容读取版本 1、2、3）：身份、问题、范围、回答模型配置 ID/名称、运行状态、阶段耗时、召回参数与结果、证据编号、引用目录 `citationCatalog`、模型选择 `selection`、解析后草案 `draft`、复核结果、回答原因、逐模型阶段用量、诊断写入失败次数。另有可空 `applicability`：配置快照、路由结果、续答消息 ID 和 `routingAttempted`。不得加入凭据、模型客户端对象或厂商原始异常。 |
| `started_at` | `TIMESTAMP(6)` | 否 | 无 | 在已接受消息上开始诊断的时间，应用按 Asia/Shanghai 写入。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 无 | 此快照生成时间。 |
| `finished_at` | `TIMESTAMP(6)` | 是 | `NULL` | 终态时间；运行中为空。 |

索引、约束与应用规则：

- 主键：`PRIMARY KEY (id)`；唯一约束 `uk_knowledge_answer_runs_run (run_id)` 和 `uk_knowledge_answer_runs_message (assistant_message_id)`。
- 普通索引 `idx_knowledge_answer_runs_conversation (conversation_id, id)`、`idx_knowledge_answer_runs_status (status, id)` 支持管理员筛选和分页。
- 检查约束 `ck_knowledge_answer_runs_status` 限定四种运行状态；`ck_knowledge_answer_runs_revision` 要求修订号大于 0。逻辑关系不建立外键，避免诊断写入依赖或锁住客户消息事务。
- 更新必须满足原记录 `status=RUNNING` 且新修订号更大；终态不允许被迟到快照覆盖。首次写入失败后，后续阶段可以插入完整累计快照。
- 阶段可先有 `APPLICABILITY`，然后是 `RETRIEVAL` 与最多两次 `PLANNING` 交替，最终为 `DRAFT`、`VALIDATION`、`REVIEW`、`EVIDENCE_RECHECK`、`MESSAGE_PERSISTENCE`；一次规划可对应两次检索。未配置知识库时没有检索阶段；未执行阶段不补造。每阶段状态为 `RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`，活跃阶段的耗时为空，结束后用单调时钟记录毫秒。
- 阶段耗时包含进入阶段时保存诊断的开销，不含结束时的快照写入；端到端耗时另由评测脚本记录。
- `retrieval` 保留首轮实际候选、排序和检索参数，最多 50 个 Chunk、50000 字；超限会标记 `retrievalTruncated`，不影响原问答链路的上下文超限拒绝。合法预算内的 `evidence` 对应本轮 E 编号；草案仅保存已解析的结构化对象，畸形原始响应不保存。
- 当前新快照写版本 5。可空 `answerContext` 保存 stopReason、knowledgeGaps（内部资料缺口）、userConditions（用户条件）、pendingQueries（计划中尚未执行的搜索）、searchedAfterAssessment（缺口判断后是否又检索）。关闭任务规划候选或单轮时为 null。这些是检索经历，不是事实证据。单列表模型协议为 decision/gaps/tasks/userConditions；转换后诊断仍用 queries 保存任务，ruleChecks=[]。每批最多两项且在下次规划前执行，不维护跨轮核查账本；停止决策任务必须为空，预算中断留下的待执行任务阻止 ANSWERABLE，无用户条件不能生成 NEEDS_CLARIFICATION。
- 历史版本 4 的 `pendingRuleQueries` 兼容为读取别名，ruleChecks（evidenceId、term、query）仍可反序列化展示；不重写历史快照。阶段 38–40 的锚点、双列表与强制核查队列已移除，不再执行。候选开关改为 `app.knowledge.multihop.task-planning-enabled`（默认 false），旧 `rule-checks-enabled` 不再启用候选。
- 复核可空 `issues` 保存固定问题路径、evidenceIds、300 字以内缺陷描述；REASONED 候选批准时必须 reason=NONE 且 issues=[]，否决必须有非 NONE 原因及 1–5 个有效问题，路径限定 status/coverage/clarification 或实际结论路径。旧策略 issues 为空，不补造历史理由；阶段 38 候选未通过回放准入，默认仍为 BASELINE，不启用 REASONED。错误码新增 KNOWLEDGE_UNJUSTIFIED_CLARIFICATION、KNOWLEDGE_RULE_CHECK_INCOMPLETE，无列或约束变更。 阶段 39 对候选规划新增固定分类 KNOWLEDGE_PLAN_PAYLOAD_INVALID（载荷解析）、KNOWLEDGE_PLAN_FIELDS_INVALID（字段）、KNOWLEDGE_PLAN_ACTION_INVALID（动作组合）、KNOWLEDGE_PLAN_RULE_QUERY_INVALID（查询与规则词）、KNOWLEDGE_PLAN_RULE_ANCHOR_INVALID（原文锚点），写入既有 errorCode 与 stages.errorCode，失败用量仍保留；不增加 JSON 字段或保存原始坏输出。
- 版本 3 新增可空 `retrievalRun`：固定预算、`searches`（轮次、查询、响应、截断标记、新增 E 编号）、`plans`（轮次和结构化缺口/动作/查询）、`stopReason`。最多 3 轮、5 次检索、2 次规划；每次响应最多保存 50 块/50000 字，累计模型证据也受 50 块/50000 字硬上限约束。失败请求保留 query 且 response 为空。停止枚举见 `KnowledgeRetrievalRun.StopReason`；它与最终答案状态独立。旧快照该字段为空，不补造历史检索。
- 阶段 41 新增 `KNOWLEDGE_SEARCH_INCOMPLETE` 表示已计划的搜索未执行却确定回答。候选规划保留 PAYLOAD/FIELDS/ACTION 三类错误；旧 RULE_QUERY/RULE_ANCHOR 和 KNOWLEDGE_RULE_CHECK_INCOMPLETE 仅供历史读取。仍不保存畸形模型原文，无 DDL 变更。
- `usage` 仅出现在已进入的模型阶段，字段未知时为 NULL。协议失败若厂商已经返回用量仍保留；未调用、无用量、零用量是不同状态。不统计 Embedding，不把阶段用量再与成功消息累计用量相加。
- 版本 2 的 `citationCatalog` 含每轮独立 UUID、证据身份、段落原文、UTF-16 左闭右开偏移、可引用标记；`selection` 是模型提出的目录编号、结论和片段选择，可包含错误选择。`draft` 仅在引用解析及结构校验成功后填入服务端原文，仍未必通过语义复核。旧版本 1 保留其原有草案语义，新增两字段为空，不改写历史 JSON。
- `review` 是模型判断，不是人工确认结论。可空 `review.checklist` 为实验逐项复核的结论路径、支持/条件判断及四个全局判断，值为 PASS/FAIL/UNCERTAIN；默认 BASELINE 及旧数据为空，CHECKLIST 全部 PASS 才批准。`selection` 与 `draft` 禁止作为客户答案复用；`answerReason` 未产生时为空。客户成功报告仍为版本 1，与管理员诊断版本独立演进。
- 无表间原子提交保证：进程中断或最后一次诊断写入失败可能留下 RUNNING；需同时核对客户消息状态和时间。当前没有诊断补偿任务或自动保留期清理，不应把残留状态伪装为正常终态。

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

V19 增加适用条件配置，更新不需要重建 ES 索引：

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `clarification_policy` | `MEDIUMTEXT` | 是 | `NULL` | 配置 JSON：`revision` 与下面字段一致，`topics` 最多 20 个；每个主题含唯一 `id`、业务 `description`（最多 500 字）、`scopeOptions`（最多 20 个）。选项为 `value` 范围码和 `label`（最多 80 字）。空选项表示此主题无需地区；空主题列表关闭路由。NULL 为升级前未配置。 |
| `clarification_revision` | `BIGINT` | 否 | `0` | 独立配置修订号，更新配置加一。客户端提供当前修订号；应用同时使用既有 `version` 乐观锁防止覆盖其他管理修改。不得回退、复用旧修订号。 |

应用约束：主题 ID 为 `[a-z][a-z0-9_-]{0,39}`，选项码为 `[A-Z][A-Z0-9_-]{0,39}` 且不允许 `ALL`，主题内范围不重复，同码标签必须一致；修订号非负。无新增索引、外键或数据库枚举。配置只决定必要条件，不是制度事实，也不承诺某地区有有效证据；续答重新执行 MySQL 准入及 ES 检索。多个需范围主题取选项交集，交集为空或模型分类不确定时作为技术失败。

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

保存原始文档的对象存储位置、完整性信息和处理状态，由 Flyway V9 创建，V16 增加业务版本、适用范围和发布元数据，V20 增加有效索引批次，V21 增加明确版本链和审核交接边界。文件正文存放在本地开发目录或阿里云 OSS；检索分块和向量存放在 Elasticsearch，不在 MySQL 重复保存。

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
| `source_key` | `VARCHAR(120)` | 是 | `NULL` | 稳定来源标识，同一来源的各业务版本共用；草稿可暂未配置，发布前必须有值。小写 ASCII 字母、数字及 `._:/-`，首位为字母或数字。 |
| `business_version` | `VARCHAR(40)` | 是 | `NULL` | 人工指定的业务版本标签；小写 ASCII 字母、数字及 `._-`，首位为字母或数字。不用于自动选最新版，与 `version` 乐观锁无关。 |
| `applicability_scope` | `VARCHAR(40)` | 否 | `ALL` | 单一适用范围代码，`ALL` 代表通用资料；大写字母开头，仅大写字母、数字、下划线和连字符。这是业务条件，不是权限。 |
| `valid_from` | `DATETIME(6)` | 是 | `NULL` | 生效时间（含边界），统一解释为 Asia/Shanghai 本地时间；发布前必填。 |
| `valid_until` | `DATETIME(6)` | 是 | `NULL` | 失效时间（不含边界）；空表示不设截止，非空时必须晚于生效时间。 |
| `publication_status` | `VARCHAR(20)` | 否 | `DRAFT` | 发布状态：`DRAFT` 草稿、`PUBLISHED` 已发布、`WITHDRAWN` 已撤回，与索引状态独立。 |
| `status` | `VARCHAR(20)` | 否 | 无 | 状态：`UPLOADING`、`PENDING`、`PROCESSING`、`READY`、`FAILED`。 |
| `chunk_count` | `INT` | 否 | `0` | Elasticsearch 中当前有效分块数量。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 失败时的脱敏错误码，例如 `STORAGE_UPLOAD_FAILED`。 |
| `active_index_build_id` | `VARCHAR(36)` | 是 | `NULL` | V20：最近成功提交的 ES 执行批次 UUID。空表示升级前无批次字段的历史片段；与业务版本及乐观锁版本不同，无新增索引或唯一约束。 |
| `predecessor_document_id` | `BIGINT` | 是 | `NULL` | V21：本候选明确接替的文档 ID，自引用外键；普通上传和历史资料为空。上传时确定且不可修改，一份旧版可有多个未审核候选。 |
| `successor_document_id` | `BIGINT` | 是 | `NULL` | V21：已审核的接替文档 ID，自引用外键且唯一；未来交接也提前记录。撤回尚未生效的接替文档时清空。 |
| `activated_at` | `DATETIME(6)` | 是 | `NULL` | V21：候选经审核后的实际启用边界，含边界；取审核时刻与声明 valid_from 的较晚值。普通发布、未审核候选和历史资料为空。撤回后保留审计值。 |
| `superseded_at` | `DATETIME(6)` | 是 | `NULL` | V21：旧版被接替的时间，不含边界，与 successor_document_id 成对设置或清空。原 valid_until 不变。 |
| `source_item_id` | `BIGINT` | 是 | `NULL` | V22：外部来源对象映射，关联 knowledge_source_items.id。非空时文件、业务元数据只能从来源维护；历代候选保留同一映射，普通上传与历史文档为空。 |
| `created_by` | `BIGINT` | 否 | 无 | 上传管理员的 `users.id`，逻辑关联。 |
| `version` | `INT` | 否 | `0` | 文档状态推进使用的乐观锁版本号。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 元数据创建时间。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 状态或统计信息最后更新时间。 |

索引与约束：

- 主键：`PRIMARY KEY (id)`。
- 唯一约束：`uk_knowledge_documents_object_key (object_key)`。
- V16 唯一约束：`uk_knowledge_document_business_version (knowledge_base_id, source_key, business_version, applicability_scope)`；同库、同来源、同业务版本、同范围不可重复，未配置的空值草稿可以共存。
- V16 检查约束：`ck_knowledge_document_validity` 保证截止时间为空，或开始时间非空且截止晚于开始；`ck_knowledge_document_publication` 限定三个发布状态。
- V16 普通索引：`idx_knowledge_document_eligibility (knowledge_base_id, publication_status, status, applicability_scope, valid_from)`，支持检索准入查询；截止时间作为剩余条件校验。
- 普通索引：`idx_knowledge_documents_base_status_id (knowledge_base_id, status, id)`，支持按知识库和处理状态读取文档。
- 普通索引：`idx_knowledge_documents_base_sha256 (knowledge_base_id, sha256)`，支持后续识别同一知识库中的重复内容；当前不禁止重复上传。
- V21 普通索引：`idx_knowledge_document_predecessor (predecessor_document_id)`，用于版本关系查询及删除保护；唯一约束 `uk_knowledge_document_successor (successor_document_id)` 保证同一接替文档不会被多份旧版引用，允许多行 NULL。
- V21 外键：`fk_knowledge_document_predecessor`、`fk_knowledge_document_successor` 均引用本表 `id`，使用默认限制删除行为，无级联修改或删除。来源、范围、知识库一致及双向关系配对由应用保证。
- V22：`idx_knowledge_document_source_item (source_item_id)`；`fk_knowledge_document_source_item` 限制删除所引用的来源映射，不级联。应用在对象删除前拒绝物理删除外部托管文档，保护历代快照；发布、撤回和索引重试仍走既有规则。
- 上传时先写入 `UPLOADING`，对象保存成功后改为 `PENDING`；提交索引后文档状态与任务同事务写入，`PROCESSING` 包括排队、执行和退避重试。成功写入 ES 并激活批次后改为 `READY`；确定性解析错误或重试耗尽改为 `FAILED`。
- `PROCESSING` 状态禁止修改元数据和删除，`UPLOADING` 同样禁止删除；重复调用原索引接口返回活动任务对应的文档，不重复入队。文档行锁、乐观锁及执行令牌分别保护提交、编辑及后台完成。
- 被当前 RAG 评测用例选为期望文档时禁止删除；应先修改或删除对应评测用例。
- 新文档和 V16 迁移后的历史文档均为草稿。历史 `READY` 不自动等于已审核；必须配置业务元数据、重新索引并发布后才能参与检索。
- 普通文档元数据仅允许在非上传中、非索引中的草稿上编辑。保存元数据后处理状态回到 `PENDING`，清空错误与分块统计；业务截止时间可以显式清空。V21 新版候选元数据随上传固定，禁止通过 metadata / submit 改写，需创建另一候选。
- 发布要求 `DRAFT + READY` 且来源、业务版本、生效时间已配置；未来生效的资料可以先发布，但不会提前召回。只有已发布资料可以撤回，撤回为终态；修订时上传新版本。
- 发布、撤回和元数据编辑要求提交当前乐观锁 `version`，过期或并发更新返回 409。唯一性冲突同样返回 409。
- 已发布及撤回文档不允许重新索引或修改业务元数据；已发布文档必须先撤回才能删除。草稿索引使用独立执行批次，只有成功提交的批次指针可进入检索，不覆盖已有批次。
- 检索准入同时要求 `PUBLISHED + READY`、`valid_from <= 当前时间 < valid_until`（空截止除外）、`activated_at <= 当前时间 < superseded_at`（各空边界忽略），以及范围为 `ALL` 或请求明确给出的范围。省略范围只允许 `ALL`。未建立明确接替关系的不同业务版本同时有效时仍全部保留，不按标签排序裁决。
- MySQL 为准入权威；ES Chunk 中保存业务元数据快照和 `indexBuildId`，不复制可变发布状态。两路召回前均传入文档及有效批次白名单；历史空批次仅匹配无 `indexBuildId` 的片段。返回后再次查询 MySQL 复核。单次准入文档超过 10000 时明确失败，不截断或退化为无过滤检索。
- V20 升级将原 `PROCESSING` 状态设为 `FAILED`，错误码 `INDEX_TASK_MISSING`，分块数清零且乐观锁递增，供管理员重新提交。上线前须停止旧版实例，不能与旧版无批次隔离的工作者混跑。
- V21 旧数据四个新增字段全为空，不自动推断历史版本关系、不重新索引、不改变发布状态。旧程序不理解交接边界，上线时所有问答实例必须升级后再启用替换功能。
- 新版上传继承旧版的来源和范围，正文 SHA-256、解析扩展名、业务版本及有效期完全相同才返回 UNCHANGED；已存在的同父候选完全相同返回 EXISTING，否则同业务版本冲突。新候选创建要求旧版当前有效、无已审核接替者且旧版乐观锁匹配。无变化/已有候选是只读返回，允许重发先前成功请求时旧版锁版本已变化。
- 审核按旧版→候选顺序加行锁，同时校验双方乐观锁。候选必须 DRAFT + READY，不能走普通 publish。应用同事务设置旧版 successor/superseded 和候选 activated/PUBLISHED；交接时间取审核时刻与候选 valid_from 较晚者，精度微秒。旧版必须覆盖到交接时刻（自身 valid_until 等于交接时刻允许），候选截止必须晚于交接。
- 时间统一按 Asia/Shanghai 业务时钟解释，未来交接由准入 SQL 的半开区间完成，不依赖定时修改发布状态。检索证据元数据返回声明有效期与交接边界的交集；ES 原有元数据快照不改写，权威数据取 MySQL。
- 撤回待生效接替文档会同事务清空旧版 successor/superseded，撤回后不再激活；已生效后撤回不会恢复旧制度。存在待生效接替者时禁止先撤回旧版。候选 activated 非空、文档存在已审核接替者或仍被候选引用时禁止物理删除，保留原始文件和历史链；未审核且停止处理的候选可以删除。
- 同库同源同范围、无自引用/循环、successor 与 superseded 成对及 activated 对应审核记录等规则由应用事务保证；新增迁移不包含依赖自增主键的 CHECK，不声称数据库能拦截任意手工 SQL 制造的循环。

## `knowledge_index_tasks` 文档索引任务表

V20 创建，一份文档保存最近一次索引提交。没有任务的 PENDING 文档不会被自动处理；重新提交终止任务会重置本轮记录，不保留完整执行历史。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `document_id` | `BIGINT` | 否 | 无 | 主键，关联 `knowledge_documents.id`，一文档一任务。 |
| `status` | `VARCHAR(20)` | 否 | 无 | `QUEUED` 等待或退避，`RUNNING` 已领取，`SUCCEEDED` 完成，`FAILED` 终止。 |
| `attempt_count` | `INT` | 否 | `0` | 本次提交已领取次数，租约恢复也计数。 |
| `max_attempts` | `INT` | 否 | 无 | 提交时保存重试预算，应用默认 3，配置必须大于零。 |
| `run_token` | `VARCHAR(36)` | 是 | `NULL` | 每次领取生成新 UUID，兼作 ES 批次标识；终止后可保留最近令牌，无独立唯一索引。 |
| `available_at` | `TIMESTAMP(6)` | 否 | 无 | 最早可领取时间；提交时为数据库当前时间，失败重试默认延后 30 秒。 |
| `lease_until` | `TIMESTAMP(6)` | 是 | `NULL` | RUNNING 的租约截止时间，默认 120 秒，心跳延长，结束或退避时清空。 |
| `error_code` | `VARCHAR(50)` | 是 | `NULL` | 固定脱敏错误码，保存最近失败原因；成功清空。 |
| `created_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 最近一次显式提交时间，自动重试不重置。 |
| `updated_at` | `TIMESTAMP(6)` | 否 | 当前时间 | 状态变更或心跳时间，由应用更新。 |

索引、约束与应用规则：

- 主键 `PRIMARY KEY (document_id)`，保证每份文档至多一个任务记录。
- 外键 `fk_knowledge_index_task_document` 引用 `knowledge_documents(id) ON DELETE CASCADE`；删除文档自动移除任务。
- 检查约束 `ck_knowledge_index_task_status` 限定四个状态；`ck_knowledge_index_task_attempts` 要求次数非负、预算为正。应用条件更新保证次数不超过预算。
- `idx_knowledge_index_task_available (status, available_at)` 用于发现到期排队任务；`idx_knowledge_index_task_lease (status, lease_until)` 用于发现超时执行任务。
- 提交先锁文档，元数据、PROCESSING 及 QUEUED 在一个事务中写入。领取、成功及失败统一先锁文档再更新任务；心跳只更新任务。数据库时钟用于租约比较。
- 领取使用状态和时间条件更新，并更换令牌；续租与完成要求 RUNNING、令牌匹配且租约未过期。成功时任务 SUCCEEDED 与文档 READY、有效批次指针同事务提交。ES 外部写入不处于数据库事务内。
- 暂时性故障在预算内回到 QUEUED；解析失败立即 FAILED；预算耗尽（包括反复租约过期）同步标记文档 FAILED。任务码包括 `DOCUMENT_PARSE_FAILED`、`ELASTICSEARCH_INDEX_FAILED`、`STORAGE_DOWNLOAD_FAILED`、`EMBEDDING_MODEL_UNAVAILABLE`、`EMBEDDING_FAILED`、`INDEX_LEASE_EXPIRED`。
- 原索引接口允许活动任务幂等查询；保存并提交接口仍检查元数据乐观锁。任务不是业务发布记录，不会自动发布或撤回资料。

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
knowledge_documents.id ── knowledge_index_tasks.document_id（级联删除外键）
knowledge_documents.id ──< knowledge_documents.predecessor_document_id（限制删除自引用外键）
knowledge_documents.id ── knowledge_documents.successor_document_id（唯一、限制删除自引用外键）
users.id ──< knowledge_documents.created_by
knowledge_bases.id ──< rag_evaluation_cases.knowledge_base_id
rag_evaluation_cases.id ──< rag_evaluation_case_documents.case_id
knowledge_documents.id ──< rag_evaluation_case_documents.document_id
knowledge_bases.id ──< rag_evaluation_runs.knowledge_base_id
rag_evaluation_runs.id ──< rag_evaluation_run_results.run_id
```

V22 另有 `knowledge_bases → knowledge_sources → knowledge_source_items` 级联外键，以及 `users → knowledge_sources.created_by`、`knowledge_source_items.last_document_id → knowledge_documents`、`knowledge_documents.source_item_id → knowledge_source_items` 限制外键。最后两条形成追溯关系；应用不提供直接删除已导入来源的入口。其余未明确标出的关系为应用维护的逻辑关联；不要将版本链当作可级联删除的数据。

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
| V16 | `V16__add_knowledge_document_publication.sql` | 文档业务版本、发布状态、适用范围和有效期。 |
| V17 | `V17__add_knowledge_answer_report.sql` | 消息模式、范围与知识核验报告。 |
| V18 | `V18__add_knowledge_answer_runs.sql` | 运行 UUID 关联及管理员诊断表。 |
| V19 | `V19__add_knowledge_clarification_policy.sql` | 在 knowledge_bases 增加适用条件 JSON 与独立修订号；旧库默认关闭。 |
| V20 | `V20__create_knowledge_index_tasks.sql` | 增加文档有效索引批次与持久任务表、调度索引、检查约束及级联外键；显式标记旧内存任务失败。 |
| V21 | `V21__add_knowledge_document_revisions.sql` | 增加前后版本关联、审核启用/被替代时间、前驱索引、后继唯一约束及两个限制删除自引用外键；历史资料默认不关联。 |
| V22 | `V22__add_knowledge_oss_sources.sql` | 新增 OSS 来源、对象映射、扫描租约和结果，以及文档 source_item_id 追溯关联；普通历史资料不纳入同步。 |

## `knowledge_sources` OSS 来源表（V22）

固定应用配置的 OSS Bucket 与管理员指定的专属前缀，不保存 AccessKey。来源身份创建后不可编辑；可暂停或恢复。

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 来源主键。 |
| knowledge_base_id | BIGINT | 否 | 无 | 所属知识库。 |
| bucket_name | VARCHAR(255) | 否 | 无 | 登记时的 Bucket；与运行配置不一致时扫描失败。 |
| object_prefix | VARCHAR(120) | 否 | 无 | `external-knowledge/{小写 slug}/`；应用校验顶层固定、末尾斜线、无嵌套。 |
| enabled | BOOLEAN | 否 | TRUE | 是否接受手动及定时扫描。 |
| created_by | BIGINT | 否 | 无 | 创建管理员，后续导入文档沿用此维护者身份。 |
| scan_status | VARCHAR(20) | 否 | IDLE | IDLE / RUNNING / SUCCEEDED / PARTIAL / FAILED。 |
| run_token | VARCHAR(36) | 是 | NULL | 扫描执行令牌，每次领取更换，暂停清空。 |
| lease_until | DATETIME(6) | 是 | NULL | 扫描租约截止；仅有效令牌可续租或提交。完成、失败、暂停清空。 |
| last_scan_at | DATETIME(6) | 是 | NULL | 最近成功、部分成功或失败的完成时间。 |
| error_code | VARCHAR(80) | 是 | NULL | SOURCE_ITEM_ERRORS / SOURCE_SCAN_FAILED / SOURCE_SCAN_CANCELLED；无明文异常。 |
| added_count | INT | 否 | 0 | 本轮首次导入数，领取时重置。 |
| updated_count | INT | 否 | 0 | 本轮新业务版本导入数。 |
| unchanged_count | INT | 否 | 0 | 本轮确认未变数，包含指纹变化但内容与业务规则相同。 |
| failed_count | INT | 否 | 0 | 本轮单对象失败数；整轮清单失败以 scan_status / error_code 表示。 |
| missing_count | INT | 否 | 0 | 完成时已标记 MISSING 的对象总数；不是本轮新增缺失数。 |
| created_at | DATETIME(6) | 否 | 当前时间 | 登记时间。 |

约束：主键 id；`uk_knowledge_source_prefix (bucket_name, object_prefix)` 防止目录重复登记；`idx_knowledge_source_base (knowledge_base_id)`；`fk_knowledge_source_base` 引用 knowledge_bases.id ON DELETE CASCADE；`fk_knowledge_source_creator` 引用 users.id 默认限制删除；`ck_knowledge_source_status` 限定上述状态。计数非负、令牌和租约配对由应用保证。

扫描先完整列举，再逐项检查。默认最多 200 个对象（包括目录占位），超过限制整轮失败。只有完整且没有单项错误的扫描才能新增 MISSING；扫描不是跨页事务快照，缺失只供管理员确认，不自动撤回资料。普通文档 PENDING 状态不触发此扫描。

## `knowledge_source_items` 外部对象映射表（V22）

| 字段 | 类型 | 可空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 外部对象稳定身份。 |
| source_id | BIGINT | 否 | 无 | 所属来源。 |
| object_key | VARCHAR(1024) | 否 | 无 | 精确 OSS 键，大小写敏感比较；不暴露给员工。 |
| key_hash | VARCHAR(64) | 否 | 无 | 原始键 UTF-8 的 SHA-256，解决长键及默认大小写不敏感排序规则的索引问题；命中后仍比较原始键。 |
| accepted_fingerprint | VARCHAR(64) | 是 | NULL | 最近成功接收的 HEAD 指纹（ETag、大小、修改时间、自定义元数据）。失败不推进；文件内容仍单独计算 SHA-256。 |
| last_document_id | BIGINT | 是 | NULL | 最近导入文档，不代表当前生效版本；可能是处理中、待审或被拒绝候选。 |
| last_seen_token | VARCHAR(36) | 是 | NULL | 最近看到对象的扫描令牌，用于完整扫描后的缺失推断。 |
| item_status | VARCHAR(20) | 否 | ERROR | SYNCED / ERROR / MISSING。SYNCED 表示已导入或未变，不代表已索引或已发布。 |
| error_code | VARCHAR(80) | 是 | NULL | SOURCE_METADATA_OR_FILE_INVALID / SOURCE_VERSION_CONFLICT / SOURCE_OBJECT_FAILED。初次占位 ERROR 可暂时无错误码；异常中断由下次扫描重试。 |
| updated_at | DATETIME(6) | 否 | 当前时间 | 应用更新同步结论时写入。 |

约束：主键 id；`uk_knowledge_source_item (source_id, key_hash)`；`fk_knowledge_source_item_source` 引用 knowledge_sources.id ON DELETE CASCADE；`fk_knowledge_source_item_document` 引用 knowledge_documents.id 默认限制删除；`ck_knowledge_source_item_status` 限定上述状态。映射、文档及知识库归属一致、同对象来源标识/范围不变由应用保证。导入提交锁定来源并核对租约，再锁知识库以串行化不同来源对业务身份的认领；快照上传在事务外，候选、任务和 last_document_id / accepted_fingerprint 在同一事务提交。

诊断阶段补充：需要判断适用条件时先执行 `APPLICABILITY`；只有 `applicability.routingAttempted=true` 才实际调用路由模型并计入模型用量。配置未启用/续答不调用路由；DRAFT、REVIEW 仍分别记账，Embedding 用量不在此统计内。
