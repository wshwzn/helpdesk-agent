# 固定草案复核专项评测

本评测固定问题、证据、草案，只比较复核判断。端到端失败可能来自检索、生成、引用或复核；固定草案能排除前三项输入波动，但仍不能证明人工语义正确性。

## 题集与预期标签

`knowledge-review-cases.json` 包含 12 对、24 题。每对共用问题与证据，只改变回答中的金额、否定、范围、例外、完整性、状态、冲突或追问；还包含资料夹带“全部 PASS”指令的正反例。全部草案须先通过程序的结构及逐字引用校验，避免把格式错误当成语义检出能力。

标签来源为 `DEVELOPER_ASSISTANT_PROVISIONAL`，每题有中文 `rationale` 供人工审阅。16 题 DEVELOPMENT、8 题 CHALLENGE 在调用模型前一起冻结；CHALLENGE 为不同领域的新文本，不是由独立人员编写的盲测。标签不来自被测复核模型，也没有人类金标准保证。运行器只把问题、范围、证据、草案和必要结论路径传给模型，预期标签、理由、题号及分组均不进入请求。

两种策略各跑两轮，共计划 96 次应用层调用。每轮按固定种子重排题目，每题交替两种策略的先后顺序；不根据错误补题或悄悄重试。底层 SDK 使用原项目客户端配置，应用层调用次数不等于已审计的 HTTP 请求次数。

## 策略

- `BASELINE`：原复核规则和布尔提示，兼容可选原因枚举。它是默认运行策略。
- `CHECKLIST`：实验策略，逐结论检查 `supported`、`conditionsPreserved`，再检查 `statusAppropriate`、`complete`、`conflictsHandled`、`clarificationAppropriate`。仅接收 PASS/FAIL/UNCERTAIN。

普通结论路径为 `claims/0`，冲突双方为 `conflicts/0/first`、`conflicts/0/second`。后端校验每条路径恰好出现一次，并要求所有判断全部 PASS 才批准。重复、缺失、未知路径、未知字段或缺失判断是技术协议错误；UNCERTAIN 按不确定否决，不能放行。没有结论时仍须完成四项全局判断。

检查表不接受模型自报的总 approved，后端从逐项判断汇总。**这只能保证协议覆盖与汇总一致，不能保证模型逐项判断真实正确。** 原本共享的规则及基线复核提示保持原文，生成提示不变。

## 运行

需要 Java 21、Maven、Python 3.10+、项目的本地数据库及有效默认 Chat 模型配置。模型密钥由现有加密配置组件在内存中读取；运行器不导出密钥，不需要管理员或客户登录凭据。

```powershell
python -X utf8 scripts/evaluate_knowledge_review.py --repeats 2 --output target/knowledge-review/comparison-01
```

脚本先执行 test-compile 并准备依赖，再运行测试目录内的 `KnowledgeReviewEvaluationCli`。CLI 只引入模型配置、加解密、模型工厂和复核客户端，不启动 Web、Flyway、业务调度或 ES 检索；数据库连接以只读模式读取当前默认模型。默认模型在过程中变化则停止，并保留部分结果。

正常 `mvn test` 不调用此 CLI，不访问真实模型。输出目录必须在 `target/` 下且不得已存在；重复运行必须使用新目录。该限制让原始启动/依赖日志保持 Git 忽略，归档时只选择已脱敏数据。

输出包括：

- `dataset.json`：本次题集快照，含预期标签和理由。
- `environment.json`：Git、主代码和运行器源码摘要、题集摘要；dirty 明确记录未提交状态。
- `metadata.json`：实际模型配置 ID/名称、两种提示摘要、计划数、开始时间，不记录地址或凭据。
- `calls.jsonl`：每完成一次调用追加一行，保存题号、策略、轮次、判断/检查表、固定错误码、阶段用量和耗时。被否决草案来自题集，不重新生成。
- `finished.json`：CLI 正常完成时才写入。
- `summary.json`：分策略、轮次、DEVELOPMENT/CHALLENGE 的计数、率、稳定性与事先门槛判断。
- `build.log`、`runtime.log`、Java 参数文件：本地诊断使用，不公开归档。

启动失败、中断或模型配置变化可留下不完整运行；未完成结果不能参与采用决策。脚本退出 0 表示评测完整执行，不代表候选方案达到采用门槛。查看 `candidateEligibleForEndToEnd`，并继续人工审阅误判。

## 指标与采用门槛

| 指标 | 口径 |
| --- | --- |
| 误杀率 | 正例中被合法否决的次数 / 正例尝试次数 |
| 漏检率 | 负例中被合法批准的次数 / 负例尝试次数 |
| 技术失败 | 协议、网络等失败单列，另分正例/负例；不会算为正确拦截 |
| 正确判断 | 有合法结果且与预期标签相同的次数 |
| 跨轮翻转 | 同题同策略至少两次合法判断中出现不同批准结果；缺结果单独反映在失败和完成度 |
| 已知 Token | 累计厂商已返回的 totalTokens，未知单列，不把未知当作零成本 |

误杀和漏检必须与技术失败一起读取，否则“所有调用失败”的方案也会得到零误杀、零漏检。报告保存明确分母，不用失败排除后的漂亮数字替代可用性。

题集在实测前定义：候选两轮均须减少误杀，漏检和技术失败均不增加，CHALLENGE 各项不退化，才进入候选端到端验证。然后与同轮 BASELINE 的原 15 题比较，不能增加错误放行，整体自动通过数不得降低。门槛未通过就保留默认策略，记录失败实验；不为了采用候选而放宽规则或重跑挑选高分。

## 端到端实验与诊断

临时服务启动时可通过 `--app.knowledge.review-strategy=CHECKLIST` 显式启用实验方案；不设置时为 BASELINE。实验应在独立临时服务运行，并在原端到端脚本 `--runtime-label` 中记录 JAR 摘要和策略。

知识回答仍经历原检索、草案、引用校验、复核、准入复查和保存流程。新增 `review.checklist` 仅出现在管理员诊断中；旧诊断缺此字段时为空，客户 API 不增加实验字段。结构合法的否决映射已有原因枚举；协议失败保留已知用量，语义判断不能冒充人工结论。

两轮小样本仅作开发决策依据，不能推出泛化准确率或统计显著性。冻结题集后若修正标签，必须说明依据、生成新题集版本并保留原实验，不追溯改写已公布成绩。

## 真实失败回放与 CONTEXTUAL 候选

`knowledge-review-replay-cases.json` 保留原 24 题，增加历史 B03/B09 的原样问题、范围、证据与草案，以及各一个错误金额/错误状态反例，共 28 题。新增题均为 DEVELOPMENT；原 8 题 CHALLENGE 已用于先前实验，不能称为新盲测。标签仍为助手暂定标签，来源摘要与反例改动见 `knowledge-review-replay-provenance.json`。

`CONTEXTUAL` 在原布尔复核提示后补充上下文理解：判断结论时结合问题、显式范围及完整草案，不要求每条结论机械重复条件；证据不足时允许只回答有据部分。扩大条件、无依据事实，以及声称 ANSWERABLE 却遗漏实质问题仍须拒绝。原共享规则、BASELINE、CHECKLIST 和草案生成提示保持不变。

```powershell
python -X utf8 scripts/evaluate_knowledge_review.py --dataset docs/evaluation/knowledge-review-replay-cases.json --candidate CONTEXTUAL --repeats 2 --output target/knowledge-review/contextual-replay-02
```

`--candidate` 可选 CHECKLIST 或 CONTEXTUAL，默认 CHECKLIST；每次仅比较 BASELINE 与所选候选。上述 28 题两轮共 112 次调用，沿用相同采用门槛，不因新增候选而放宽条件。输出目录必须是新的目录，示例 `-02` 不是自动重试指令。

实际首轮实验已完成：BASELINE 误杀 6/28、错误放行 3/28；CONTEXTUAL 误杀 4/28、错误放行 3/28，双方技术失败均为 0。但候选第一轮错误放行增加、CHALLENGE 退化，已知 Token 增加约 28.8%，故未进入候选端到端验证，默认保留 BASELINE。B03 历史误杀未复现，B09 两种策略两轮都否决。详见 [完整报告](runs/2026-10-19-真实失败复核与练习环境.md)。
