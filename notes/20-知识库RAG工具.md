# 知识库 RAG 工具

## 实现功能与业务规则

- 管理员可以把一个知识库设为唯一默认项，客户对话只检索该知识库。
- Agent 新增 `search_knowledge_base` 工具，只向模型暴露自然语言查询文本；客户身份、对话身份和知识库 ID 均由后端控制。
- 通用产品说明、使用方法、故障排查和服务规则需要先查询知识库；个人工单信息仍使用工单工具。
- 工具复用现有 BM25、KNN 与 RRF 检索服务，返回精简的来源文件、Chunk 序号和正文。
- 未配置默认知识库或没有命中时返回明确状态，要求模型说明未找到可靠资料，不编造答案。
- 普通与流式对话共享同一组工具回调，调用参数和结果继续保存到 `agent_tool_calls`。

## 新增类和文件

- `KnowledgeSearchTools`：选择后端默认知识库并执行知识检索工具。
- `KnowledgeSearchToolResult`：描述已命中、未命中、未配置或无效查询状态。
- `KnowledgeSearchToolChunk`：向模型提供精简的来源和 Chunk 正文。
- `KnowledgeSearchToolsTest`：验证工具参数隔离、默认知识库选择和未配置状态。
- `V13__add_default_knowledge_base.sql`：增加默认知识库标记和查询索引。

## 修改类和文件

- `KnowledgeBase`、`KnowledgeBaseResponse`：增加 `isDefault` 字段。
- `KnowledgeBaseService`、`KnowledgeBaseServiceImpl`：增加默认知识库查询和切换能力。
- `AdminKnowledgeBaseController`：增加设为默认接口。
- `SpringAiConversationModelClient`：注册知识检索工具并补充 RAG 行为约束。
- 管理端接口测试、模型客户端测试、OpenAPI、README 和数据库文档同步更新。

## API 与数据库变化

- 新增 `POST /api/admin/knowledge-bases/{id}/default`。
- `KnowledgeBaseResponse` 新增布尔字段 `isDefault`。
- `knowledge_bases` 新增 `is_default BOOLEAN NOT NULL DEFAULT FALSE`。
- 新增索引 `idx_knowledge_bases_default_updated (is_default, updated_at)`。

## 测试与验证

- 单元测试覆盖知识工具 Schema、默认知识库检索和未配置状态。
- 集成测试覆盖默认知识库切换、Flyway V13 和 OpenAPI 文档。
- 模型客户端测试确认同步和流式调用使用的回调集合包含知识检索工具。

## 已知限制与后续工作

- 当前系统只支持一个全局默认知识库，后续增加 Agent 配置后可改为 Agent 与多个知识库绑定。
- 客户端暂未把工具来源渲染为独立引用卡片，本轮回答中的来源仍由模型以文本形式给出。
- 设置默认知识库前不强制要求存在已索引文档，空知识库会得到未命中结果。
