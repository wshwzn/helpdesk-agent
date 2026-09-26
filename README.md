# 智能工单助手

一个面向 SaaS 外部客户服务场景的智能工单助手后端。当前阶段已完成工单领域基础能力，后续 Agent 将通过工具调用这些接口完成工单创建、查询和状态流转。

## 环境要求

- Java 21
- Maven 3.9+
- MySQL 8.0+，默认端口 `3306`

## 本地启动

创建本地数据库：

```sql
CREATE DATABASE helpdesk_agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

将 `src/main/resources/application-local.example.yml` 复制为 `application-local.yml`，然后填写本地数据库账号和密码。`application-local.yml` 已被 Git 忽略，不会提交到远程仓库。

```powershell
mvn test
mvn spring-boot:run
```

应用启动时，Flyway 会自动执行 `src/main/resources/db/migration` 中尚未执行的数据库迁移。

## 数据库表

- `tickets`：工单业务表，保存工单编号、标题、问题描述、优先级、状态、创建人和创建时间等信息。
- `flyway_schema_history`：Flyway 自动创建的迁移记录表，用于记录每个 SQL 迁移脚本是否执行成功，防止重复建表。请勿手动修改或删除其中的数据。

## 工单接口

创建工单：

```http
POST /api/tickets
Content-Type: application/json

{
  "title": "无法连接公司 VPN",
  "description": "VPN 客户端连接时提示超时。",
  "priority": "HIGH",
  "creatorId": 1
}
```

查询工单详情：

```http
GET /api/tickets/{id}
```

分页查询工单，可按创建人和状态筛选：

```http
GET /api/tickets?page=1&size=20&creatorId=1&status=PENDING
```

优先级可选值为 `LOW`、`MEDIUM`、`HIGH`；当前新工单的初始状态为 `PENDING`。

## 工单处理流程

工单状态按照以下规则流转：

```text
PENDING（待处理）→ PROCESSING（处理中）→ RESOLVED（已解决）
       └────────→ CANCELLED（已取消）←────────┘
```

客户可以取消自己创建且处于待处理或处理中状态的工单。处理中工单被取消后会保留已分配的客服信息，以便审计；已解决和已取消的工单不能再次取消：

```http
POST /api/tickets/{id}/cancel

{
  "customerId": 1,
  "reason": "问题已经自行解决"
}
```

客服可以查询工单队列并领取待处理工单：

```http
GET  /api/support/tickets?status=PENDING
POST /api/support/tickets/{id}/claim

{
  "supportId": 200
}
```

只有领取该工单的客服可以填写处理结果并完结工单：

```http
POST /api/support/tickets/{id}/resolve

{
  "supportId": 200,
  "resolution": "已重置客户的上传配额"
}
```

当前阶段通过请求参数传递客户和客服编号。加入登录认证后，这些编号将改为从登录身份中获取，不能继续信任客户端传值。
