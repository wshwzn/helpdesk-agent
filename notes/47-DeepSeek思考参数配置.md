# DeepSeek 思考参数配置

## 行为与业务规则

配置文件 `application.yaml` 新增：

```yaml
app:
  model-config:
    deepseek:
      thinking-type: ${DEEPSEEK_THINKING_TYPE:enabled}
      reasoning-effort: ${DEEPSEEK_REASONING_EFFORT:low}
```

仅厂商字段为 `DEEPSEEK` 的文本模型应用参数，不根据“dp官方”等显示名称或模型名称猜测厂商。配置适用于同一客户端的生成、复核、修订等请求。开启时发送顶层 `thinking: {type: enabled}` 与 `reasoning_effort: low`（强度以配置为准）；关闭时发送 `thinking: {type: disabled}`，不附带强度。其他厂商及嵌入模型保持原行为。

`thinking-type` 只允许 enabled/disabled，空值和拼写错误启动失败；强度不能为空，具体支持的强度由厂商接口约束。修改后需重启应用，现有模型客户端缓存不支持配置热更新。可以通过环境变量覆盖，也可以在本地 profile 配置文件中设置。

## 文件职责

- 新增 `DeepSeekThinkingProperties`：绑定并校验两个部署参数，提供默认值。
- 修改 `ModelConfigConfiguration`：注册参数绑定。
- 修改 `OpenAiCompatibleChatModelFactory`：通过 Spring AI 的 extraBody 写入 thinking 对象，通过 reasoningEffort 写入强度。
- 修改 `application.yaml` 和 `README.md`：提供默认配置与修改说明。
- 新增 `DeepSeekThinkingTest`：本地 HTTP 假服务接收实际序列化请求，验证字段位置、开关、强度覆盖、其他厂商隔离及配置绑定校验。

## API 与数据库

无管理端 API、前端或数据库变更，无需数据库迁移。变化只发生在发往 DeepSeek 的模型请求体。

## 测试与验证

`DeepSeekThinkingTest` 8 项、`TrustedKnowledgeConversationIntegrationTest` 24 项，共 32 项测试通过。使用本地 HTTP 假服务验证真实客户端请求的 JSON 顶层字段，覆盖 enabled/low、enabled/high、disabled/high 和其他四种厂商；配置绑定覆盖默认值、覆盖值及非法开关启动失败。未调用真实付费模型。

## 已知限制

不提供按模型配置单独覆盖、页面编辑或热更新。厂商标记为 CUSTOM 的配置不自动应用，即使地址指向 DeepSeek。关闭思考或降低强度对真实问答效果的影响需后续用同一批问题比较，本轮不宣称准确率或延迟得到改善。
