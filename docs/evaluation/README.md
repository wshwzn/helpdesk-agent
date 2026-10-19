# 可信问答脚本评测

本目录的固定题集检查正常问答、旧版/未来版隔离、地区隔离、缺条件、部分有据、无依据、有效资料冲突、撤回后恢复和撤回后无依据。所有资料是仓库原创的虚构制度。

## 运行方式

需要 Python 3.10+（只用标准库）、运行中的后端、可用的 MySQL/ES，以及已启用的默认 Chat 和 Embedding 模型。脚本通过真实 API 执行，不直接修改数据库或伪造身份。

在仓库根目录被 Git 忽略的 `.env` 中配置以下四个字段，也可使用同名环境变量。环境变量优先；脚本只读取这些登录字段，不读取其他密钥。

```properties
HELPDESK_ADMIN_USERNAME=<管理员账号>
HELPDESK_ADMIN_PASSWORD=<管理员密码>
HELPDESK_CUSTOMER_USERNAME=<客户账号>
HELPDESK_CUSTOMER_PASSWORD=<客户密码>
```

先针对已经导入七份资料、且冲突通知已发布的默认库运行两题：

```powershell
python -X utf8 scripts/evaluate_trusted_knowledge.py --scenario conflict --output target/knowledge-evaluation/conflict-01
```

完整运行 15 题：

```powershell
python -X utf8 scripts/evaluate_trusted_knowledge.py --isolated --output target/knowledge-evaluation/full-01
```

验证新版本的诊断覆盖时增加 `--require-diagnostics`：

```powershell
python -X utf8 scripts/evaluate_trusted_knowledge.py --isolated --require-diagnostics --output target/knowledge-evaluation/diagnostics-01
```

- `--isolated` 新建专用评测库，上传和索引七份固定文档，复用原默认库的检索参数。临时切换默认库后，依次执行 baseline → conflict → restored → withdrawn；最后恢复原默认库。默认库为全局配置，运行期间应避免同时进行其他知识对话或配置修改。
- 脚本只在自建评测库发布/撤回资料，不改变已有文档的状态。文档撤回是终态，因此下一次完整运行会创建新库，而不是把已撤回文档强行恢复为发布。
- 结束后保留评测库和独立会话作为复查依据。需要清理时按报告中的 `evaluationKnowledgeBaseId` 处理，不批量删除用户资料。
- 每题独立建会话，正常生成只调用一次。遇到 HTTP 502/网络超时记录技术失败，不重试、不删题。重跑使用不同输出目录，旧报告禁止覆盖。
- `--base-url` 指定地址；默认 `http://localhost:8080`。`--runtime-label` 记录实际被测服务版本，推荐填写 JAR 摘要；脚本记录的本地 Git 和源码摘要不能单独证明服务运行版本。
- `--auth-file` 可用于一次性调试，读取含 `admin`、`customer` JWT 的本地 JSON。令牌文件只能放在忽略目录，使用后删除；脚本不在报告保存令牌或登录密码。
- 检查资料 SHA-256、来源/版本/范围、生效区间、发布状态和默认模型后才跑题；每题前重新检查配置是否改变。历史日期不同的固定资料不再适合本题集时，脚本直接拒绝运行，不改写业务时钟。
- 正常退出或 Python 异常会尝试恢复默认库。强杀进程/断电无法保证执行 finally；重启后检查 `results.json` 的 `original.knowledgeBase.id`、`evaluationKnowledgeBaseId` 和 `restoration`，必要时通过管理端恢复。发现其他人已切换默认库时不会覆盖对方选择。

## 如何读结果

`results.json` 保存完整结构化回答、候选原文、模型名称、检索参数、文档状态、Git/题集/源码摘要、耗时和 Token。`report.md` 提供问题、正文及结果表；逐题落盘，中断后仍保留已执行项。

| 结果 | 含义 |
| --- | --- |
| CHECK_PASSED | 状态、必要来源、关键点、引用身份/逐字出处、范围和准入等自动检查通过；不代表语义全部正确 |
| CHECK_FAILED | 请求成功，但上述检查至少一项不通过，`problems` 说明原因 |
| TECHNICAL_ERROR | 生成接口、网络或后端失败；查看 HTTP 错误和历史消息中的阶段码，不计作正确拒答 |

分母明确区分计划题数、已执行题数和成功返回报告的题数。报告中的已知 Token 仅汇总成功消息已提供的回答模型用量，失败调用、Embedding 消耗和未返回用量均不算作零费用。耗时是每题建会话加一次回答请求的端到端耗时，不含导入/索引和场景切换。

## 管理员运行诊断

V18 起，新知识助手消息的 `knowledgeRunId` 在成功和失败时都会保留。脚本从成功响应或失败消息历史取得编号，再使用管理员身份读取 `GET /api/admin/knowledge-answer-runs/{runId}`。逐题结果保存 `diagnosticState` 与可用的 `diagnostic`，获取时间不计入问答耗时。

| 诊断状态 | 含义 |
| --- | --- |
| CAPTURED | 运行、助手消息、会话、问题与范围一致，快照状态与已终结消息一致 |
| NO_RUN_ID | 旧版本或未取得对应助手消息，没有运行编号 |
| UNAVAILABLE | 编号存在但接口不可用，如 404、403、网络失败 |
| INVALID | 编号或诊断身份字段不符合预期 |
| INCOMPLETE | 消息已经结束，但诊断快照仍未达到相应终态 |

诊断采集失败不会把原问答从成功改为失败，也不会把失败改为通过。`--require-diagnostics` 只增加退出码条件。原来按成功消息计算的 Token 指标保留；新增 `diagnosticKnownAnswerTokens` 从已获取的完整诊断累计 DRAFT/REVIEW 阶段用量，可能包含失败调用。它与成功消息 Token 有重叠，**不能相加**；未知调用和 Embedding 仍不在已知总量内，`diagnosticModelStagesMissingUsage` 单列缺失用量的模型阶段。

诊断中的 `draft` 可能是错的，`review` 也是模型判断。候选、逐字引文和结论需一起对照，不能拿未核验草案当答案。普通客户和客服不能查询此接口；管理员列表 `GET /api/admin/knowledge-answer-runs` 只返回摘要，支持 page/size、conversationId 和 status 筛选。原始 `results.json` 现在可能包含失败草案与知识原文，提交公开报告前只保留虚构测试资料并检查凭据字段。

诊断采用独立短事务。写入失败时业务仍继续，后续阶段尝试保存累计快照；进程中断或最后写入失败可能留下 RUNNING，不能单凭它断言模型仍在调用。详细设计见 `notes/27-问答运行记录与失败诊断.md`。

首轮必须逐题对照原文检查语义，至少确认：每个断言是否被引用支持、条件是否扩大、数字及否定是否误读、是否遗漏相反证据、是否无故拒答。自动状态匹配率、逐字引用检查通过率与人工语义准确率是不同指标。由开发助手检查时应明确标注为“助手审阅”，不能标成人类金标准。

## 失败阶段

接口仍返回脱敏的 `502 CHAT_GENERATION_FAILED`，SSE 仍发送 `error`。消息历史的 `errorCode` 现在可以进一步定位：

| 错误码 | 阶段 |
| --- | --- |
| KNOWLEDGE_RETRIEVAL_FAILED | 默认库读取、Embedding 或 ES 检索阶段失败 |
| KNOWLEDGE_DRAFT_CALL_FAILED | 生成草案的模型调用失败 |
| KNOWLEDGE_REVIEW_CALL_FAILED | 语义复核的模型调用失败 |
| KNOWLEDGE_PROTOCOL_INVALID | 模型未按结构化 JSON 协议返回 |
| KNOWLEDGE_STRUCTURE_INVALID | 状态与列表等结构约束不满足 |
| KNOWLEDGE_CITATION_INVALID | 引用编号或逐字引文不合法 |
| KNOWLEDGE_REVIEW_REJECTED | 模型复核否决草案 |
| KNOWLEDGE_REVIEW_UNSUPPORTED_CLAIM | 复核模型认为存在无依据断言 |
| KNOWLEDGE_REVIEW_WRONG_STATUS | 复核模型认为业务状态选择错误 |
| KNOWLEDGE_REVIEW_MISSED_CONFLICT | 复核模型认为遗漏冲突 |
| KNOWLEDGE_REVIEW_INCOMPLETE_ANSWER | 复核模型认为遗漏了本可回答的重要部分 |
| KNOWLEDGE_REVIEW_UNNECESSARY_CLARIFICATION | 复核模型认为没有必要追问 |
| KNOWLEDGE_REVIEW_UNCERTAIN | 复核模型无法确认 |
| KNOWLEDGE_EVIDENCE_CHANGED | 核验时发现证据撤回、过期或业务元数据变化 |
| KNOWLEDGE_EVIDENCE_CHECK_FAILED | 证据快照缺失或准入复查异常 |
| KNOWLEDGE_CONTEXT_TOO_LARGE | 证据/模型输入预算超限 |
| KNOWLEDGE_ANSWER_FAILED | 未被上述分类覆盖的知识链路错误，或历史旧版本的通用错误 |

最终默认复核提示保持原来的布尔协议，通常以 `KNOWLEDGE_REVIEW_REJECTED` 表示复核否决。上述原因解析能力保留用于兼容实验结果，默认提示并不要求原因，不能保证每次都有细分原因。要求模型输出原因的提示实验未采用，具体结果见实测报告。

分类码不包含草案、模型响应和敏感配置；不会把技术异常转换成“证据不足”的正常业务答案。复核细分原因也是模型判断，不自动等同于人工确认的事实。通过/原因互相矛盾或原因不在枚举内时归为协议错误；仅返回旧布尔协议的否决保留通用 REJECTED。首轮旧版本只有通用错误码的失败，不能在事后猜测为某个精确阶段。

## 本地工具测试

```powershell
python -X utf8 -m unittest discover -s scripts -p 'test_*.py' -v
mvn -B test
```

Python 测试检查评测判分的边界；Java 测试检查服务端约束。两者都不等于真实模型得分。真实运行报告单独保存，固定题集属于开发回归集，不能当作未见过问题的泛化评测。

## 本次实测归档

[2026-10-19 实测报告](runs/2026-10-19-可信问答实测报告.md)记录原版本、未采用的提示实验、定向诊断和最终代码的真实结果；[逐题数据](runs/2026-10-19-results.json)保留答案、候选原文和失败。不能只取其中最好的一轮作为项目准确率。

[运行诊断验证](runs/2026-10-19-运行诊断验证.md)记录 V18 后的完整 15 题及管理员诊断覆盖情况；[对应数据](runs/2026-10-19-运行诊断数据.json)包含被拒绝的草案、复核结果和阶段用量。
