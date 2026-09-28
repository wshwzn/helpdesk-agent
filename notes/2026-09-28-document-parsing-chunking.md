# 文档解析与分块预览

## 实现功能与业务规则

- 对象存储接口新增下载能力，本地目录和阿里云 OSS 均可返回原始文档读取流。
- 使用 Apache Tika 3.3.2 解析 `txt`、`md`、`pdf`、`docx`，统一提取为纯文本；只引入文本、PDF 和 Microsoft Office 三个必要解析模块。
- 默认最多提取 200 万字符，避免异常文档产生无限正文；空正文、损坏或无法解析的文档返回 `422 KNOWLEDGE_DOCUMENT_PARSE_FAILED`。
- 默认每块最多 1000 字符、重叠 150 字符，优先在段落、换行、中英文句末和空白处切分。
- 管理员可以分页预览实际分块。预览每次重新下载并解析，不写数据库、不修改文档 `PENDING` 状态。

## 新增类和文件

- `KnowledgeProcessingProperties`、`KnowledgeProcessingConfiguration`：管理最大提取字符数、块大小和重叠量。
- `KnowledgeDocumentParser`、`TikaKnowledgeDocumentParser`、`ParsedDocument`：统一文档解析协议及 Tika 实现。
- `KnowledgeTextChunker`、`DocumentChunk`：文本规范化和中文友好分块。
- `KnowledgeDocumentPreviewService`、`KnowledgeDocumentPreviewServiceImpl`：编排对象下载、解析、分块和预览分页。
- `DocumentChunkResponse`、`DocumentChunkPreviewResponse`：分块详情和分页预览响应。
- `KnowledgeDocumentParsingException`：封装不暴露文件内容的解析失败。
- `KnowledgeTextChunkerTest`、`TikaKnowledgeDocumentParserTest`：覆盖中文分块及四种文件格式解析。

## 修改文件

- `ObjectStorageService`、`LocalObjectStorageService`、`AliyunOssObjectStorageService`：增加 `download` 方法。
- `AdminKnowledgeBaseController`：增加文档分块预览接口。
- `GlobalExceptionHandler`：增加文档解析失败的 422 响应。
- `pom.xml`：引入 Apache Tika 核心和三个必要解析模块。
- `.env.example`、本地配置和测试配置：增加文档处理参数。
- `README.md`、OpenAPI 测试：补充分块预览用法和接口结构。

## API 与数据库变化

- 新增 `POST /api/admin/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunk-preview?page=1&size=20`。
- 本轮没有数据库结构变化，也不保存临时解析正文和分块。

## 测试与验证

- `mvn -DskipTests compile`：通过。
- 定向测试覆盖本地对象下载、管理接口预览、中文自然边界、重叠分块以及 TXT、Markdown、PDF、DOCX 解析。
- `mvn test`：全量 59 个测试通过。
- 使用真实阿里云 OSS 完成上传、下载、Markdown 解析、分块预览和删除：测试文档提取 8239 个字符并生成 11 个分块，文档状态保持 `PENDING`，临时数据已清理。

## 已知限制与后续工作

- 预览为同步、进程内解析，超大或复杂 PDF 仍可能消耗较多 CPU；正式入库阶段应放入有超时和重试的异步任务。
- 扫描版 PDF 没有文本层时不能提取正文，当前未启用 OCR。
- 分块尚未生成 Embedding，也没有写入 Elasticsearch；下一步需要增加向量模型配置和索引任务。
