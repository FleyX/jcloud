# jcloud 部署指南

本文档说明如何使用 Docker Compose 部署 jcloud。部署产物为单一镜像，内部同时包含前端静态资源、后端 Spring Boot 应用以及 Caddy 反向代理，对外仅暴露一个由环境变量控制的端口。

所有部署相关文件（`Dockerfile`、`docker-compose.yml`、`Caddyfile`、`.dockerignore`、`start.sh`）均位于项目根目录的 `deploy/` 文件夹下。

## 目录

- [部署文件位置](#部署文件位置)
- [部署架构](#部署架构)
- [部署要求](#部署要求)
- [环境变量](#环境变量)
- [快速部署（内置 PostgreSQL + Redis）](#快速部署内置-postgresql--redis)
- [使用外部 PostgreSQL / Redis](#使用外部-postgresql--redis)
- [自定义 Caddy 端口](#自定义-caddy-端口)
- [多架构镜像构建](#多架构镜像构建)
- [自动发布到 DockerHub](#自动发布到-dockerhub)
- [数据持久化](#数据持久化)
- [升级与维护](#升级与维护)
- [常见问题](#常见问题)

---

## 部署文件位置

```
jcloud/
├── deploy/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── Caddyfile
│   ├── start.sh
│   └── .dockerignore
├── deploy.md
└── ...
```

所有 `docker compose` 命令默认从项目根目录执行，并通过 `-f deploy/docker-compose.yml` 指定 Compose 文件。

---

## 部署架构

```
┌─────────────────────────────────────┐
│  jcloud 容器                         │
│  ┌───────────────────────────────┐  │
│  │  Caddy（端口可配）              │  │
│  │  - 静态资源 → /srv/web         │  │
│  │  - /jcloud/api → 后端 8080     │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │  Spring Boot 应用（端口 8080）  │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
 PostgreSQL     Redis
（可选内置）   （可选内置）
```

- **前端**：Caddy 直接提供 `web/dist` 中的静态资源，并支持 SPA 路由回退。
- **后端**：Caddy 将 `/jcloud/api/*` 请求透传至内部 Spring Boot 服务。
- **数据库**：可通过 `--profile db` 启动内置 PostgreSQL，或配置外部实例。
- **缓存**：可通过 `--profile cache` 启动内置 Redis，或配置外部实例。

---

## 部署要求

- Docker Engine 24.0+（支持 `depends_on.required`）
- Docker Compose v2.20+
- 目标平台：Linux x86_64（amd64）、Linux ARM64（arm64/v8）

---

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JCLOUD_PORT` | `80` | Caddy 对外监听端口 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/jcloud` | PostgreSQL 连接地址 |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | 数据库密码 |
| `REDIS_HOST` | `redis` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_DB` | `0` | Redis 数据库索引 |
| `JCLOUD_JWT_SECRET` | `change-me-in-production-jcloud-secret-key-2026` | JWT 签名密钥，**生产环境必须修改** |
| `JCLOUD_DATA_PATH` | `./data` | 宿主机数据挂载根目录，默认位于 `deploy/data` |

> 提示：所有变量可通过 `.env` 文件、Shell 导出或 `docker compose` 命令行传入。从项目根目录执行时，`.env` 文件放在项目根目录即可。

---

## 快速部署（内置 PostgreSQL + Redis）

1. 克隆代码并进入项目根目录。
2. （可选）创建 `.env` 文件覆盖默认配置：

```bash
cat > .env <<EOF
JCLOUD_PORT=80
JCLOUD_JWT_SECRET=$(openssl rand -hex 32)
SPRING_DATASOURCE_PASSWORD=YourStrongPassword
EOF
```

3. 启动全部服务（首次会自动从 DockerHub 拉取 `fleyx/jcloud:latest`）：

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

4. 等待健康检查通过后访问：

```
http://<服务器IP>:<JCLOUD_PORT>
```

首次访问会引导完成系统初始化（创建管理员、配置系统存储空间等）。

---

## 使用外部 PostgreSQL / Redis

如果已有 PostgreSQL 与 Redis 实例，可跳过内置服务，仅启动 jcloud 容器。

1. 创建 `.env`：

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

2. 仅启动 jcloud：

```bash
docker compose -f deploy/docker-compose.yml up -d
```

> 内置的 `postgres` 与 `redis` 服务带有 `profiles`，不会在此模式下创建。

---

## 自定义 Caddy 端口

只需修改 `JCLOUD_PORT` 即可，例如使用 `8080` 避免以非 root 运行时绑定低端口的权限问题：

```bash
JCLOUD_PORT=8080 docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

---

## 多架构镜像构建

项目镜像支持 `linux/amd64` 与 `linux/arm64`。使用 Docker Buildx 构建并推送：

```bash
# 创建并切换到 buildx 构造器（仅需执行一次）
docker buildx create --use --name jcloud-builder || docker buildx use jcloud-builder

# 构建并推送至镜像仓库（构建上下文为项目根目录）
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f deploy/Dockerfile \
  -t your-registry/jcloud:latest \
  --push .
```

如果仅在本地构建当前架构：

```bash
docker build -f deploy/Dockerfile -t jcloud:latest .
```

> BuildKit 会自动使用 `deploy/.dockerignore` 排除构建上下文中的无关文件。
>
> `docker-compose.yml` 默认直接从 DockerHub 拉取镜像。若使用本地构建的镜像，可通过 `JCLOUD_IMAGE` 指定：
>
> ```bash
> JCLOUD_IMAGE=jcloud:latest docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
> ```

---

## 自动发布到 DockerHub

项目提供 `deploy/release.sh` 脚本，自动从 GitHub 获取最新 release tag，构建多架构镜像并推送到 DockerHub。

### 前置要求

- 已安装 Docker 并启用 BuildKit / buildx
- 已登录 DockerHub：`docker login`
- 已安装 `gh` CLI，或 `curl` + `jq`（用于读取 GitHub 最新 tag）

### 使用方法

1. 确保当前目录为项目根目录。
2. 执行发布脚本：

```bash
./deploy/release.sh
```

脚本默认推送到 `fleyx/jcloud`，可通过环境变量覆盖：

```bash
DOCKERHUB_REPO=yourname/jcloud ./deploy/release.sh
```

如果 GitHub 仓库名无法从 `git remote` 自动推断，可显式指定：

```bash
GITHUB_REPO=FleyX/jcloud DOCKERHUB_REPO=yourname/jcloud ./deploy/release.sh
```

### 行为说明

- 优先通过 `gh release view` 获取 GitHub 最新 release tag。
- 若未安装 `gh`，则通过 GitHub API (`curl` + `jq`) 获取。
- 若两者均不可用，则回退到本地最新 git tag。
- 构建完成后同时推送 `${DOCKERHUB_REPO}:${VERSION_TAG}` 与 `${DOCKERHUB_REPO}:latest`。
- 支持 `linux/amd64` 与 `linux/arm64` 两种架构。

---

## 数据持久化

默认挂载目录为 `./data`（相对于 `deploy/docker-compose.yml`，即 `deploy/data`），结构如下：

```
deploy/data/
├── files/      # 用户文件数据
├── system/     # 系统数据（预览图、ZIP 临时文件等）
├── postgres/   # 内置 PostgreSQL 数据
└── redis/      # 内置 Redis 数据
```

可通过 `JCLOUD_DATA_PATH` 修改宿主机挂载根目录，例如将数据放到项目根目录的 `data` 文件夹：

```bash
JCLOUD_DATA_PATH=../data docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

> 当在 `deploy/` 目录内直接执行 `docker compose` 时，`JCLOUD_DATA_PATH=../data` 会指向项目根目录的 `data/`。

---

## 升级与维护

### 升级版本

1. 拉取最新代码或更新镜像标签。
2. 重新构建镜像：

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache down
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d --build
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
# 停止并移除容器（保留数据卷）
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
