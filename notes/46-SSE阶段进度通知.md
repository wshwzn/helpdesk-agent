# SSE 阶段进度通知

## 行为与业务规则

问答的 SSE 事件顺序为 `start → progress* → delta → done`，失败时为 `start → progress* → error`。每次实际进入阶段就通知客户端，未执行的阶段不伪造。补查、修订后再次核验允许同一阶段重复；不提供完成百分比或预计剩余时间。

进度来自本次请求的 `KnowledgeAnswerTrace.stage`，不是额外轮询或模型调用。只发送固定阶段和固定文案，不传出草案、证据正文、复核意见或内部异常。最终正文仍在核验和落库完成后交付。同步入口使用空监听器，不增加通知。

取消连接会取消调度任务；即使模型忽略中断并迟到返回，也不能覆盖已取消的消息或继续交付。仍不能保证供应商停止执行或计费。

## 文件职责与修改

- `KnowledgeAnswerTrace`：增加请求级阶段监听，在实际阶段操作前通知。
- `ConversationServiceImpl`：使用 SSE 发送器与后台调度任务连接阶段通知和最终交付，取消后丢弃迟到异常。
- `ChatStreamEvent`：增加 `progress` 工厂和 `stage` 字段，集中维护九个阶段的安全提示。
- `ConversationController`、`README.md`：更新接口说明。
- `TrustedKnowledgeConversationIntegrationTest`：增加实时到达、修订重复阶段、失败不泄露正文测试；HTTP SSE 用例检查进度帧。
- `verify_unified_knowledge_entry.py` 及测试：评测保留进度事件，但不再把事件总数固定为三个；仍核对 delta 和 done 正文一致。
- 客户端 `helpdesk-agent-web` 独立仓库同步接入；仅等待中的匹配消息显示当前阶段，完成、失败、断流和停止等待后清除。

## API 与数据库

现有 `POST /api/conversations/{id}/messages/stream` 新增事件，无新增端点或数据库变更。示例：

```text
event:progress
data:{"type":"progress","assistantMessageId":42,"stage":"REVIEW","message":"正在核验答案与资料是否一致"}
```

其他无关字段可能为 null。阶段值为 `APPLICABILITY`、`RETRIEVAL`、`PLANNING`、`DRAFT`、`VALIDATION`、`REVIEW`、`REVISION`、`EVIDENCE_RECHECK`、`MESSAGE_PERSISTENCE`。旧客户端可忽略未知事件，新增 `stage` 对其他事件为空；最终消息和历史接口保持原契约。

## 验证

- 后端针对会话、问答编排与运行诊断的 66 项测试通过。模型桩故意阻塞，验证正文未生成时已收到阶段；失败只返回安全错误，取消后迟到响应不能覆盖终态。额外覆盖“保存阶段通知中同步取消”的回调重入边界，确认不会继续落库。
- 前端 18 项测试通过，类型检查与生产构建通过；覆盖 SSE 分片、进度不是终态、修订重复阶段、消息身份匹配、完成后迟到事件、停止等待和断流清理。
- Python 统一入口脚本 6 项测试通过，覆盖进度事件兼容与最终正文一致性检查。
- 未新增真实模型调用或修改线上配置；本轮通过确定性测试验证通知与状态边界，没有开展浏览器人工验收。

## 限制

仅显示当前阶段，不做逐字输出、历史进度回放或重连续传；长时间单次模型调用期间不会定时产生新的阶段事件。阶段通知不会缩短模型推理耗时，也不会改变拒答或复核规则。需重启本地后端加载代码，前端更新后才能显示提示。
