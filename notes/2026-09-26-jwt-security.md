# JWT 认证与角色权限变更说明

## 实现的功能和规则

- 使用 Spring Security 拦截受保护接口，并通过 JWT 验证无状态登录身份。
- 支持客户注册和用户登录，密码使用 BCrypt 哈希保存。
- 公开注册固定创建 `CUSTOMER`，不能由客户端指定为客服或管理员。
- `/api/tickets/**` 仅允许客户访问，且客户只能查询、创建和取消自己的工单。
- `/api/support/**` 仅允许 `SUPPORT` 访问，领取人和完结人从 JWT 获取。
- 未认证请求返回 HTTP 401，角色不符返回 HTTP 403。

## 新增的类和文件

| 类或文件 | 作用 |
| --- | --- |
| `User`、`UserRole`、`UserMapper` | 用户持久化模型、角色枚举和数据库访问。 |
| `AuthController`、`AuthService` | 提供注册、登录和令牌签发流程。 |
| `RegisterRequest`、`LoginRequest` | 校验注册与登录请求。 |
| `AuthResponse`、`UserResponse` | 返回访问令牌、过期时间和安全的用户信息。 |
| `SecurityConfig` | 配置无状态安全链、接口角色、BCrypt 和 JWT 编解码器。 |
| `JwtTokenService`、`JwtProperties`、`IssuedToken` | 生成带用户 ID、账号和角色声明的 JWT。 |
| `CurrentUserProvider` | 从 Spring Security 上下文读取可信用户 ID。 |
| `RestAuthenticationEntryPoint`、`RestAccessDeniedHandler` | 统一输出 JSON 格式的 401、403 响应。 |
| `V3__create_users.sql` | 创建用户表及角色、启用状态索引。 |
| `AuthControllerIntegrationTest` | 覆盖注册、登录、账号冲突、认证和角色限制。 |

## 修改的类和文件

| 类或文件 | 修改内容 |
| --- | --- |
| `pom.xml` | 增加 Spring Security 和 OAuth2 Resource Server 依赖。 |
| `TicketController`、`SupportTicketController` | 从登录上下文获取用户 ID，不再接收客户端用户编号。 |
| `TicketService` | 接收可信用户 ID并校验客户只能访问自己的工单。 |
| 工单请求 DTO | 删除 `creatorId`、`customerId` 和 `supportId`；领取接口不再需要请求体。 |
| `GlobalExceptionHandler` | 增加账号冲突和登录失败响应。 |
| `application.yaml` 及环境配置 | 增加 JWT 签发者、密钥和有效期配置。 |
| `README.md` | 增加中文认证说明并更新全部接口示例。 |

## API 和数据库变化

新增接口：

```http
POST /api/auth/register
POST /api/auth/login
```

Flyway V3 新增 `users` 表。为兼容已有工单数据，本阶段未给 `tickets.creator_id` 和 `assignee_id` 增加外键。

## 验证结果

- `mvn test`：10 个测试通过，0 失败。
- H2 测试库：V1、V2、V3 迁移均执行成功。
- 集成测试覆盖 JWT 签发与验证、401、403、客户数据隔离和客服身份绑定。
- 本地 MySQL 9.3：V3 迁移成功，已手工跑通“注册 → 携带 JWT 创建工单 → 查询自己的工单”。
- 手工验证生成的用户和工单已删除，原有数据未修改。

## 当前限制与后续工作

- 只有访问令牌，尚未实现刷新令牌、主动注销和令牌撤销。
- 尚未提供管理员创建、禁用客服账号的接口；本地开发暂时通过数据库调整角色。
- 用户被禁用后，已签发的访问令牌在过期前仍然有效。
- 认证功能上线前产生的工单若没有对应用户，需要后续做账号映射或数据迁移。
- 生产环境必须通过 `JWT_SECRET` 提供至少 32 字节的随机密钥。
