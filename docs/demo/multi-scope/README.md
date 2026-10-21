# 多范围功能验证

三份纯虚构制度：上海640元、北京480元、所有地区十二个自然日报销期限。广州没有住宿金额资料，用于验证部分可答。数据不代表真实制度或房价。

运行 `python scripts/evaluate_knowledge_scopes.py --output target/scopes-run --runtime-jar target/stage42-backend.jar`。凭据使用现有四个 HELPDESK_* 环境变量或忽略的 .env，不写入脚本。默认要求当前 CHAT 配置名为“dp官方”；脚本不修改模型配置。输出目录不可覆盖。

脚本新建独立库、上传并提交索引、发布三份资料，配置上海/北京/广州主题选项，临时切换默认库；六个场景各跑一次，澄清场景包含一次追问及一次多选续答。结束恢复原默认库，保留资料和诊断用于阅读。硬杀进程无法保证finally执行，应根据 results.json 的 originalKnowledgeBaseId 手动恢复。

覆盖旧单范围、多范围显式请求、自然语言比较、缺一侧资料、多选续答、通用资料跨地区适用。每题记录完整报告、诊断、调用次数和Token，失败不重试。模型变化后不与旧模型得分直接比较。自动检查不是语义盲评，需要阅读引用和最终回答。

请求示例：

```json
{"content":"比较上海和北京的住宿上限","applicabilityScopes":["CN_SH","CN_BJ"]}
```

不提供范围时，需知识库配置包含这些地区的完整名称与相关主题；复杂表述可通过显式列表选择。旧 applicabilityScope 仍可用，新旧字段不得冲突。最多三项，ALL只表示通用资料，不能与地区混选。续答还需 clarificationMessageId。
