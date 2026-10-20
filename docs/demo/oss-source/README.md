# OSS 外部来源同步验收

这条流程用于外部系统维护文件、知识库只读接入的场景。普通上传和“保存并提交”不需要来源扫描，未提交的内部草稿不会被扫描任务接管。

## 来源约定

首版使用应用当前配置的阿里云 OSS Bucket。为每个来源分配独立目录，例如 `external-knowledge/hr-policy/`；固定前缀结构防止目录嵌套和扫描内部快照。目录必须由外部维护者维护，知识库不会回写源文件。

每个文档在上传 OSS 时设置以下 **用户自定义元数据**。SDK Map 使用右列键名，HTTP 头需要加 `x-oss-meta-`；值均为 ASCII。正文支持 txt/md/pdf/docx，最大 20 MiB。

| 含义 | SDK 元数据键 | 示例 |
| --- | --- | --- |
| 稳定制度来源标识 | kb-source-key | travel-policy |
| 本次业务版本 | kb-business-version | v1 |
| 适用范围 | kb-scope | CN_SH |
| 生效时间（Asia/Shanghai） | kb-valid-from | 2026-01-01T00:00:00 |
| 失效时间（可省略） | kb-valid-until | 2027-01-01T00:00:00 |

不要把 ETag、修改时间或索引构建令牌当成业务版本。内容或业务规则改变时提供新业务版本；对同一路径修改来源标识或范围会报冲突。对象重命名不是版本更新，会按新路径处理，复用已有制度身份将被拒绝，需要人工整理来源。

## 管理接口

所有接口需要 ADMIN Bearer Token。以下路径相对于 `/api/admin/knowledge-bases/{baseId}`。

| 请求 | 用途 |
| --- | --- |
| POST /sources，`{"objectPrefix":"external-knowledge/hr-policy/"}` | 登记来源，201；Bucket 来自服务配置 |
| GET /sources | 查看所有来源和最近扫描报告 |
| GET /sources/{sourceId} | 查看单个来源 |
| PUT /sources/{sourceId}/enabled，`{"enabled":false}` | 暂停并作废活动租约；true 恢复 |
| POST /sources/{sourceId}/scan | 同步完成一次扫描，返回结果；索引仍异步 |
| GET /sources/{sourceId}/items?page=1&size=20 | 查看对象、最近文档、错误或缺失状态 |
| POST /sources/{sourceId}/items/{itemId}/reject-candidate，`{"version":当前文档版本}` | 拒绝已结束处理的未发布草稿，204 |

扫描报告 `addedCount/updatedCount/unchangedCount/failedCount` 为本轮计数；`missingCount` 是已标记缺失的总数。`SUCCEEDED` 表示扫描成功，**不等于索引完成或审核发布**。具体文档通过 `items[].lastDocumentId` 查询；`lastDocumentId` 也不一定是当前在用版本。

首次导入的草稿索引 READY 后，通过原 `documents/{id}/publish` 发布。后续变化产生候选，使用原 `approve-replacement` 同时提交候选与旧版的乐观锁版本审核。索引失败使用原 `index` 接口重试，扫描未变文件不会悄悄重开失败任务。被拒绝或已撤回版本不会因为重新扫描而恢复发布；须在来源端提供新的业务版本。

有待审候选时不连续覆盖；维护者应审核或拒绝后再处理下一版。已有未来交接安排时，先处理该安排，再接收其他业务版本。缺失仅用于核查，确认业务失效后管理员显式撤回对应已发布文档；如存在未来候选，沿用 B2 先取消未来安排的规则。

## 调度及失败处理

- 默认每 60 秒尝试扫描显式启用的来源，每实例一个专用线程；数据库租约协调多实例。没有登记来源时不产生 OSS 扫描。
- `KNOWLEDGE_SOURCES_ENABLED=false` 仅关闭**定时**调度，管理员仍可手动扫描；来源自身 enabled=false 会同时禁止手动与定时扫描。
- `KNOWLEDGE_SOURCE_SCAN_DELAY_MS` 默认 60000；`KNOWLEDGE_SOURCE_MAX_OBJECTS` 默认 200（允许 1–1000，包括目录占位）；`KNOWLEDGE_SOURCE_LEASE_SECONDS` 默认 300（允许 30–3600）。
- 完整分页成功后逐项 HEAD。未变不下载；变化时条件 GET，核对响应元数据和字节数，然后 SHA-256 去重。快照保存于内部独立路径。
- `PARTIAL` / `SOURCE_ITEM_ERRORS`：查看对象错误码。`SOURCE_METADATA_OR_FILE_INVALID` 检查格式、元数据；`SOURCE_VERSION_CONFLICT` 检查身份、版本标签、待审及未来交接；`SOURCE_OBJECT_FAILED` 检查存储、权限和读取竞争。
- 清单失败、超限、Bucket 配置漂移使整轮失败，不推断缺失；有任何单项错误时也保守地不新增缺失标记。旧 MISSING 提示可能保留，应结合最近扫描状态查看。
- 扫描重试是安全的，但不是 OSS 全目录的事务快照，也不保证捕获轮询之间的所有中间版本。缺失提示需要人工确认。

## 自动执行真实联调

先升级后端并执行到 V22。运行期间启用 B1 索引调度，关闭来源定时调度，避免与脚本主动扫描竞争。不要同时运行不理解版本交接的旧后端实例。脚本会创建独立知识库、独立 OSS 测试目录，保留结果并在退出时暂停该来源；不会修改默认知识库。

```powershell
mvn -B test package dependency:build-classpath '-Dmdep.outputFile=target/stage35-classpath.txt'
python scripts/smoke_oss_source.py --base-url http://localhost:8080
```

本地 `.env` 配置 OSS 四个字段；管理员凭据通过 `HELPDESK_ADMIN_USERNAME` / `HELPDESK_ADMIN_PASSWORD` 环境变量或忽略的 `.env` 提供，不能写入脚本。脚本只依赖 Python 标准库和项目已经依赖的 Java OSS SDK。

验证顺序：首次导入 → 重复扫描 → 自动索引 → 发布并检索旧版 → OSS 修改 → 候选索引 → 审核前仍检索旧版 → 审核后只检索新版 → 源文件删除标记缺失但仍可检索快照 → 文件恢复仍复用已有文档。默认报告为忽略目录 `target/oss-source-smoke.json`。

## 已知边界

仅同 Bucket 单向轮询，无外部事件订阅、双向编辑、跨 Bucket 凭据管理、段落级向量复用或自动缺失撤回。OSS 快照上传成功而数据库提交失败，可能产生未引用对象，暂未实现孤儿清理。来源和历史导入文档保留供追溯，暂不提供解除托管/删除来源 API。
