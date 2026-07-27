# jcloud 部署指南

## 目录

- [jcloud 部署指南](#jcloud-部署指南)
  - [目录](#目录)
  - [部署要求](#部署要求)
  - [环境变量](#环境变量)
  - [快速部署（内置 PostgreSQL + Redis）](#快速部署内置-postgresql--redis)
  - [使用外部 PostgreSQL / Redis](#使用外部-postgresql--redis)
  - [自定义端口](#自定义端口)
  - [数据持久化](#数据持久化)
  - [升级与维护](#升级与维护)
    - [升级版本](#升级版本)
    - [查看日志](#查看日志)
    - [停止服务](#停止服务)
  - [常见问题](#常见问题)
    - [1. 容器启动后无法访问](#1-容器启动后无法访问)
    - [2. 使用外部数据库时连接失败](#2-使用外部数据库时连接失败)
    - [3. 如何修改 JWT 密钥](#3-如何修改-jwt-密钥)
    - [4. 是否支持 HTTPS](#4-是否支持-https)

---

## 部署要求

- Docker Engine 24.0+
- Docker Compose v2.20+

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JCLOUD_PORT` | `80` | 对外访问端口 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/jcloud` | PostgreSQL 连接地址 |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | 数据库密码 |
| `REDIS_HOST` | `redis` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_DB` | `0` | Redis 数据库索引 |
| `JCLOUD_JWT_SECRET` | `change-me-in-production-jcloud-secret-key-2026` | JWT 签名密钥，至少36位字符**生产环境必须修改** |
| `JCLOUD_DATA_PATH` | `./data` | 数据存放目录，默认位于 `deploy/data` |

> 可通过 `.env` 文件、Shell 导出或 `docker compose` 命令行传入变量。`.env` 文件放在项目根目录即可。

---

## 快速部署（内置 PostgreSQL + Redis）

1. 克隆代码并进入项目根目录。
2. （可选）创建 `.env` 文件：

```bash
cat > .env <<EOF
JCLOUD_PORT=80
JCLOUD_JWT_SECRET=$(openssl rand -hex 32)
SPRING_DATASOURCE_PASSWORD=YourStrongPassword
EOF
```

3. 启动全部服务：

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

4. 等待健康检查通过后访问：

```
http://<服务器IP>:<JCLOUD_PORT>
```

首次访问会引导完成系统初始化。

---

## 使用外部 PostgreSQL / Redis

如果已有 PostgreSQL 与 Redis 实例，可跳过内置服务，仅启动 jcloud：

```bash
cat > .env <<EOF
JCLOUD_PORT=8080
JCLOUD_JWT_SECRET=$(openssl rand -hex 32)
SPRING_DATASOURCE_URL=jdbc:postgresql://your-db-host:5432/jcloud
SPRING_DATASOURCE_USERNAME=jcloud
SPRING_DATASOURCE_PASSWORD=YourStrongPassword
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_DB=0
EOF
```

```bash
docker compose -f deploy/docker-compose.yml up -d
```

---

## 自定义端口

修改 `JCLOUD_PORT` 即可，例如使用 `8080`：

```bash
JCLOUD_PORT=8080 docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

---

## 数据持久化

默认数据目录为 `./data`（相对于 `deploy/docker-compose.yml`，即 `deploy/data`）：

```
deploy/data/
├── files/      # 用户文件数据
├── system/     # 系统数据（预览图、ZIP 临时文件等）
├── postgres/   # 内置 PostgreSQL 数据
└── redis/      # 内置 Redis 数据
```

可通过 `JCLOUD_DATA_PATH` 修改，例如放到项目根目录：

```bash
JCLOUD_DATA_PATH=../data docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

---

## 升级与维护

### 升级版本

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache down
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

> Flyway 会在后端启动时自动执行数据库迁移。

### 查看日志

```bash
# jcloud 主服务
docker logs -f jcloud

# PostgreSQL
docker logs -f jcloud-postgres

# Redis
docker logs -f jcloud-redis
```

### 停止服务

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache down
```

---

## 常见问题

### 1. 容器启动后无法访问

- 检查防火墙是否放行 `JCLOUD_PORT`。
- 查看日志确认后端是否已正常启动：`docker logs -f jcloud`。
- 确认 PostgreSQL 健康检查已通过：`docker compose -f deploy/docker-compose.yml ps`。

### 2. 使用外部数据库时连接失败

- 确保 `SPRING_DATASOURCE_URL` 中的主机名在容器内可解析。
- 若使用宿主机上的数据库，可将 URL 中的主机设为 `host.docker.internal`（Linux 需 Docker 20.10+ 并启用 host gateway）。

### 3. 如何修改 JWT 密钥

编辑 `.env` 中的 `JCLOUD_JWT_SECRET`，然后重启容器：

```bash
docker compose -f deploy/docker-compose.yml restart jcloud
```

### 4. 是否支持 HTTPS

当前 `deploy/Caddyfile` 使用 HTTP。若需 HTTPS，建议：

- 使用外部反向代理（如 Nginx、Traefik、Cloudflare）终止 TLS。
- 或修改 `deploy/Caddyfile` 添加自动 HTTPS 域名配置，并重新构建镜像。
