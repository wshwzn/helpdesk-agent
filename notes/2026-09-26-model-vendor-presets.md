# 模型协议与厂商预设调整说明

## 实现的功能和规则

- 模型调用统一使用 `OPENAI_COMPATIBLE` 协议，不再把厂商误建模为不同协议或 SDK。
- 新增 `OPENAI`、`DEEPSEEK`、`QWEN`、`OLLAMA` 和 `CUSTOM` 厂商选项。
- 内置厂商地址由后端维护，请求必须省略 `baseUrl`；`CUSTOM` 必须提交合法的 HTTP/HTTPS SDK 基础地址。
- 自定义地址不能包含查询参数、片段、用户信息，也不能以 `/chat/completions` 结尾。
- Spring AI 客户端会将 SDK 基础地址拆为主机与请求路径，避免把 `/v1` 重复拼接。
- 旧配置迁移后归类为 `CUSTOM`，原有地址和密钥保持不变。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `ModelProtocol` | 表示底层调用协议，当前只有 OpenAI 兼容协议。 |
| `ModelVendor` | 定义厂商名称、预设地址以及是否需要自定义地址。 |
| `ModelVendorResponse` | 向管理端返回可选厂商及地址填写规则。 |
| `OpenAiCompatibleEndpoint`、`OpenAiCompatibleEndpointResolver` | 将 SDK 基础地址转换为 Spring AI 所需的主机地址和聊天接口路径。 |
| `V5__add_model_vendor_presets.sql` | 将 `provider_type` 改为 `protocol`，并新增 `vendor` 字段。 |
| `OpenAiCompatibleEndpointResolverTest` | 验证有无 `/v1` 的地址都能正确生成聊天接口路径。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `AiModelConfig`、请求/响应 DTO | 拆分 `protocol` 与 `vendor`，只允许自定义厂商提交地址。 |
| `AiModelConfigService` | 固定协议、解析厂商预设并校验自定义地址。 |
| `AiChatModelFactory`、`DynamicChatModelRegistry` | 改为按协议选择工厂。 |
| `OpenAiCompatibleChatModelFactory` | 显式设置正确的 `completionsPath`，修复 `/v1` 重复风险。 |
| `AdminModelConfigController` | 新增厂商列表接口。 |
| `AdminModelConfigControllerIntegrationTest` | 覆盖预设地址、自定义地址规则及新响应字段。 |
| `README.md` | 更新模型配置示例和厂商说明。 |

## API 和数据库变化

- 新增 `GET /api/admin/model-configs/vendors`。
- 创建和更新请求由 `providerType` 改为 `vendor`；内置厂商不再接收 `baseUrl`。
- 响应新增 `protocol`、`vendor`，继续返回最终使用的 `baseUrl`。
- Flyway V5 将表字段 `provider_type` 重命名为 `protocol`，新增非空 `vendor` 字段，历史数据默认值为 `CUSTOM`。

## 验证结果

- `mvn test`：20 个测试通过，0 失败。
- H2 已成功执行 V1 至 V5 迁移；本地 MySQL 9.3 已从 V4 平滑升级到 V5。
- 自动化测试覆盖内置厂商地址、自定义地址必填、预设地址不可覆盖和 API Key 加密脱敏。
- URL 单元测试确认 `/v1` 不会重复拼接，DeepSeek 无版本路径地址会生成 `/chat/completions`。
- 本地手工验证已跑通“管理员登录 → 查询厂商列表 → 不传地址创建 DeepSeek 配置 → 检查数据库协议、厂商和预设地址”，临时数据已清理。

## 当前限制与后续工作

- 厂商预设当前固定为默认节点，尚未支持同一厂商的区域节点选择。
- `CUSTOM` 地址仍需在生产环境结合网络出口策略防范 SSRF。
- 当前仅实现聊天模型；Embedding 地址和模型配置将在知识库阶段单独设计。
