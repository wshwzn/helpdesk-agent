# 可信问答演示资料

所有文件均为本项目原创的虚构资料，可公开演示。当前已实现业务准入、结构化回答与核验链路；以下期望是验收条件，不是已经测得的模型回答成绩。

## 导入步骤

1. 创建独立知识库；只上传下表的六份 Markdown，不上传本说明。
2. 按表调用文档 `metadata` PUT 接口，每次提交文档详情返回的最新 `version`。
3. 调用 `index`，等待 `READY`；前五份资料再调用 `publish`。第六份保持 `DRAFT`。
4. 管理端检索可指定 `applicabilityScope`。对话演示前将这个库设置为默认库；消息请求使用 `mode=KNOWLEDGE`，并在 `applicabilityScope` 字段明确提供范围代码，不能只写在问题正文里。

| 文件 | sourceKey | businessVersion | applicabilityScope | validFrom | validUntil | 发布 |
| --- | --- | --- | --- | --- | --- | --- |
| 01-通用报销流程.md | expense-process | v1 | ALL | 2026-01-01T00:00:00 | null | 是 |
| 02-上海住宿旧版.md | travel-policy | v1 | CN_SH | 2026-01-01T00:00:00 | 2026-10-01T00:00:00 | 是 |
| 03-上海住宿新版.md | travel-policy | v2 | CN_SH | 2026-10-01T00:00:00 | 2027-01-01T00:00:00 | 是 |
| 04-上海住宿未来版.md | travel-policy | v3 | CN_SH | 2027-01-01T00:00:00 | null | 是 |
| 05-北京住宿标准.md | travel-policy | v1 | CN_BJ | 2026-01-01T00:00:00 | null | 是 |
| 06-上海住宿未审核草稿.md | travel-draft | v1 | CN_SH | 2026-01-01T00:00:00 | null | 否 |

同一来源的不同范围可共享业务版本标签，但同库、同来源、同范围、同版本不可重复。版本标签没有自动比较大小的语义。

## 固定问题与期望

以下手工演示以 **2026-10-18、Asia/Shanghai** 为参考时刻。正式查询使用服务器业务时钟，不接受模型指定历史时间；其他日期演示需按实际日期理解有效期。自动测试使用固定时间，避免测试随日期失效。

| 用例 | 问题及范围 | 应参与的证据 / 期望行为 |
| --- | --- | --- |
| 正常问答 | 差旅结束多久内提交报销？省略范围 | 01：十五个自然日；返回真实文档 ID |
| 新旧规则 | 住宿上限是多少？CN_SH | 03：600 元；不能引用 02 的 500 元 |
| 未来版本 | 当前住宿上限是多少？CN_SH | 04 不进入召回，不能使用 700 元 |
| 范围隔离 | 住宿上限是多少？CN_BJ | 05：450 元；不能使用上海标准 |
| 草稿排除 | 住宿能报销 999 元吗？CN_SH | 06 不进入召回 |
| 缺少范围 | 住宿上限是多少？省略范围 | 只允许通用资料；命中 01 时应请求补充地区，不能猜金额。无命中则返回 NO_MATCH，不调用回答模型 |
| 撤回 | 将 03 撤回后再查上海住宿上限 | 03 不再进入新检索；历史回答保留旧快照，不能据此回答当前规则 |
| 时间边界 | 在 2026-10-01T00:00:00 判断有效版本 | 02 失效，03 生效；由固定时钟自动测试覆盖 |

前五个检索用例能否命中仍受 Embedding、分块和检索参数影响；当前测试证明准入过滤和接口行为，不声称真实模型已达到某项回答准确率。现有 RAG 评测 API 没有范围字段，仅适合通用资料；专项答案评测会在后续扩展。

## 第二阶段：结构化回答与冲突演示

先仅使用前面的六份资料演示正常问题，再单独导入 `conflict/07-上海住宿冲突通知.md`。这样可区分正常回答和冲突拒绝裁决，不把同一测试库的不同状态混作准确率成绩。

冲突资料配置：`sourceKey=travel-notice`、`businessVersion=v1`、`applicabilityScope=CN_SH`、`validFrom=2026-10-01T00:00:00`、`validUntil=2027-01-01T00:00:00`，然后索引并发布。与 03 同时生效，分别给出 650 元和 600 元；上传顺序不构成优先级。

```json
{
  "content": "上海地区普通员工国内出差，当前住宿报销上限是多少？",
  "mode": "KNOWLEDGE",
  "applicabilityScope": "CN_SH"
}
```

| 场景 | 条件 / 问题 | 需检查的结构化结果 |
| --- | --- | --- |
| 正常回答 | 导入冲突资料前，问上海住宿上限 | `answer.status=ANSWERABLE`，600 元，引用 03 的实际文档 ID 和逐字引文 |
| 有效资料冲突 | 导入并发布 07 后，问上述完整问题 | 03 和 07 均进入候选时应为 `CONFLICT`，列出 600/650 双方，不默认“更新通知覆盖旧制度” |
| 缺少条件 | 不指定范围，询问住宿上限，检索命中通用流程 | `NEEDS_CLARIFICATION`，问题要求明确地区，不声称某个地区金额 |
| 部分有据 | 问“多久提交报销，以及飞机延误补贴是多少？” | `INSUFFICIENT_EVIDENCE`，可回答十五个自然日，不能编造补贴 |
| 没有依据 | 问资料未涉及的停车月卡额度 | 无命中则 `reason=NO_MATCH`；有无关候选时由模型返回并复核 `INSUFFICIENT_EVIDENCE` |
| 核验失败 | 引用伪造、复核否决或模型协议异常 | HTTP 502 / SSE error，消息 FAILED；不能算作正确拒答 |

同时检查 `knowledgeAnswer.evidence` 是否包含预期候选、`claims/conflicts` 的 references 是否指向实际文档，以及历史回读是否保留原版本。若只召回一份冲突资料，应记录为检索覆盖不足，不称为“没有冲突”。`REVIEWED` 不是人工认证，仍需对照原文检查最终判断。

演示冲突结束后撤回 07，再验证新问题恢复正常回答；旧冲突消息应保留当时证据。真实运行需保存模型、检索配置和输出，本说明没有预填实际成绩。

## 脚本批量评测

已提供 `scripts/evaluate_trusted_knowledge.py` 和 15 道固定题集。无需逐题复制接口请求；`--isolated` 会在新评测库导入这些资料并执行场景切换，最后恢复原默认库，不改动你手工导入文档的状态。登录配置、命令和判分边界见 `docs/evaluation/README.md`。

## 本轮边界

- 评测脚本已支持导入这组固定资料，仍未实现通用来源同步、范围目录、自然语言地区到代码的可靠映射或前端发布页面。
- 已发布元数据不可修改；旧版未预设截止时间时，需要管理员在切换时明确撤回，当前没有自动替代关系。
- 有效版本存在重叠时保留双方；知识模式对本轮召回资料做冲突复核，但未实现全库冲突扫描，也没有真实模型准确率结论。
- 尚未实现增量扫描、索引批次原子切换、失败保留上一批次及更新任务持久化。


## 适用范围澄清与续答

服务升级到 V19 后，先用 ADMIN 查询 `GET /api/admin/knowledge-bases/{id}/clarification-policy`，以返回的 revision 为准，PUT 同一路径。示例配置见本目录 `clarification-policy.json`：住宿标准需要地区，通用流程不需要。初次配置 revision=0；再次修改必须用当前修订号。

1. 客户发 `POST /api/conversations/{id}/messages`：

```json
{"content":"普通员工因公出差，住宿报销上限是多少？","mode":"KNOWLEDGE","applicabilityScope":"ALL"}
```

2. 服务返回 `assistantMessage.knowledgeAnswer.reason=APPLICABILITY_REQUIRED`、`answer.status=NEEDS_CLARIFICATION` 和 `clarification.scopeOptions`。保存该助手消息 ID。此时没有引用或制度答案。
3. 在同一会话选择上海（将 123 替换为刚返回的助手消息 ID）：

```json
{"content":"我选择上海","mode":"KNOWLEDGE","applicabilityScope":"CN_SH","clarificationMessageId":123}
```

后端恢复原问题与原知识库，重新检索当前有效资料；通过引用校验和复核后，报告的 `continuationOfMessageId` 关联原澄清消息。历史澄清保持不变。SSE 接口接受相同参数。

通用“差旅结束多久内提交报销”不应追问地区。配置只定义条件，不承诺所选地区存在资料；缺资料、冲突及技术失败仍分别处理。新问题不自动继承地区；续答正文只作为用户选择的说明，变更问题请另发不带 clarificationMessageId 的请求。

非法范围/ALL 返回 400；跨会话、已使用或过期卡片返回 409；配置修改后需重新提问。续答失败也需重新发送原问题，不能复用已经不是最新消息的澄清。

自动验证：`python -X utf8 scripts/evaluate_knowledge_clarification.py --base-url http://localhost:8080 --output target/knowledge-clarification/run-01 --runtime-label <实际构建标识>`。使用与原评测相同的四个 HELPDESK_* 环境变量或忽略的本地 auth-file，不能把凭据写入版本库。脚本新建资料库、启用示例配置，跑固定回归及两个地区续答，最后恢复原默认库；失败不自动重跑。
