# OSS 来源同步与变更识别（B3）

## 业务规则与当前进度

本轮完成 B3：接入管理员显式登记的 OSS 目录，只读同步到知识库独立快照。内部上传的 PENDING 文档不参与来源扫描。248 项 Java 测试与打包通过，真实 MySQL、OSS、Embedding、ES 联调通过，本地后端已升级到 V22。

- 首版固定使用应用已配置的阿里云 OSS Bucket，来源目录为 `external-knowledge/{小写标识}/`，不同来源不嵌套，禁止扫描内部 `knowledge-bases/` 快照。
- 文件自定义元数据明确声明来源标识、业务版本、范围与生效时间，不根据修改时间猜测制度版本。
- 全量列出目录，逐个 HEAD 检查变化；未变对象不下载、不提交新索引。变化对象条件下载并校验元数据一致性，再计算 SHA-256。
- 首次导入是待发布草稿；已有资料修改生成 B2 候选，索引复用 B1 任务，发布与交接仍须管理员审核。
- 来源身份及范围不可原地改动；同一业务版本内容改变、历史版本回退、尚有待审候选时继续更新均记录冲突。管理员可以拒绝已结束处理的草稿候选，再在来源端提供新业务版本。
- 仅完整且没有单项错误的扫描标记缺失；缺失不自动撤回，管理员使用既有撤回接口确认业务失效。重现的对象复用原映射。
- 每个来源以数据库时钟、租约和执行令牌协调多实例；所有写入先检查执行权。独立执行线程避免长扫描阻塞 B1 心跳。
- 快照上传在事务外；来源指针、候选与任务在同一事务提交。上传成功但提交失败可能留下无引用对象，不误删提交状态不明的快照。

## 新增文件职责

- `OssSourceGateway`：完整分页、HEAD、条件下载的契约。
- `OssSourceObject`：不可变头信息、变化指纹、对象键哈希；ETag 不当作 SHA-256。
- `SourceBusinessMetadata`：解析与校验五个显式 OSS 业务元数据字段，统一微秒精度。
- `KnowledgeSource` / `KnowledgeSourceItem` / `KnowledgeSourceClaim`：来源、对象映射及扫描执行权的数据记录。
- `KnowledgeSourceProperties`：扫描启用、单轮上限、租约范围校验。
- `KnowledgeSourceStore`：登记/暂停、跨库校验、数据库租约、写入 fencing、结果计数、完整扫描后的缺失标记。
- `KnowledgeSourceImportService`：预检查及事务提交、与 B1/B2 衔接、拒绝候选；不同来源争用业务身份时先锁知识库，外部 IO 不持有此锁。
- `KnowledgeSourceSyncService`：完整清单与逐对象处理编排，错误脱敏，快照上传后提交。
- `KnowledgeSourceScheduler` / `KnowledgeSourceConfiguration`：独立有界执行线程与周期触发。
- `AdminKnowledgeSourceController`：管理员来源管理与扫描结果接口。
- `V22__add_knowledge_oss_sources.sql`：来源、对象映射表与文档的来源关联。
- `KnowledgeSourceIntegrationTest`：21 项事务、并发、故障、审核、资格和接口边界测试。
- `KnowledgeSourceMigrationTest`：V21 → V22 保留旧资料及外键/唯一约束测试。
- `AliyunOssSourceGatewayTest`：6 项真实 SDK 对象协议测试，覆盖分页、异常游标、边界、条件 GET、元数据竞争与长度检查。
- `scripts/smoke_oss_source.py` / `scripts/OssSourceFixture.java`：真实 HTTP 验收及限定测试目录的 OSS SDK 文件维护器，仅从环境变量或忽略配置读凭据。
- `docs/demo/oss-source/README.md`：元数据约定、管理员接口、配置、错误处理及复现命令。

## 修改文件职责

- `AliyunOssObjectStorageService`：完整分页、HEAD、有条件且有大小限制的 GET。
- `KnowledgeUploadSupport`：外部快照与上传共用文件校验及 SHA-256。
- `KnowledgeDocument` / `KnowledgeDocumentResponse`：来源对象关联。
- 文档修改、上传新版与删除服务：外部托管资料保持单一维护入口，保留追溯关系。
- 应用配置：来源调度、数量上限、租约；测试关闭自动调度。
- `AdminKnowledgeBaseControllerIntegrationTest`：适配文档响应追加 sourceItemId；README、路线与数据库字典同步更新。

## API 与数据库

管理员接口位于 `/api/admin/knowledge-bases/{baseId}/sources`：创建、查询、暂停/恢复、手动扫描、对象分页及拒绝候选。首次发布、新版交接、索引重试、业务撤回沿用现有文档接口。

V22 新增 `knowledge_sources`、`knowledge_source_items`、`knowledge_documents.source_item_id`，含来源目录唯一约束、对象键哈希唯一约束及关联外键。字段细节同步至数据库字典。

## 验证

- `mvn -B test package dependency:build-classpath '-Dmdep.outputFile=target/stage35-classpath.txt'`：248 项测试，0 失败、0 错误、0 跳过，BUILD SUCCESS。相较 B2 新增 28 项。
- 初轮测试暴露测试夹具缺少用户/知识库、H2 生成键含默认时间字段、Mockito 事务代理存根问题，均修复；生产插入明确只读取生成的 id。完整回归最终通过，没有删去失败断言。
- 真实 MySQL 9.3 从 V20 升至 V22，V21/V22 均执行成功；没有修改已应用迁移。旧 IDE 后端已停止，当前 8080 运行本轮打包副本 `target/stage35-backend.jar`，PID 记录于忽略文件 `target/stage35-backend.pid`。
- 真实联调运行 `40ac46e1eca0`：独立知识库 19、来源 1、旧文档 230、新文档 231。首次导入、重复扫描、真实 Embedding/ES 索引、旧版发布、外部修改候选、审核前只召回旧版、审核后只召回新版、源文件删除仅标记缺失、恢复不重复索引全部通过。
- 脚本记录 15 个程序断言（其中包含重复的对象映射断言，不能当作 15 个独立业务场景）；验证的是来源同步、索引及管理员检索，不是新的问答语义准确率评测。
- 原始报告 `target/oss-source-smoke.json`，完整构建日志 `target/stage35-build.log`，联调输出 `target/stage35-smoke.log` 均在忽略目录；报告不含凭据。测试资料与快照保留，来源暂停，默认知识库未改变。
- 后端已恢复默认定时扫描配置，启用测试来源后未手动调用 scan，观察到定时扫描成功、unchangedCount=1；随后再次暂停来源。报告记录 `scheduledScanVerified=true`。当前健康检查为 UP。

## 限制与后续

- 同 Bucket、单向、轮询、文档级更新，不做双向编辑或段落级复用。文件移动视为旧路径缺失和新路径出现，不自动重绑身份。
- 不保证捕获两次轮询间已被覆盖的中间版本；OSS 分页清单不是跨页事务快照，缺失只作为人工核查提示。
- 单次默认最多 200 个对象，超限扫描失败且不标记缺失。只保留最近扫描摘要，不是完整事件审计系统。
- 快照孤儿清理尚未实现；同内容不同业务版本仍会重新索引。
- 初版手动扫描是同步 HTTP 调用，索引异步；大规模接入需要单独扩展扫描任务及分页规模。
- 多实例抢租约和过期写入隔离通过数据库集成测试验证，尚未执行两台真实应用实例的故障演练。
- B 阶段完成当前约定范围，下一轮进入 C1：冻结多跳样本及单轮检索基线，再实现受控补查。
