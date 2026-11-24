# 公开制度检索对照评测

这是一套自建的小型中文制度检索基准，用于对比项目原有 BM25、向量和 RRF 排名效果，不是公开标准评测榜单。它只测试检索，不测试问题改写、规划、答案生成、引用核验或拒答率。

**当前标签为 AI 辅助构题、对照原文核对，尚未经过独立人工复核。不要将其描述为人工标注数据集、真实用户日志或生产指标。**

## 资料和题集

- 语料来源：深圳大学教务部公开学生手册中的学籍与学位管理、考试管理页面。23 份文件的标题、官方地址、正文摘要和段落摘要见 [sources.json](sources.json)。固定抓取日为 2026-11-24。
- 原始 HTML、去除导航后的正文及段落映射保存在忽略目录 `data/public-campus/`。仓库不复制发布这些制度全文；再次下载需匹配固定摘要，网页更新后会明确停止，不能静默更换语料。
- 提取使用 `#vsb_content` 下的非空 `p`，保留顺序和行内文字；不做 OCR，表格单元格里的段落按文档顺序排列。问题不依赖未提取的图片公式或表格排版。
- 100 个可回答问题：30 个开发集、70 个测试集，在第一次评测前固定。可阅读 [questions.tsv](questions.tsv)，机器标签见 [cases.json](cases.json)。开发集和测试集不共享标注段落；它们仍共享知识库、主题和语言风格，不代表跨领域泛化。
- 测试集含直接查询 8 题、口语改写 24 题、条件限定 30 题、多文档 8 题。多文档题主要是两个子问题并列，不能把它们当成需要中间推理结果的严格多跳任务。部分复合题复用了测试集内单题的证据，不是 70 个完全独立知识点。
- 另外保存 10 个缺少答案的问题，供后续问答拒答测试。它们没有进入本次检索 Recall 的分母，也没有在本轮执行生成或拒答测量。
- 资料包含不同年份发布的规定，本实验将每份文件视为固定语料，不判定其当前政策效力。导入时统一使用实验性 `snapshot-v1 / ALL / 2000-01-01` 元数据，让三个方案使用同一准入集合；这不是现实制度有效期标注。

## 指标口径

每题标注若干**必要证据组**，一组可包含多个可替代段落；本版主要采用单段落标签。证据绑定稳定来源 ID、段落编号和正文摘要，不绑定数据库自增 ID 或当前 chunk 编号。

取各方法排名前 K 个 **chunk**（K=1、5、10），然后计算：

| 指标 | 算法 |
|---|---|
| 参考文档 Recall@K | 命中的必要文档组 / 必要文档组总数，同文档去重 |
| 证据段落 Recall@K | 命中的必要证据组 / 必要证据组总数 |
| 证据 Hit@K | 至少命中一个证据组为 1，否则 0 |
| 证据齐全率@K | 所有必要证据组均命中为 1，否则 0 |
| 证据 MRR@K | 第一条包含标注证据的 chunk 排名倒数；前 K 无命中为 0 |

所有指标先逐题计算，再按题数宏平均。技术失败仍留在分母并记零，另报失败数。

证据命中采用严格规则：忽略空白后，至少一个返回 chunk 须完整包含该标注段落。只返回同文档其他内容不算证据命中；只包含标注段落的一部分也不算。它是**标注段落覆盖率**，不是全库所有相关 chunk 的穷举 Recall，也不是语义正确率。若切块导致证据段落被截断，本口径会受影响；增加等价段落、改变证据粒度应升级题集并重新评测，不能事后替换标签美化历史分数。

人工复核时尤其需要检查：问题的前提和限定是否准确、段落是否完整支撑问题、是否遗漏其他同样有效的证据、开发测试之间是否仍存在近似重复。当前高分不能替代这些核查。

## 公平比较的实现

原 `/search` 会先做 RRF 再截取 Top-K。直接从它反推单路排名，会漏掉被融合截断的候选，因而不适合作为单路基线。

新增管理员接口：

```http
POST /api/admin/knowledge-bases/{id}/search-candidates
Content-Type: application/json

{"query":"学生证丢了怎么办？"}
```

它复用生产 `KnowledgeRetrievalService.search`，沿用相同发布、有效期、范围、默认模型及当前索引批次过滤，只将返回上限设为两倍候选窗口，保留完整并集。原 `/search` 和问答链路的结果上限不变。

脚本先检查两路候选排名连续、数量齐全，再分别读取 `bm25Rank`、`vectorRank` 和后端 RRF 顺序。这是同一候选集合的排名消融对比，实际只生成一次查询向量、调用一次双路检索。不把融合结果重新用模拟分数排序，也不把两份截断结果当作完整基线。

本轮固定：单路候选窗口 25、KNN `num_candidates=50`、向量相似度下限 0.0、RRF 常数 60、Top-K 为 1/5/10。阈值 0.0 是为对比排名而预先选定的实验设置，不是对用户默认知识库的调参建议。普通查询结果上限设为 10，候选接口最多返回 50。

本轮模型为 `qwen3.7-text-embedding`、1024 维；分块大小 1000 字符、重叠 150，共 91 个 chunk。该库在本机 ID 为 27，名称为“公开制度检索评测-SZU-v1”，未设为默认问答库。

## 实测结果与边界

见 [2026-11-24 初测报告](runs/2026-11-24-initial.md) 和 [参数及逐题排名快照](runs/2026-11-24-initial.json)。

- 30 题开发集三种方案的证据 Recall@5 均为 100%。没有为了提高测试分数调整参数或标签。
- 70 题测试集证据 Recall@5：BM25 95.00%、向量 95.00%、RRF 99.29%。相对 BM25 是 **+4.29 个百分点**，不是提高 4.29 倍。
- 测试集 RRF 证据齐全率为 69/70，证据 Hit@5 为 70/70。这三个指标不同；99.29% 不是正确回答了 99.29% 的用户问题。
- RRF 的证据 MRR@5 为 0.8440，低于 BM25 的 0.8636，说明召回覆盖改善并不意味着首位排序全面改善。Top-10 时向量证据 Recall 为 100%，RRF 仍为 99.29%，也不能声称所有 K 下融合都最好。
- `q100` 的专业分流证据在向量路第 2 位，未进 BM25 的前 25；RRF 融合后为第 17，导致前 5 和前 10 均没找齐两份资料。这是单路强相关证据被双路共同命中的结果挤下去的实例；本轮保留该失败，不改测试集。
- 共享请求 P95 为 299 ms，只有单机串行一轮数据。不能推导单路速度、线上并发吞吐、完整问答延迟或最终回答成功率。
- 23 份资料、91 个 chunk 的规模较小，问题由资料辅助构造，且标签未人工复核。结果可用于解释实验设计和失败分析，不能宣称广泛真实业务上的 99% 召回或统计显著优势。

简历目前可如实写：

> 基于 23 份公开制度构建 100 条 AI 辅助标注检索用例，划分开发集与测试集，对比 BM25、向量与 RRF，建立证据段落召回率、MRR 及失败案例的可复现评测流程。

完成独立人工复核、保留修订记录并重跑后，再考虑把对应版本的测试指标加入简历；不要将当前标签写成人工金标准。

## 复现

后端、MySQL、ES 及默认 Embedding 模型需要可用。凭据由 `HELPDESK_ADMIN_USERNAME`、`HELPDESK_ADMIN_PASSWORD` 环境变量或项目根目录被忽略的 `.env` 提供；也可用 `--tokens data/本地令牌文件.json`，格式复用现有评测脚本。运行会产生向量模型调用。

```powershell
python -m pip install -r scripts/requirements-public-retrieval.txt
python scripts/prepare_public_retrieval_corpus.py
python scripts/evaluate_public_retrieval.py validate
python scripts/evaluate_public_retrieval.py prepare
python scripts/evaluate_public_retrieval.py evaluate --split dev --output data/public-campus/dev-new.json
python scripts/evaluate_public_retrieval.py evaluate --split test --output data/public-campus/test-new.json
python scripts/summarize_public_retrieval.py --dev data/public-campus/dev-new.json --test data/public-campus/test-new.json --output docs/evaluation/public-campus/runs/新的运行名.json
```

下载脚本的 `--freeze` 仅用于第一次创建新语料清单，已有清单时拒绝覆盖。不要为绕过摘要不匹配而使用它。

`prepare` 根据 `data/public-campus/state.json` 续用专用库，上传后提交索引、等待完成并发布。它不切换默认库、不删除原库，不自动无限重试失败索引；失败原因会明确输出。更换数据库时请使用对应状态文件，不能沿用其他数据库的 ID。

`validate` 生成 `data/public-campus/人工复核清单.md`，逐题列出证据原文。它只校验文件和标注引用一致，不代表语义已经人工审核。

原始 `dev-run.json`、`test-run.json` 含每题完整返回正文，留在本地；归档 JSON 只保留参数、指标、来源与 chunk 序号、内容摘要及匹配的证据组，便于核对而不复制制度全文。运行禁止覆盖同名结果，报告记录题集/语料摘要、实现摘要、模型与分块参数，并检查前后环境一致性。

本轮源码基点为 `a3f249d` 加完整候选接口及评测脚本，实际执行代码的 SHA-256 已记录在报告中；不能把该基点误认为已包含本轮改动。

```powershell
python -m unittest discover -s scripts -p test_evaluate_public_retrieval.py
mvn '-Dtest=KnowledgeRetrievalServiceTest' test
```
