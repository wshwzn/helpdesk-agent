# 动态模型配置功能变更说明

## 实现的功能和规则

- 引入 Spring AI 1.1.8，当前支持运行时创建 `OPENAI_COMPATIBLE` 类型的聊天模型。
- 管理员可以新增、查询、修改、启用、禁用、测试和切换默认模型配置。
- API Key 使用 AES-256-GCM 和随机 IV 加密入库，接口只返回后四位掩码。
- 更新配置时不提交 `apiKey` 会保留原密钥；配置变更后自动清理对应模型缓存。
- 只有启用的模型才能设为默认模型，默认模型不能直接禁用。
- 连接测试失败只返回脱敏提示，不向客户端暴露厂商异常或 API Key。
- `/api/admin/**` 仅允许 `ADMIN` 角色访问。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `AiModelConfig`、`ModelProviderType`、`AiModelConfigMapper` | 模型配置领域模型、厂商协议枚举和持久化访问。 |
| 模型配置请求与响应 DTO | 校验管理请求，并避免密文、乐观锁版本等内部字段泄露。 |
| `AiModelConfigService` | 实现配置管理、默认模型切换、连接测试和业务校验。 |
| `AdminModelConfigController` | 提供管理员模型配置接口。 |
| `SecretEncryptor`、`AesGcmSecretEncryptor` | 使用 AES-GCM 加密和解密 API Key。 |
| `ModelEncryptionProperties`、`ModelConfigConfiguration` | 读取并校验独立的 256 位加密主密钥。 |
| `AiChatModelFactory`、`OpenAiCompatibleChatModelFactory` | 按厂商协议动态创建 Spring AI `ChatModel`。 |
| `DynamicChatModelRegistry` | 按配置 ID 和版本缓存模型，支持主动失效。 |
| `DefaultChatModelProvider`、`ResolvedChatModel` | 为后续对话功能解析唯一的启用默认模型。 |
| `ModelConnectionTester` | 抽象模型连通性测试，生产实现发送最小提示词。 |
| `V4__create_ai_model_configs.sql` | 创建模型配置表、唯一名称约束和启用/默认索引。 |
| `AdminModelConfigControllerIntegrationTest` | 覆盖权限、加密、掩码、更新、默认模型和连接测试。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `pom.xml` | 引入 Spring AI 1.1.8 BOM 和 OpenAI 模型模块。 |
| `SecurityConfig` | 增加管理员接口角色限制。 |
| `GlobalExceptionHandler` | 增加模型配置 400、404 和 409 异常映射。 |
| `application.yaml` | 增加 `MODEL_CONFIG_ENCRYPTION_KEY` 配置。 |
| `README.md` | 增加密钥生成方式和模型管理接口说明。 |

## API 和数据库变化

新增 `/api/admin/model-configs` 下的查询、创建、修改、连接测试、默认切换和启停接口。Flyway V4 新增 `ai_model_configs` 表；API Key 明文不会写入数据库。

## 验证结果

- `mvn test`：16 个测试通过，0 失败。
- H2 测试库：V1 至 V4 迁移均执行成功。
- 测试确认相同 API Key 每次产生不同密文，篡改密文会导致解密失败。
- 测试确认客户不能访问管理员接口，响应及连接失败信息不包含 API Key。
- 本地 MySQL 9.3：V4 迁移成功，已手工跑通“管理员登录 → 创建配置 → 查询掩码 → 设为默认模型”。
- 手工验证使用虚构 API Key，未调用厂商；验证用户和模型配置已清理。

## 当前限制与后续工作

- 目前只实现 OpenAI 兼容协议，原生 Anthropic、Ollama 等协议可通过新增工厂扩展。
- 尚未实现管理员账号管理，本地开发仍需在数据库中调整用户角色。
- 自定义 Base URL 只校验 HTTP/HTTPS 格式，生产部署还需结合网络策略防范 SSRF。
- 加密主密钥轮换字段已经预留，但自动轮换和批量重加密尚未实现。
- 连接测试会产生一次极小的模型请求；自动化测试使用替身，不访问真实厂商。
