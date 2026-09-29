# RAG 检索评测

## 实现功能与规则

- 管理员可以为知识库维护固定测试问题，每题支持选择 1～20 个 `READY` 期望文档。
- 单次最多运行 100 条用例，并固定使用运行开始时的候选数量、相似度下限和最终结果数量。
- 逐题计算是否命中、文档级 Recall@K、首个相关 Chunk 名次、倒数排名和耗时。
- 汇总计算 Hit@K、平均 Recall@K、MRR 和平均耗时；同一文档的多个 Chunk 只计一次文档命中。
- 运行记录保存问题、期望文档和实际召回 Chunk 快照，可以比较不同检索参数的历史效果。
- 管理员可以删除指定历史运行；运行汇总与逐题快照在同一事务内删除，评测用例保持不变。
- 被当前评测用例引用的文档禁止删除；删除用例不影响已有运行快照。

## 新增文件与职责

- `V14__create_rag_evaluation_tables.sql`：创建评测用例、期望文档关系、运行汇总和逐题结果表。
- `knowledge/evaluation/domain`：四张评测表的 MyBatis-Plus 实体。
- `knowledge/evaluation/mapper`：评测数据访问接口。
- `knowledge/evaluation/dto`：用例请求、期望文档、运行汇总和逐题结果结构。
- `RagEvaluationPersistenceService`：用短事务维护用例、批量关系、运行快照及运行删除。
- `RagEvaluationService`：在数据库事务外串行执行检索并计算指标，转发历史运行删除操作。
- `AdminRagEvaluationController`：提供管理员评测用例、运行历史查询和删除接口。

## 修改文件与职责

- `KnowledgeRetrievalService`：增加使用固定检索参数快照的重载方法。
- `KnowledgeDocumentServiceImpl`：删除文档前检查评测用例引用。
- `GlobalExceptionHandler`：增加评测未找到和冲突错误响应。
- `AdminKnowledgeBaseControllerIntegrationTest`：覆盖多期望文档、指标计算、历史读取、跨知识库删除保护和运行删除。
- `README.md`、`docs/database-schema.md`：补充接口、指标口径和四张表字段说明。

## API 与数据库影响

- 新增 `/api/admin/knowledge-bases/{id}/evaluation` 下的用例 CRUD、运行、历史查询及运行删除接口。
- `DELETE /api/admin/knowledge-bases/{id}/evaluation/runs/{runId}` 返回 `204`，同时删除该次汇总与逐题快照。
- 新增 `rag_evaluation_cases`、`rag_evaluation_case_documents`、`rag_evaluation_runs`、`rag_evaluation_run_results` 四张表。

## 验证

- `mvn -Dtest=AdminKnowledgeBaseControllerIntegrationTest test`：8 项测试通过。
- `mvn test`：77 项测试通过。

## 已知限制

- 当前按文档标注相关性，不支持期望关键词、指定 Chunk 或多级相关度。
- 批量评测为同步串行执行，最多 100 条；大规模评测后续可改为异步任务。
- 当前只评估检索质量，不调用聊天模型判断最终回答正确性。
