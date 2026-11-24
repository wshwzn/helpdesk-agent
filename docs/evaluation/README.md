# 可信问答脚本评测

单次检索与受控补查的小规模对照见 [企业跨文档关联问答](bridge-qa/README.md)：20条自建关联题、50份虚构资料；首次运行因文本模型余额不足未获得有效答案，暂不提供效果提升数字。

公开制度的 80 题生成核验对照见 [问答质量评测](public-campus/ANSWERS.md)：固定真实检索结果、共用初稿，比较增加语义复核和修订前后的正确交付、引用支持及拒答。结果为同模型辅助评分，首轮未显示正确交付收益，保留逐题失败与评分局限。

公开真实资料的独立检索对照见 [高校制度检索基准](public-campus/README.md)：23 份固定公开资料、100 条 AI 辅助标注问题，比较 BM25、向量和 RRF；与下方虚构制度问答回归分别统计。标签尚未独立人工复核，不作为正式人工金标准。

最新交付回归见 [E 轮报告](runs/2026-10-21-项目交付回归.md)：11 项资料生命周期断言通过；六个问答场景五通过、一技术失败，含澄清共七次消息请求。325 项 Java、52 项 Python 检查通过。当前基线仍是有界规划 + BASELINE 审核，候选 task-planning 关闭；本轮不宣称已修复历史漏冲突与复核误拒。历史各轮资料保留原口径，不能跨模型合并成绩。

下述早期固定题集检查正常问答、旧版/未来版隔离、地区隔离、缺条件、部分有据、无依据、有效资料冲突、撤回后恢复和撤回后无依据，其资料是仓库原创的虚构制度；公开制度实验单列在 `public-campus/`。

## 多跳开发基线（C1）

独立题集 [`multihop-cases.json`](multihop-cases.json) 固定 18 份资料和 12 题，检查计划→编码→等级→地区金额等依赖关系。使用 `scripts/evaluate_knowledge_multihop.py` 创建独立库，分别记录单轮召回覆盖、答案检查、技术失败和金标准查询探针；不会把人工指定探针计为自动多跳。运行方法见 [资料说明](../demo/multihop/README.md)，首次真实结果见 [单轮基线报告](runs/2026-10-20-多跳单轮基线.md)。后续多跳版本必须使用相同题集对照，C1 本身尚未增加自动补查。

## 运行方式

阶段45增加真实项目资料回放与在线验证，默认 GROUNDED 复核在首次具体否决后允许修订一次。冻结回放使用[真实现场题集](knowledge-recovery-cases.json)，保留原始50条召回，不改检索配置；与 BASELINE 各固定两次生成/复核，另外检查真实资料错误断言和既有语义反例。`DELIVERED_REQUIRES_MANUAL_CHECK` 只表示回放通过编排，必须人工阅读，不能直接算正确率。

```powershell
python -X utf8 scripts/evaluate_knowledge_recovery.py --output target/knowledge-recovery/run-01
python -X utf8 scripts/verify_real_knowledge_answers.py --base-url http://localhost:8081 --output target/knowledge-recovery/live-01 --runtime-jar target/stage45-backend.jar
```

第一条使用本地模型配置连接真实模型、冻结检索与资格依赖，不启动 Web、不改资料。第二条要求指定端口已运行相应 JAR，以当前默认“项目开发记录”执行四题真实 HTTP；读取下述登录环境变量，每题独立会话只请求一次，前后检查资料、模型及检索配置未变。默认库不匹配时停止，不自动切库或改参数。输出目录禁止覆盖；原始输入、尝试、失败、用量与摘要保留。线上验证与冻结回放的检索数量不同必须单列，不合并计算效果。

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

诊断版本 2 新增 `citationCatalog` 和 `selection`：前者记录本轮段落目录及原文偏移，后者记录模型实际选择。`draft` 是后端提取原文、完成引用与结构校验后的草案，仍可能在语义上错误；解析失败时看 `selection`，此时 `draft` 为空。版本 1 的历史诊断保留旧草案，新增字段为空。`review` 也是模型判断。候选、逐字引文和结论需一起对照，不能拿未核验草案当答案。普通客户和客服不能查询此接口；管理员列表 `GET /api/admin/knowledge-answer-runs` 只返回摘要，支持 page/size、conversationId 和 status 筛选。原始 `results.json` 现在可能包含失败草案与知识原文，提交公开报告前只保留虚构测试资料并检查凭据字段。

诊断采用独立短事务。写入失败时业务仍继续，后续阶段尝试保存累计快照；进程中断或最后写入失败可能留下 RUNNING，不能单凭它断言模型仍在调用。详细设计见 `notes/27-问答运行记录与失败诊断.md`。

首轮必须逐题对照原文检查语义，至少确认：每个断言是否被引用支持、条件是否扩大、数字及否定是否误读、是否遗漏相反证据、是否无故拒答。自动状态匹配率、逐字引用检查通过率与人工语义准确率是不同指标。由开发助手检查时应明确标注为“助手审阅”，不能标成人类金标准。

## 失败阶段

接口仍返回脱敏的 `502 CHAT_GENERATION_FAILED`，SSE 仍发送 `error`。消息历史的 `errorCode` 现在可以进一步定位：

| 错误码 | 阶段 |
| --- | --- |
| KNOWLEDGE_RETRIEVAL_FAILED | 默认库读取、Embedding 或 ES 检索阶段失败 |
| KNOWLEDGE_PLANNING_CALL_FAILED | 补查规划的模型依赖调用失败；无成功降级 |
| KNOWLEDGE_DRAFT_CALL_FAILED | 生成草案的模型调用失败 |
| KNOWLEDGE_REVIEW_CALL_FAILED | 语义复核的模型调用失败 |
| KNOWLEDGE_PROTOCOL_INVALID | 模型未按结构化 JSON 协议返回 |
| KNOWLEDGE_STRUCTURE_INVALID | 状态与列表等结构约束不满足 |
| KNOWLEDGE_CITATION_INVALID | 目录身份不匹配、片段不属于对应证据、片段不可选，或逐字出处不合法 |
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

[片段引用验证](runs/2026-10-19-片段引用验证.md)记录模型选择段落、后端提取原文后的完整 15 题：引用解析全部通过，但整体自动通过率为 10/15，低于前一轮；[对应数据](runs/2026-10-19-片段引用数据.json)保留全部选择、目录、解析后草案和失败诊断。

## 固定草案复核专项评测

[专项评测说明](knowledge-review.md)提供 12 组固定正反草案、BASELINE/CHECKLIST 两轮配对比较，以及误杀、漏检和技术失败的独立统计。专项脚本不会重新检索或生成草案；普通单元测试不调用真实模型。采用新策略前需达到预先冻结的门槛并继续端到端验证。

[2026-10-19 复核专项对比](runs/2026-10-19-复核专项对比.md)记录 96 次固定草案复核、未采用 CHECKLIST 的依据及保留基线后的 15 题回归；全部失败和技术错误均归档，没有只保留最好的一轮。


## 适用条件澄清闭环评测

`scripts/evaluate_knowledge_clarification.py` 在隔离库启用 `docs/demo/trusted-knowledge/clarification-policy.json`，保留原 15 题，增加两次地区续答与一次独立澄清，合计 18 次问答；另外校验非法范围、ALL 和重复续答不新增消息。原始历史失败输入冻结在 `clarification-historical-failures.json`，未把人工重写样本冒充历史重放。

```powershell
python -X utf8 scripts/evaluate_knowledge_clarification.py --base-url http://localhost:8080 --output target/knowledge-clarification/run-01 --runtime-label <实际构建标识>
```

鉴权沿用前述四个环境变量或忽略的本地 auth-file。始终新建资料库，最终恢复原默认库，原库条件配置不修改；脚本不会自动重试失败生成。需要 V19 及当前后端代码；资料有效期仍为 2026-10-01 至 2027-01-01 之前。

新增阶段 `APPLICABILITY` 保存配置、主题识别和续答来源；只在 routingAttempted=true 时统计该阶段的模型用量，技术失败缺用量必须保留为未知。新增错误码：KNOWLEDGE_APPLICABILITY_CALL_FAILED（依赖调用故障）、KNOWLEDGE_APPLICABILITY_UNCERTAIN（主题不确定或范围无交集）、KNOWLEDGE_APPLICABILITY_CHANGED（生成期间配置变更）。前置非法参数返回 400，过期卡片/配置修订冲突返回 409，不进入模型运行分母。

[2026-10-19 澄清续答验证](runs/2026-10-19-澄清续答验证.md)：18 次中 16 自动通过，2 次原有复核否决，两个地区闭环通过；完整[原始数据](runs/2026-10-19-澄清续答数据.json)保留全部失败。自动通过不等同于语义金标准。

## 真实失败回放与稳定练习

`knowledge-review-replay-cases.json` 在原 24 题上追加 B03/B09 的真实输入及两个反例，共 28 题；`knowledge-review-replay-provenance.json` 保存来源与摘要。运行命令及候选解释见 [复核专项说明](knowledge-review.md)。原 24 题的默认 CHECKLIST 比较方式保留。

[真实失败复核与练习环境报告](runs/2026-10-19-真实失败复核与练习环境.md)记录 BASELINE/CONTEXTUAL 两轮共 112 次调用。候选总体误杀减少，但第一轮错误放行增加且 CHALLENGE 退化，未采用；[全量复核数据](runs/2026-10-19-真实失败复核数据.json)保留完整输入及全部判断。原 B03 误杀未复现，B09 仍被两种策略否决，不能描述为两条问题都已修复。

[练习环境说明](../demo/trusted-knowledge/练习环境.md)提供可重复使用的资料库初始化脚本。它不执行撤回场景，也不是模型质量评测。[五次练习验证数据](runs/2026-10-19-练习环境验证数据.json)记录通用期限及两个地区的澄清续答，五次均自动通过；运行中的 8080 服务构建未核实，不把这组结果称为候选策略端到端验证。

## 受控多跳对照（C2）

诊断自 schemaVersion=3 起 的 retrievalRun 保存全部搜索、规划和停止原因；retrieval 仍表示首轮。脚本按实际搜索结果并集计算召回覆盖，同时单列首轮覆盖；失败或截断的检索诊断不能按完整召回计分。PLANNING 用量与 DRAFT/REVIEW 一起计入诊断成本。

可用 `--knowledge-base-id 20 --skip-probes` 复用已经导入的冻结题库，仍校验资料 SHA、发布状态、有效期、检索参数及模型配置。输出目录必须新建；不能覆盖 C1 或挑选重跑结果。运行开关为 `app.knowledge.multihop.enabled`（默认 true），调度软预算 `scheduling-budget-seconds`（默认 90）。

首次真实对照见 [C2 报告](runs/2026-10-21-受控多跳首次对照.md)：9 通过、1 未通过、2 技术失败；仍有过早停止和复核问题，C 阶段尚未整体验收。

## 规则核查与状态衔接候选（阶段 38）

[完整报告](runs/2026-10-21-规则核查与状态衔接对照.md)：12 题 7 通过、1 未通过、4 技术失败；候选改善缺规则与冲突题，但整体退化，未默认启用。当时使用 `app.knowledge.multihop.rule-checks-enabled`，该旧开关已在阶段 41 移除。版本 4 诊断新增可空 answerContext 和规则锚点；关闭候选时 answerContext=null。

REASONED 固定草案回放共 96 次，第二轮误拒/协议失败退化，未通过事前门槛，默认复核仍为 BASELINE。[端到端全量数据](runs/2026-10-21-规则核查与状态衔接对照数据.json)与[复核全量数据](runs/2026-10-21-可定位复核回放数据.json)保留全部失败；不将总正确数或单个改善题作为采用依据。

阶段 39 仅修正候选查询队列和规划失败分类，详见 [实现说明](../../notes/39-检索队列与规划失败分类.md)。没有新增真实模型分数；规则候选仍关闭。后续新诊断可区分五类 KNOWLEDGE_PLAN_*_INVALID，旧记录不补造细分原因。

## 独立规划协议回放（阶段 40）

[独立题集](knowledge-planning-cases.json)为 8 个虚构设备制度场景，覆盖直接题、缺映射、完整链、并列缺口、缺规则、冲突、实际用户条件。运行 `python -X utf8 scripts/evaluate_knowledge_planning.py --output target/planning-replay-new --repeats 2`，显式调用生产规划客户端；不检索、不生成答案、不修改知识库。

[首次报告](runs/2026-10-21-独立规划协议回放.md)和[全量数据](runs/2026-10-21-独立规划协议回放数据.json)：16/16 协议通过，15/16 满足事前动作标签，0 技术失败。P01 第一次的额外核查不是回答错误；保留偏窄标签与未通过门槛，不事后改分。候选未启用，未进入完整问答评测。

## 单列表规划候选取舍（阶段 41）

[完整报告](runs/2026-10-21-单列表规划候选取舍.md)：同一冻结 12 题只跑一次，10 通过、1 未通过、1 技术失败，完整召回 11/12。原先通过的九题未退化，N01 改善；但 N02 仍错误确定回答，不满足事前采用门槛。已知 Token 62,538，比阶段 37 增长 6.89%。候选保持关闭，结束本项迭代，下一步进入 D 统一入口。

新候选只输出 decision/gaps/tasks/userConditions，显式开关为 `app.knowledge.multihop.task-planning-enabled=true`（环境变量 `KNOWLEDGE_TASK_PLANNING_ENABLED=true`），默认 false；复核仍 BASELINE。新快照版本 5，answerContext 使用 pendingQueries，旧 pendingRuleQueries 可读取；诊断 queries 保存转换后的任务，ruleChecks 为空。阶段 40 的独立回放工具现在调用新的候选协议，重新运行不等于复现旧版协议，旧数据保持原样。

## 多范围联合检索（阶段42）

[完整报告](runs/2026-10-21-多范围联合检索验证.md)保留首次会话入口缺陷及修复后六题：五题通过，一题部分可答草案被复核拒绝。全程使用新默认“dp官方”，未切回旧模型；修复后29,037已知Token包含失败和澄清初问，未计Embedding。最多三范围共用检索和生成预算，正常比较题一次搜索；不能将两轮成功项拼成六题全通过，或与旧模型得分直接比较。

## 统一可信问答入口（阶段43）

[验证报告](runs/2026-10-21-统一可信问答入口验证.md)：325项Java测试通过；使用“dp官方”验证旧AGENT同步、多范围SSE、跨模式澄清续答和KNOWLEDGE通用题，四场景五次问答均完成。初版检查器因时间戳精度差异将四题误报为历史不一致，原始失败结果保留；修正后只读复查四项通过，未新增模型调用。总16,944已知文本Token包括澄清初问；本轮不构成问答准确率提升或阶段42失败修复的证据。
