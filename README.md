# 企业知识助手

面向单个企业内部的知识问答后端，支持版本与适用范围过滤、混合检索、引用解析、语义复核、条件澄清和运行诊断。员工查询制度及操作流程，管理员维护资料与模型配置。

所有新消息统一走 KNOWLEDGE 可信问答；显式 AGENT 仅作为旧客户端输入别名，在入口规范为 KNOWLEDGE。客服工单已退出问答主线，历史模块与接口保留。文档增量更新、多范围受控检索及入口统一已实现；E 轮已完成回归归档与学习交接，当前能力和未解决问题见[项目交付清单](docs/项目交付清单.md)。

学习入口：[架构与事务边界](docs/architecture/企业知识问答架构.md) · [代码阅读与练习](docs/interview/代码阅读与动手练习.md) · [简历描述](docs/interview/简历描述与能力边界.md)。当前主链路是带有限规划的 RAG 工作流，没有原生 Tool Calling/MCP。

当前员工账号继续使用 CUSTOMER 角色代码，管理员使用 ADMIN；角色沿用既有设计。

## 环境要求

- Java 21
- Maven 3.9+
- MySQL 8.0+，默认端口 `3306`
- Elasticsearch 9.x，用于保存全文和向量检索 Chunk
- 可选：阿里云 OSS，用于保存知识库原始文档；未配置时使用本地目录

## 本地启动

服务器 Docker 部署及 OpenResty 接入说明见 [`deploy/README.md`](deploy/README.md)。

创建本地数据库：

```sql
CREATE DATABASE helpdesk_agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

将 `src/main/resources/application-local.example.yml` 复制为 `application-local.yml`，然后填写本地数据库账号和密码。再将 `.env.example` 复制为 `.env`，按需填写安全配置；这两个本地文件均已被 Git 忽略，不会提交到远程仓库。Spring Boot 通过 `optional:file:.env[.properties]` 读取扩展名为空的 `.env` 文件。

启动前提供 JWT 签名密钥和模型配置加密主密钥。模型配置密钥必须是 32 个随机字节的 Base64 编码；生产环境必须使用安全随机值并妥善备份：

```powershell
$env:JWT_SECRET = "请替换为至少32字节的随机密钥"
$keyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
$env:MODEL_CONFIG_ENCRYPTION_KEY = [Convert]::ToBase64String($keyBytes)
mvn test
mvn spring-boot:run
```

知识库文档默认写入 `./data/objects`。使用阿里云 OSS 时，在 `.env` 中配置以下项目；AccessKey 建议使用仅允许操作目标 Bucket 指定前缀的 RAM 用户凭据：

```properties
STORAGE_PROVIDER=aliyun-oss
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
OSS_BUCKET=your-private-bucket
OSS_ACCESS_KEY_ID=
OSS_ACCESS_KEY_SECRET=
```

本地 Elasticsearch 开启 HTTPS 时，填写连接账号以及安装目录生成的 CA 证书路径：

```properties
ELASTIC_URI=https://localhost:9200
ELASTIC_USERNAME=elastic
ELASTIC_PASSWORD=<本地 Elasticsearch 密码>
ELASTIC_CA_CERT_PATH=D:/elasticsearch-9.5.4/config/certs/http_ca.crt
ELASTIC_INDEX_NAME=helpdesk_knowledge_chunks_v1
```

应用启动时，Flyway 会自动执行 `src/main/resources/db/migration` 中尚未执行的数据库迁移。

## 接口文档

本地启动后可以通过 Swagger UI 查看和调试接口：

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

登录后复制响应中的 `accessToken`，点击 Swagger UI 右上角的 **Authorize**，直接粘贴令牌即可；无需手动添加 `Bearer` 前缀。接口文档页面本身可以公开访问，但执行客户、客服或管理员接口仍会校验 JWT 及角色。

生产环境可以关闭接口文档：

```powershell
$env:OPENAPI_ENABLED = "false"
```

## 数据库表

完整字段、索引、枚举取值和表关系说明见 [`docs/database-schema.md`](docs/database-schema.md)。

- `tickets`：工单业务表，保存工单编号、标题、问题描述、优先级、状态、创建人和创建时间等信息。
- `users`：用户表，保存登录账号、BCrypt 密码哈希、显示名称、角色和启用状态。
- `ai_model_configs`：模型配置表，保存协议、厂商、基础地址、模型名称、能力开关和 AES-GCM 加密后的 API Key。
- `conversations`：客户对话表，保存对话归属、标题、状态和最近消息时间。
- `chat_messages`：对话消息表，保存客户消息、AI 回答、生成状态、模型快照和 Token 用量。
- `agent_tool_calls`：工具调用记录表，保存工具参数、模型可见结果、状态和执行顺序。
- `agent_pending_actions`：待确认操作表，保存 AI 生成的工单草稿及确认、取消、过期和执行状态。
- `knowledge_bases`：知识库表，保存名称、说明和创建信息。
- `knowledge_documents`：知识库文档元数据表，保存对象位置、文件摘要和处理状态；原始内容不进入 MySQL。
- `knowledge_index_tasks`：每份文档最近一次索引任务，保存排队、重试次数和执行租约。
- `rag_evaluation_cases`、`rag_evaluation_case_documents`：保存固定评测问题及其多个期望文档。
- `rag_evaluation_runs`、`rag_evaluation_run_results`：保存每次检索评测的参数、汇总指标和逐题快照。
- `flyway_schema_history`：Flyway 自动创建的迁移记录表，用于记录每个 SQL 迁移脚本是否执行成功，防止重复建表。请勿手动修改或删除其中的数据。

## 知识库管理

管理员可以管理知识库、上传原始文档并异步建立 Elasticsearch 索引。上传支持 `txt`、`md`、`pdf`、`docx`，单文件最大 20 MB；上传成功后状态为 `PENDING`，触发索引后依次进入 `PROCESSING`、`READY` 或 `FAILED`。

```http
POST   /api/admin/knowledge-bases
GET    /api/admin/knowledge-bases
GET    /api/admin/knowledge-bases/{id}
POST   /api/admin/knowledge-bases/{id}/default
PUT    /api/admin/knowledge-bases/{id}/retrieval-settings
DELETE /api/admin/knowledge-bases/{id}
POST   /api/admin/knowledge-bases/{id}/documents
GET    /api/admin/knowledge-bases/{id}/documents?page=1&size=20
GET    /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}
GET    /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/content
DELETE /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}
POST   /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunk-preview?page=1&size=20
POST   /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/index
POST   /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/submit
GET    /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/index-task
POST   /api/admin/knowledge-bases/{id}/search
```

文档上传接口使用 `multipart/form-data`，字段名为 `file`。`content` 接口在 ADMIN 鉴权后以内联文件流返回原始文档，可用于管理端预览和下载，不会暴露对象存储地址。知识库中存在文档时不能直接删除；应先逐个删除文档，确保对象存储内容同步清理。

分块预览接口会从本地目录或 OSS 下载原文，通过 Apache Tika 解析为纯文本，再分页返回实际分块。默认每块最多 1000 个字符，相邻块重叠 150 个字符，并优先在段落、换行和中英文句末处分割。可以通过 `.env` 调整：

```properties
KNOWLEDGE_MAX_EXTRACTED_CHARACTERS=2000000
KNOWLEDGE_CHUNK_SIZE=1000
KNOWLEDGE_CHUNK_OVERLAP=150
KNOWLEDGE_EMBEDDING_BATCH_SIZE=16
```

预览只用于检查解析和切块效果，会在每次请求时重新处理文档，不修改处理状态。索引接口返回 `202 Accepted` 表示任务已持久化；默认 Embedding 模型和 ES 检查改由后台执行，外部依赖不可用时通过任务状态报告。仅草稿允许建立或重新建立索引；草稿的 `READY` 或 `FAILED` 文档可以重新提交，`PROCESSING` 文档重复调用索引接口返回现有状态，不重复入队，期间禁止修改元数据或删除。

推荐使用 `POST .../documents/{documentId}/submit`，请求体与下方元数据 PUT 相同，原子完成“保存完整元数据 + 提交索引”，不用再单独调用 index。原 PUT 仍仅保存元数据；单纯上传或仅保存留下的 PENDING 文档不会被定时任务擅自处理。后台索引完成后仍需管理员发布。

`GET .../documents/{documentId}/index-task` 返回最近一次任务状态（QUEUED / RUNNING / SUCCEEDED / FAILED）、执行次数、预算、下次可执行时间、租约时间和固定错误码；尚未提交返回 `data: null`。文档 PROCESSING 包含排队、执行及自动重试等待。默认失败退避 30 秒、最多执行 3 次，解析错误直接失败；超时租约可恢复，旧执行者不能激活或覆盖新批次。配置如下：

```properties
KNOWLEDGE_INDEX_TASKS_ENABLED=true
KNOWLEDGE_INDEX_CONCURRENCY=3
KNOWLEDGE_INDEX_MAX_ATTEMPTS=3
KNOWLEDGE_INDEX_LEASE_SECONDS=120
KNOWLEDGE_INDEX_RETRY_DELAY_SECONDS=30
```

每个实例默认同时处理 3 份文档，每 2 秒为运行任务续租并补充空闲执行位。工作线程开始执行后才领取任务，每份文档独立重试、提交索引批次。`KNOWLEDGE_INDEX_CONCURRENCY` 修改后重启生效；多实例的总并发是各实例并发数之和，需要结合向量模型配额调整。

**V20 升级前先停止旧版实例，不与旧工作者混跑。** 历史 PROCESSING 标为 FAILED / INDEX_TASK_MISSING，管理员检查后重试；历史有效索引继续兼容。索引批次隔离与任务设计、测试边界见 [`notes/33-文档提交与可靠索引.md`](notes/33-文档提交与可靠索引.md)。

### 文档业务版本与发布

`status=READY` 仅表示索引完成。正式检索还要求 `publicationStatus=PUBLISHED`、处于生效区间且适用范围匹配。**V16 将历史文档保留为草稿，不自动发布；升级后需补齐业务元数据、重新索引、发布，才能恢复正式召回。**

上传后按以下顺序操作，每次从文档详情读取最新的乐观锁 `version`：

```http
PUT  /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/metadata
POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/index
POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/publish
POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/withdraw
```

元数据 PUT 请求示例（时间统一使用 Asia/Shanghai，不带时区偏移；有效期为左闭右开）：

```json
{
  "version": 1,
  "sourceKey": "travel-policy",
  "businessVersion": "2026.2",
  "applicabilityScope": "CN_SH",
  "validFrom": "2026-10-01T00:00:00",
  "validUntil": null
}
```

`sourceKey` 是跨业务版本保持稳定的来源标识；同库的来源、业务版本、范围组合唯一。范围使用大写代码，`ALL` 表示通用，**不表示员工访问权限**。`businessVersion` 只是标签，不用它自动选最新版。元数据只可修改草稿，保存后需要重新索引；清空 `validUntil` 会移除原截止时间。

索引异步完成至 `READY` 后，发布和撤回均提交 `{"version": 当前乐观锁版本}`。缺少元数据、处理未完成、错误状态或旧版本请求返回 409；非法日期区间返回 400。已发布记录不可直接修改或重建索引，修订须上传新业务版本。撤回后不再参与新的检索，也不能重新发布。

### 上传新版与审核交接

更新已有资料时使用明确的新版入口，来源和适用范围继承旧版，原文件保持独立：

```http
POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{旧版ID}/revisions
Content-Type: multipart/form-data
```

请求包含 `file` 文件及 `metadata` JSON 部分（该部分 Content-Type 为 `application/json`）：

```json
{
  "predecessorVersion": 5,
  "businessVersion": "2026.3",
  "validFrom": "2026-11-01T00:00:00",
  "validUntil": null
}
```

新建候选返回 202，`data.outcome=CREATED`，`data.document` 包含新文档 ID、PROCESSING 状态和 predecessorDocumentId。文件上传成功后自动提交索引；通过现有文档详情及 index-task 接口查询进度。原始文件哈希、解析扩展名、业务版本和有效期均相同时，返回 200 / UNCHANGED（旧文档）或 EXISTING（已有相同候选），不重复创建对象或任务。同业务版本但内容或日期不同返回 409，需要使用新版本标签。

仅文件相同但业务元数据变化仍创建候选并完整索引，当前不复用分块向量。无变化及已有候选是只读返回，可以重发此前成功请求；新建候选必须提供旧版当前 predecessorVersion。新版元数据随上传固定，需改动时另建候选；处理失败可显式重试索引，上传失败则检查或删除失败候选后重新上传。

候选 READY 后，读取双方最新乐观锁版本并审核：

```http
POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{候选ID}/approve-replacement
Content-Type: application/json

{"version": 4, "predecessorVersion": 5}
```

返回双方文档和 `effectiveAt`。实际交接时刻取审核时刻与新版 validFrom 的较晚值：旧版在此时刻退出、新版从此时刻参与检索，未来生效无需定时改状态。旧版本身的有效期必须覆盖到交接时刻，新版截止必须晚于交接；不自动延长旧制度。并发审核只有一个候选成功；候选禁止调用普通 publish 绕过审核。

文档详情新增 predecessorDocumentId、successorDocumentId、activatedAt、supersededAt。旧版仍可显示 PUBLISHED，但过了 supersededAt 已不可检索；原 validUntil 和原文件保留，证据元数据采用实际有效区间。新版构建失败或尚未审核时，不改变旧版的问答资格。

使用原 withdraw 接口撤回尚未生效的新版，会同时取消旧版的退出边界；已生效后撤回新版不会自动恢复旧制度。存在未来接替安排时，先撤回新版才能撤回旧版。已审核或被候选引用的版本保留用于追溯，禁止物理删除；未审核且停止处理的候选允许删除。

V21 不推断历史版本关系。**所有问答实例应升级到理解交接边界的代码后，再启用替换功能。** 设计与限制见 [`notes/34-文档新版与审核交接.md`](notes/34-文档新版与审核交接.md)。普通上传和独立发布仍兼容，本功能只保证明确关联的版本交接，不自动裁决全库同源冲突。

### OSS 外部来源同步

V22 支持登记当前 OSS Bucket 中的独立 `external-knowledge/{slug}/` 目录。系统仅扫描已登记来源，内部上传的待处理草稿不会被自动接管。文件通过 OSS 自定义元数据提供来源标识、业务版本、范围及有效期；修改时间不会被当成制度生效时间。

完整列举后识别新增、修改、未变与缺失：未变文件不重复下载或入队；首次导入自动索引但仍为草稿；修改生成待审核新版，复用持久任务及版本交接。完整且无错误的扫描才新增缺失标记，源文件删除不会自动撤回已发布快照。来源资料只能在外部修改内容和业务元数据，知识库负责索引、审核与撤回。

管理员使用 `/api/admin/knowledge-bases/{baseId}/sources` 创建来源，通过 `/{sourceId}/scan` 手动扫描、`/{sourceId}/items` 查看结果。默认定时扫描每 60 秒启动，来源有独立数据库租约，扫描线程不阻塞索引任务心跳。配置、接口、元数据格式和真实联调脚本见 [OSS 来源同步验收](docs/demo/oss-source/README.md)，设计和验证记录见 [阶段 35](notes/35-OSS来源同步与变更识别.md)。

### 受控多跳与开发对照

阶段 41 将候选规划收敛为一份任务列表，删除核查专用队列；同一冻结 12 题实测 **10 通过、1 未通过、1 技术失败**。虽然缺规则题改善，冲突题仍错误确定回答，未满足采用标准，候选继续关闭（`app.knowledge.multihop.task-planning-enabled=false`）。本项候选迭代结束，下一步转向统一可信问答入口；默认仍为阶段 37 规划与 BASELINE 复核。详见 [实现与取舍](notes/41-单列表规划与候选取舍.md) 和 [完整对照](docs/evaluation/runs/2026-10-21-单列表规划候选取舍.md)。

旧 `rule-checks-enabled` / `KNOWLEDGE_MULTIHOP_RULE_CHECKS_ENABLED` 不再启用候选。显式复现新候选使用 `task-planning-enabled` / `KNOWLEDGE_TASK_PLANNING_ENABLED`，不建议在日常运行启用。历史阶段 38–40 的失败与回放记录保留，不代表当前默认行为。

KNOWLEDGE 默认启用受控补查：首轮原问题搜索，最多两次规划、每次最多两个补查，累计证据去重，最后继续引用校验、复核与资格检查。C1 的同一套 18 份资料、12 题在首次 C2 对照中由 6 题自动通过提升到 9 题，完整来源链召回由 6/12 提升到 11/12；仍有 1 道漏冲突、2 道复核失败，不能声称已解决全部多跳或冲突问题。详见 [首次对照](docs/evaluation/runs/2026-10-21-受控多跳首次对照.md)、[原单轮基线](docs/evaluation/runs/2026-10-20-多跳单轮基线.md)、[运行方法](docs/demo/multihop/README.md) 和 [阶段 37](notes/37-受控多跳检索.md)。

`app.knowledge.multihop.enabled=false` 可进行单轮对照；默认调度软预算 90 秒、累计证据硬上限 50 块/50000 字符。逐轮查询和停止原因仅在管理员诊断中展示。逻辑模型调用上限不含 SDK 内部 HTTP 重试，规划用量已计入消息及诊断，Embedding 和未返回的重试用量不在已知成本内。

检索请求可以增加 `"applicabilityScope": "CN_SH"`，同时查询该范围与 `ALL`；省略范围只查通用资料。MySQL 先产生可用文档 ID，BM25 和 KNN 都在召回前过滤；返回后再复核撤回和到期情况。单次准入超过 10000 份文档会明确失败，需要拆分知识库。新旧有效期重叠时保留双方，正文冲突检测尚待后续实现。

公开虚构演示资料及固定问题见 [`docs/demo/trusted-knowledge/README.md`](docs/demo/trusted-knowledge/README.md)。

混合检索接口会为问题生成查询向量，通过一次 Elasticsearch `_msearch` 分别执行 BM25 全文检索和 KNN 向量检索，再在应用层使用 RRF 去重融合。当前本地 ES 的 Basic 许可证不包含原生 RRF，因此应用层融合可避免商业许可证依赖。每个知识库独立维护检索参数，默认单路候选上限为 50、向量相似度下限为 0、最终结果上限为 5：

```http
PUT /api/admin/knowledge-bases/{id}/retrieval-settings
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "candidateLimit": 50,
  "vectorMinSimilarity": 0.650,
  "resultLimit": 5
}
```

`candidateLimit` 允许 1～200，`vectorMinSimilarity` 必填且允许 0～1，`resultLimit` 允许 1～50 且不能超过候选上限。相似度过滤始终启用，0 表示过滤负相关向量。KNN 的 `num_candidates` 不开放配置，由后端固定为 `candidateLimit × 2`。参数修改后立即影响后续查询，不需要重新索引。

```http
POST /api/admin/knowledge-bases/{id}/search
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "query": "如何配置模型接口？"
}
```

响应会回显本次生效的三个参数，并给出融合名次、RRF 分数以及 Chunk 在 BM25、KNN 两路中的名次和原始分数，便于调试召回效果。向量检索只使用由当前默认 Embedding 配置生成的 Chunk；切换默认向量模型后，应重新索引文档并重新评估相似度阈值。

公开制度的三路检索对照、100 题分集及证据段落评分见 [公开制度检索评测](docs/evaluation/public-campus/README.md)。管理员可通过 `POST /api/admin/knowledge-bases/{id}/search-candidates` 获取完整双路候选并集，避免普通 Top-K 截断影响单路基线；该接口不改变问答流程的检索结果上限。

检索后的正确交付、引用支持与无答案拒答对照见 [问答质量评测](docs/evaluation/public-campus/ANSWERS.md)。80 题首轮实验保留所有失败及同模型辅助评分，不将其作为人工准确率或质量提升证明。

单次检索与受控补查的20题自建场景对照见 [跨文档关联问答](docs/evaluation/bridge-qa/README.md)。充值后完整回答由10/20提升至20/20，平均额外检索1.2次；这是合成关联题专项实验，不代表真实用户准确率。

管理员可以维护固定检索评测集。每道问题支持选择 1～20 个 `READY` 状态的期望文档，单次最多批量运行 100 道问题：

```http
POST   /api/admin/knowledge-bases/{id}/evaluation/cases
GET    /api/admin/knowledge-bases/{id}/evaluation/cases
PUT    /api/admin/knowledge-bases/{id}/evaluation/cases/{caseId}
DELETE /api/admin/knowledge-bases/{id}/evaluation/cases/{caseId}
POST   /api/admin/knowledge-bases/{id}/evaluation/runs
GET    /api/admin/knowledge-bases/{id}/evaluation/runs?limit=10
GET    /api/admin/knowledge-bases/{id}/evaluation/runs/{runId}
DELETE /api/admin/knowledge-bases/{id}/evaluation/runs/{runId}
```

每次运行会固定使用开始时的候选数量、相似度下限和最终结果数量，保存文档级 `Hit@K`、平均 `Recall@K`、MRR、平均耗时及逐题实际召回 Chunk。相同文档出现多个 Chunk 时只计一次文档命中；历史运行保存问题、期望文档和召回结果快照，不受后续用例修改影响。删除某次运行会同时删除汇总和逐题快照，但不会影响评测用例。被当前评测用例引用的文档不能删除，应先调整对应评测用例。

管理员可通过 `POST /api/admin/knowledge-bases/{id}/default` 设置唯一默认知识库。新问答由可信服务执行检索，知识库身份由服务端绑定，不通过旧自由工具对话选择；逐次查询和证据保存到运行诊断。`search_knowledge_base` 旧实现及其工具审计暂保留供历史兼容，会话入口不再调用旧模型客户端。原检索评测默认只评测 `ALL` 通用资料。

历史 AGENT 知识检索记录中，AI 消息的 `citations` 字段会返回本轮命中的知识库名称、来源文件名、Chunk 序号、完整片段内容、`documentId` 和 `metadata` 业务版本快照。同名不同文档按主键区分，旧工具记录缺少新字段时仍能显示。历史查询继续读取旧工具引用；新同步回答和流式 `done` 从核验报告生成引用。引用代表检索来源，尚不代表已验证其支持回答中的每项结论。历史引用保留当时快照，新问答必须重新检索。

## 登录认证

公开注册只会创建 `CUSTOMER` 客户账号：

```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "customer01",
  "password": "Password123",
  "displayName": "测试客户"
}
```

登录接口返回 JWT：

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "customer01",
  "password": "Password123"
}
```

当前访问令牌有效期为 240 小时，方便本地联调时保持登录状态；生产部署应根据安全要求缩短有效期，并补充刷新令牌和主动撤销机制。修改有效期后，已经签发的旧令牌不会自动延长，需要重新登录。

调用受保护接口时携带访问令牌：

```http
Authorization: Bearer <accessToken>
```

当前尚未实现管理员创建内部账号的接口。本地开发时可先注册账号，再在数据库中将其 `role` 更新为 `SUPPORT` 或 `ADMIN`，重新登录后即可获得对应令牌。

## 模型配置

DeepSeek 思考参数在 `application.yaml` 的 `app.model-config.deepseek` 下设置：`thinking-type` 默认 `enabled`（可改为 `disabled`），`reasoning-effort` 默认 `low`（可改为厂商支持的其他强度，例如 `high`）。也支持环境变量 `DEEPSEEK_THINKING_TYPE`、`DEEPSEEK_REASONING_EFFORT` 覆盖。修改后重启后端生效，作用于所有厂商标记为 `DEEPSEEK` 的文本模型；不按配置显示名识别。关闭思考时不发送强度字段，不新增管理接口或数据库字段，其他厂商不附带这两个参数。

模型统一使用 OpenAI 兼容协议。管理员选择内置厂商时，后端自动使用预设地址；选择 `CUSTOM` 时才需要填写 SDK 基础地址。创建配置时的 API Key 会使用 AES-256-GCM 加密，后续接口只返回尾号掩码。

按模型用途查询可选厂商及其预设地址；省略 `modelType` 时默认查询聊天模型厂商：

```http
GET /api/admin/model-configs/vendors?modelType=CHAT
Authorization: Bearer <管理员 accessToken>
```

使用 DeepSeek 预设创建配置：

```http
POST /api/admin/model-configs
Authorization: Bearer <管理员 accessToken>
Content-Type: application/json

{
  "name": "DeepSeek 主模型",
  "modelType": "CHAT",
  "vendor": "DEEPSEEK",
  "modelName": "deepseek-chat",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": true,
  "supportsTools": true,
  "enabled": true
}
```

Embedding 模型不使用聊天厂商的地址预设，必须选择 `CUSTOM`、自行填写 OpenAI 兼容基础地址并声明实际输出维度；流式和工具能力必须为 `false`：

```json
{
  "name": "知识库向量模型",
  "modelType": "EMBEDDING",
  "vendor": "CUSTOM",
  "baseUrl": "https://proxy.example.com/v1",
  "modelName": "text-embedding-model",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": false,
  "supportsTools": false,
  "embeddingDimensions": 1024,
  "enabled": true
}
```

当前内置厂商为 `OPENAI`、`DEEPSEEK`、`QWEN` 和 `OLLAMA`。接入代理服务或其他兼容厂商时使用 `CUSTOM`，并填写类似 `https://proxy.example.com/v1` 的基础地址；不要填写以 `/chat/completions` 结尾的完整接口地址：

```json
{
  "name": "自定义兼容模型",
  "vendor": "CUSTOM",
  "baseUrl": "https://proxy.example.com/v1",
  "modelName": "custom-chat",
  "apiKey": "<真实 API Key>",
  "supportsStreaming": true,
  "supportsTools": true,
  "enabled": true
}
```

管理接口：

```http
GET  /api/admin/model-configs
GET  /api/admin/model-configs/vendors
GET  /api/admin/model-configs/{id}
PUT  /api/admin/model-configs/{id}
POST /api/admin/model-configs/{id}/test
POST /api/admin/model-configs/{id}/default
POST /api/admin/model-configs/{id}/enable
POST /api/admin/model-configs/{id}/disable
```

更新配置时省略 `apiKey` 会保留原密钥，更新 Embedding 配置时省略 `embeddingDimensions` 会保留原维度。创建请求省略 `modelType` 时按 `CHAT` 兼容，更新时省略则保留当前类型。接口响应中的 `protocol` 固定为 `OPENAI_COMPATIBLE`；`vendor` 只为聊天模型提供地址预设，Embedding 固定使用 `CUSTOM`。`CHAT` 和 `EMBEDDING` 分别维护一个默认模型。默认模型必须处于启用状态，也不能直接禁用；应先切换默认模型。连接测试会按类型发送一个极短对话或向量请求，可能产生少量费用。

## 企业知识问答接口

发送消息前需要配置并启用默认 Chat 模型；有资料的知识检索还需默认 Embedding 模型和 ES。创建员工对话：

```http
POST /api/conversations
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "title": "差旅制度咨询"
}
```

向对话发送消息并同步等待 AI 回答：

```http
POST /api/conversations/{id}/messages
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "content": "差旅结束后多久内提交报销？"
}
```

`mode` 省略、null、KNOWLEDGE和旧AGENT别名均进入同一可信服务，正文最多2000字。省略范围时按主题配置识别或澄清，未配置则查ALL。SSE先发送start，待核验并保存后发送完整正文，无需厂商支持流式。调用方应同时接受SSE和JSON，以接收前置校验错误；新响应mode均为KNOWLEDGE。

```http
POST /api/conversations/{id}/messages/stream
Authorization: Bearer <客户 accessToken>
Content-Type: application/json
Accept: text/event-stream, application/json

{
  "content": "差旅结束后多久内提交报销？"
}
```

事件按照以下顺序返回：

```text
event: start   已保存客户消息，并创建 PENDING 状态的 AI 消息
event: delta   核验和保存后的一次完整正文
event: done    回答完成，携带最终保存的 AI 消息
event: error   生成失败，携带脱敏错误码和说明
```

流开始后的模型异常不会改写已经发送的 HTTP 状态码，而是通过 `error` 事件通知客户端；对应 AI 消息会保存为 `FAILED`。客户端主动中断连接时，服务端会尝试将尚未完成的 AI 消息标记为 `STREAM_CANCELLED`。

流式请求默认允许持续 5 分钟，可通过环境变量 `APP_CHAT_STREAM_TIMEOUT` 调整，例如设置为 `10m`。

如果应用被强制关闭，来不及完成正在生成的 AI 消息，系统会把超过 10 分钟仍为 `PENDING` 的回答恢复为 `FAILED`，错误码为 `GENERATION_INTERRUPTED`。恢复会在客户下一次发送前立即执行，后台任务也会每分钟扫描一次；关联的运行中工具记录会失败化，未确认工单草稿会作废。可以调整以下参数，但回收阈值必须大于流式请求超时：

```properties
CHAT_PENDING_TIMEOUT=10m
CHAT_PENDING_RECOVERY_INTERVAL=1m
```

其他对话接口：

```http
GET /api/conversations?page=1&size=20&status=ACTIVE
GET /api/conversations/{id}
GET /api/conversations/{id}/messages?page=1&size=50
DELETE /api/conversations/{id}
```

员工只能访问自己的对话。普通追问使用当前会话最近六条已完成的知识问答消息补全问题，然后重新检索、生成和核验；历史文字只帮助理解指代与条件，不作为事实证据。独立新问题不继承旧主题，指代不清先返回澄清问题。首轮和范围卡片续答跳过补全；卡片仍由服务端恢复原问题与原知识库。CHAT_HISTORY_LIMIT不影响此固定窗口，每条历史最多取2000字。详见 [自然语言追问](notes/49-自然语言追问与上下文.md)。同步接口调用失败时返回 `502 CHAT_GENERATION_FAILED`；流式接口开始传输后调用失败时返回 `error` 事件。两种方式都会保留客户消息和一条 `FAILED` AI 消息，且不会向客户端暴露厂商原始异常。

### 证据约束知识问答

同一消息接口接受省略/null/KNOWLEDGE/AGENT四种输入形式，均规范为KNOWLEDGE，进入服务端强制的“本轮检索 → 结构化草案 → 引用校验 → 独立语义复核 → 准入复查 → 保存并返回”链路。阶段45起默认采用 GROUNDED 复核；首次有具体缺陷时最多修订一次，再经过相同校验及复核，第二次仍否决则失败。保留充分解释与原检索配置，两轮诊断与用量均保存，详见[实现说明](notes/45-问答复核与一次修订.md)。可用 `KNOWLEDGE_REVIEW_STRATEGY=BASELINE` 显式回到旧策略对照。

```http
POST /api/conversations/{id}/messages
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "content": "上海员工当前出差住宿每晚报销上限是多少？",
  "mode": "KNOWLEDGE",
  "applicabilityScope": "CN_SH"
}
```

- 知识问题最多 2000 字，每次提交完整问题；此模式不带历史事实、不执行工单工具。使用默认知识库，范围可由调用方传单值 `applicabilityScope` 或最多三项 `applicabilityScopes`；省略时按已配置主题中的完整地区名称识别，无法明确识别时返回澄清，未配置主题则仍只查 `ALL`。旧AGENT请求同样支持范围及clarificationMessageId；非法范围、无效续答和未知模式仍在生成前拒绝。
- `assistantMessage.knowledgeAnswer.answer.status` 返回 `ANSWERABLE`（充分依据）、`NEEDS_CLARIFICATION`（需补条件）、`INSUFFICIENT_EVIDENCE`（依据不足）或 `CONFLICT`（疑似冲突）。不足时允许保留有依据部分；冲突时展示双方，不按版本号或上传时间选边。
- 草案模型只返回本轮 `citationSetId` 与 `evidenceId + paragraphId`。后端按空行建立片段目录、验证所属证据并逐字提取完整原文；跨轮目录、未知片段、跨证据片段均拒绝，不放宽引用校验。过长段落保留上下文但不可引用。
- 报告含 `claims[].references[]` 的 `evidenceId` 和逐字 `quote`，通过 `evidence[]` 对应真实 `documentId`、`chunkSequence`、原文与业务版本。正文中的 `[E1]` 由服务端生成。兼容字段 `citations` 只包含实际使用的证据；报告还保留未被引用的本轮候选，便于审计遗漏。
- `reason=NO_MATCH/NOT_CONFIGURED` 区分无命中与未配置；`REVIEWED` 表示有证据且通过复核。检索异常、协议错误、引用伪造、复核否决、生成期间证据失效均是技术失败，不能当作正确拒答统计；消息历史保存安全的知识阶段错误码（如 `KNOWLEDGE_CITATION_INVALID`），未知或旧版本错误仍为 `KNOWLEDGE_ANSWER_FAILED`，无草案正文。
- `/messages/stream` 接受相同请求字段：先 `start`，执行阶段实时发送 `progress`，核验和保存完成后一次性 `delta`，最后 `done`；失败以 `error` 结束。`progress` 包含 `assistantMessageId`、`stage`、固定中文 `message`，不包含草稿或诊断原文。补查、修订会导致阶段重复，未执行的阶段不发送。所有入口均无需厂商流式能力，正文等待核验完成。协议与验证见 [阶段46记录](notes/46-SSE阶段进度通知.md)。
- 升级注意：旧客户端显式发送AGENT也采用2000字限制、完整正文SSE及统一安全错误码；响应和新存储mode为KNOWLEDGE。pendingActions保留空数组，不再发action_required。前端可以隐藏模式选择；只在存在相应核验报告时展示结果状态，不能因历史AGENT有citations就标成已核验。本仓库未修改外部前端。
- 历史消息保存 `mode`、范围和完整核验报告；历史证据不会随知识库撤回而消失，不能据此推断今天的规则。V17 会自动迁移旧消息为 AGENT。
- V18 为新知识助手消息增加 `knowledgeRunId`，成功或失败均保留。管理员可通过 `GET /api/admin/knowledge-answer-runs/{runId}` 查询候选、草案、核验结果、分阶段耗时及已知用量；列表接口只返回摘要。客户消息不会返回未核验草案。诊断存储故障与业务结果隔离，可能留下缺失或未结束快照，不能把运行 ID 当作诊断完整性的保证。新诊断版本 2 额外保留 `citationCatalog` 和模型原始 `selection`；`draft` 是引用解析后的草案。旧诊断仍可读取。设计见 `notes/27-问答运行记录与失败诊断.md` 和 `notes/28-片段选择与原文引用.md`。

程序严格检查引用身份和逐字出处；语义复核使用同一个配置模型的独立调用，仍可能误判，不构成事实正确性的数学保证。候选超过 50 个 Chunk、正文合计超过 50000 字时明确失败，不静默丢弃可能含冲突的资料。当前冲突识别仅覆盖本次检索命中的资料，还没有全库冲突扫描或前端模式选择器。基础实现与测试细节见 `notes/25-证据约束回答与核验.md`，真实模型运行结果见下述专项评测。

可信问答现有 15 道固定回归题及标准库 Python 运行器，使用真实 HTTP 接口，独立记录技术失败、状态不符、引用检查和需审阅的语义问题。完整模式创建评测库并按“正常 → 冲突 → 撤回恢复 → 撤回后无依据”顺序执行，最后恢复原默认库。运行方法和失败阶段码见 `docs/evaluation/README.md`，开发记录见 `notes/26-可信问答脚本评测与失败诊断.md`，各轮结果和未解决问题见 [2026-10-19 实测报告](docs/evaluation/runs/2026-10-19-可信问答实测报告.md)。真实运行不是单元测试，也不把模型桩成绩当成真实准确率。

删除对话采用归档方式：对话更新为 `ARCHIVED` 后不再出现在默认客户列表中，也不能继续发送消息，但消息、工具调用和工单草稿仍保留用于审计。列表接口默认查询 `ACTIVE`，需要审计归档记录时可显式传入 `status=ARCHIVED`。正在生成回答的对话不能删除；已经归档的对话重复删除保持幂等。

## 历史兼容：工单接口

这些接口和权限规则保留供历史业务使用，不属于企业知识问答主线，问答模型不再注册工单工具。

创建工单：

```http
POST /api/tickets
Content-Type: application/json
Authorization: Bearer <客户 accessToken>

{
  "title": "无法连接公司 VPN",
  "description": "VPN 客户端连接时提示超时。",
  "priority": "HIGH"
}
```

查询工单详情：

```http
GET /api/tickets/{id}
```

分页查询工单，可按创建人和状态筛选：

```http
GET /api/tickets?page=1&size=20&status=PENDING
```

客户只能查询自己的工单，创建人编号从 JWT 获取。优先级可选值为 `LOW`、`MEDIUM`、`HIGH`；当前新工单的初始状态为 `PENDING`。

## 历史兼容：工单草稿确认

旧客服版本通过 `prepare_create_ticket` 生成待确认草稿，并使用 `pendingActions` / `action_required` 返回。当前模型已不再注册该工具，新问答不产生工单草稿；未过期的历史草稿仍按原权限、版本和有效期规则处理。以下接口保留用于历史操作。

查询对话中的操作卡片：

```http
GET /api/agent/actions?conversationId={conversationId}&status=PENDING
Authorization: Bearer <客户 accessToken>
```

客户可以在确认前修改标题和描述，不能修改 AI 建议的优先级：

```http
PATCH /api/agent/actions/{actionId}
Authorization: Bearer <客户 accessToken>
Content-Type: application/json

{
  "title": "重置密码后仍无法登录",
  "description": "今天重置两次密码后仍提示密码错误。",
  "version": 0
}
```

确认或取消时必须携带卡片最新的 `version`：

```http
POST /api/agent/actions/{actionId}/confirm
POST /api/agent/actions/{actionId}/cancel

{
  "version": 1
}
```

草稿有效期为 24 小时。确认接口具有幂等性，重复确认只返回第一次创建的工单，不会重复建单。

## 历史兼容：工单处理流程

工单状态按照以下规则流转：

```text
PENDING（待处理）→ PROCESSING（处理中）→ RESOLVED（已解决）
       └────────→ CANCELLED（已取消）←────────┘
```

客户可以取消自己创建且处于待处理或处理中状态的工单。处理中工单被取消后会保留已分配的客服信息，以便审计；已解决和已取消的工单不能再次取消：

```http
POST /api/tickets/{id}/cancel
Authorization: Bearer <客户 accessToken>

{
  "reason": "问题已经自行解决"
}
```

客服可以查询工单队列并领取待处理工单：

```http
GET  /api/support/tickets?status=PENDING
POST /api/support/tickets/{id}/claim
Authorization: Bearer <客服 accessToken>
```

只有领取该工单的客服可以填写处理结果并完结工单：

```http
POST /api/support/tickets/{id}/resolve
Authorization: Bearer <客服 accessToken>

{
  "resolution": "已重置客户的上传配额"
}
```

Spring Security 根据 JWT 中的角色限制访问：`CUSTOMER` 可以操作自己的工单，`SUPPORT` 可以访问客服处理接口。客户和客服编号均从已验证的 JWT 中获取，不接受客户端传值。

### 复核专项评测

提供固定问题、证据与草案的 12 组正反例，可单独比较复核误杀、漏检、技术失败和成本。运行说明见 [复核专项评测](docs/evaluation/knowledge-review.md)。早期默认策略为 `BASELINE`，`app.knowledge.review-strategy=CHECKLIST` 仅用于显式实验；该轮候选退化且更耗 Token，未被采用，完整数据见 [专项对比报告](docs/evaluation/runs/2026-10-19-复核专项对比.md)。普通单元测试不会调用真实模型。

随后将真实 B03/B09 失败输入及反例追加为 28 题，对 `CONTEXTUAL` 候选完成 112 次固定输入复核。虽然总体误杀减少，但逐轮错误放行和挑战样本未达到预设门槛，因此当时保留 `BASELINE`，详见 [真实失败回放报告](docs/evaluation/runs/2026-10-19-真实失败复核与练习环境.md)。阶段45根据“项目开发记录”的真实失败增加按问题核验与一次修订，默认改为 `GROUNDED`；此结论不代表旧候选已被采用或全量历史题集已重测。

### 自用练习与口头面试

[开发路线](docs/企业知识问答后续路线.md)保留 A—E 的实施顺序与取舍记录；当前进入学习与口述准备。E 轮 11 项生命周期检查通过、六个问答场景五通过一技术失败，详见[交付回归](docs/evaluation/runs/2026-10-21-项目交付回归.md)。收尾不等于已解决漏冲突和复核误拒。

[稳定练习环境](docs/demo/trusted-knowledge/练习环境.md)使用独立练习账号和资料库，初始化后保留正常发布状态，避免直接使用批量评测结束后已撤回资料的库。默认知识库仍是全局配置，账号并不隔离知识库。

[口头面试准备](docs/interview/可信知识问答口述准备.md)按一分钟介绍、请求流程、设计取舍、常见追问和真实失败案例组织，不依赖现场展示源码或成品。


V19 增加可配置的适用条件澄清：管理员通过 `/api/admin/knowledge-bases/{id}/clarification-policy` 配置业务主题及范围选项。缺范围时先识别主题，再由服务端返回结构化澄清；客户传 `clarificationMessageId` 和显式范围后，服务端从原消息恢复问题、绑定原知识库并重新检索及核验。通用流程无需一律问地区，旧知识库默认关闭此功能。规则、接口和限制见 `notes/30-适用条件澄清与续答.md`；演示见 `docs/demo/trusted-knowledge/README.md`，实测见 `docs/evaluation/runs/2026-10-19-澄清续答验证.md`。本轮接口/脚本交付，尚未增加前端选择卡片。

### 多范围比较与调用预算

阶段42支持 `{"content":"比较上海和北京的住宿上限","applicabilityScopes":["CN_SH","CN_BJ"]}`。旧单范围字段仍可用；新旧字段不得冲突，范围不可为空、重复、超过三项或与ALL混选。消息响应和报告增加 applicabilityScopes，多范围时旧标量为null。

先联合过滤所选地区与ALL通用资料，一次向量化、一次混合检索；缺少专属地区片段时仅对该地区有限补查，再根据整题缺口规划。各地区共享三轮、五次逻辑搜索、两次规划及证据预算；整题统一生成与复核，首次具体否决时至多修订一次，不为每个地区单独调用，不把不同地区金额差异当成同条件冲突。通用资料是否真正适用仍需语义核验，任一部分缺资料可返回不足及有据部分。

配置主题的完整名称在问题中明确出现时无需重复追问；复杂否定或不能明确识别时继续澄清。续答可通过 applicabilityScopes 多选原卡片提供的地区，仍携带 clarificationMessageId。前端多选控件不在本仓库；请求契约、验证和限制见 [阶段42记录](notes/42-多范围联合检索与澄清.md) 与 [验证资料](docs/demo/multi-scope/README.md)。V23增加消息范围JSON列表，历史消息继续兼容。

阶段43统一入口的兼容边界、验证及未解决问题见[开发记录](notes/43-统一可信问答入口.md)。旧模型实现暂保留，但会话层已移除自由回答分支和历史工具重放。
