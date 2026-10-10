# 后端服务器部署

## 运行组件

在 `/opt/helpdesk-agent` 运行 `compose.yaml`：后端 Java 21、MySQL 8.0、Elasticsearch 9.5.4。三个容器自动重启，日志按 10 MB × 3 轮转。

- 后端：宿主机 `127.0.0.1:8080`，最大堆 384 MB、容器内存上限 768 MB。
- MySQL：只在项目内部网络开放，容器内存上限 512 MB。
- Elasticsearch：单节点，启用密码认证，只在项目内部网络开放；堆 512 MB、容器内存上限 1280 MB。
- 内部网络的 ES HTTP 不启用 TLS；后端另接外连网络访问模型 API 和 OSS。
- `data/mysql`、`data/elasticsearch`、`data/objects` 保存持久化数据。

## 配置与启动

1. 本地运行 `mvn clean verify`，上传 `target/helpdesk-agent-0.0.1-SNAPSHOT.jar` 为服务器 `app.jar`。
2. 将 `compose.yaml` 和 `.env.example` 上传到部署目录，复制示例为 `.env`，填入独立随机密码和密钥；模型加密密钥为 32 字节随机值的 Base64，JWT 密钥至少 32 字节。
3. `STORAGE_PROVIDER=aliyun-oss` 时填入四项 OSS 配置，否则使用持久化本地目录。
4. 将 ES 数据目录属主设为 `1000:0`，原始文档目录属主设为 `10001:10001`，配置文件权限设为 `600`。
5. 确认 `vm.max_map_count` 至少为 `1048576`，随后执行：

```bash
cd /opt/helpdesk-agent
docker compose config --quiet
docker compose up -d --wait --wait-timeout 300
curl -fsS http://127.0.0.1:8080/actuator/health
```

使用 `prod` 配置，Flyway 自动初始化空库。Maven 的 JAR 打包阶段排除 `application-local.yml`，本地 `spring-boot:run` 仍可使用开发配置。`.env` 不放入仓库，修改模型加密密钥会导致原模型 API Key 无法解密，应与数据库一起备份。

当前服务器已初始化与登录页预填一致的两个演示账号：管理员 `admin`、客户 `user`。角色和登录均已验证；知识库与模型配置保持为空。

重新部署到全新数据库时，先通过注册接口创建账号，再由数据库管理者将指定账号的 `users.role` 修改为 `ADMIN`，随后重新登录获取管理员令牌。

## 前端接入

把 `openresty-api.conf.example` 中的 `location /api/` 加到两个站点的 `server {}`。当前 OpenResty 使用宿主网络，可以访问 `127.0.0.1:8080`。请求保留 `/api` 前缀；配置关闭响应缓冲并延长读取超时，以支持 SSE。前端 API 地址使用 `/api`。

## 更新与备份

更新前备份现有 JAR，再上传新版本。上传时先使用新文件名，上传完成后替换 `app.jar`，再执行 `docker compose up -d --force-recreate backend`，避免上传中的 JAR 被启动。

```bash
cd /opt/helpdesk-agent
docker compose ps
docker compose logs --tail 80 backend
bash backup.sh
```

备份脚本保存 MySQL 与密钥，文件仅属主可读；备份应另行下载。OSS 原文由云端保存，ES Chunk 可使用同一 Embedding 模型重新索引生成。未配置聊天或向量模型时，对话和索引不能调用模型，应先登录管理端分别配置默认 Chat、Embedding。
