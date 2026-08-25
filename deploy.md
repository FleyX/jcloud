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
  - [指定运行用户（PUID/PGID）](#指定运行用户puidpgid)
  - [NVIDIA 硬件加速（可选）](#nvidia-硬件加速可选)
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
| `PUID` | `0` | 容器运行用户 uid，用于解决宿主机数据目录归属 root 的权限问题（详见下文「指定运行用户」） |
| `PGID` | `0` | 容器运行用户组 gid，与 `PUID` 配合使用 |
| `TZ` | `Asia/Shanghai` | 容器时区，默认透传宿主机 `TZ` 环境变量 |

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

## 指定运行用户（PUID/PGID）

默认容器内进程以 root 运行，bind mount 数据目录中的文件在宿主机上归属 root。在 NAS（TrueNAS/群晖/UNRAID 等）或多用户宿主机上，可通过 `PUID`/`PGID` 让容器以指定用户运行，使落盘文件归属该用户：

```bash
cat >> .env <<EOF
PUID=1000
PGID=1000
EOF
```

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

注意事项：

- **存量数据需先改归属**：从默认 root 切换到非 root 用户前，先在宿主机执行（路径按 `JCLOUD_DATA_PATH` 调整），否则容器启动后读写数据目录会因权限不足失败：

```bash
sudo chown -R 1000:1000 deploy/data/jcloud
```

- **GPU 硬解**：非 root 运行时需把宿主机 `/dev/dri` 节点的 video/render 组加入容器。先查询 gid：

```bash
stat -c '%g' /dev/dri/renderD128 /dev/dri/card0
```

  然后取消 `deploy/docker-compose.yml` 中 jcloud 服务 `group_add` 注释段的注释，并替换为查到的 gid。

> 该目录仅覆盖 jcloud 主服务；内置 postgres/redis 使用官方镜像自带的用户机制，无需指定。

---

## NVIDIA 硬件加速（可选）

jcloud 实时转码支持 NVENC/NVDEC 硬解（镜像内 ffmpeg 已内置支持，镜像无需改动），只需将宿主机 NVIDIA GPU 注入容器。

### 通用 Linux 宿主

1. 宿主机安装 NVIDIA 驱动与 [nvidia-container-toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)，按官方文档配置 Docker 运行时后重启 docker 服务。
2. 编辑 `deploy/docker-compose.yml`，取消 jcloud 服务中两处 NVIDIA 注释（`NVIDIA_DRIVER_CAPABILITIES` 环境变量与 `deploy` 资源块）。
3. 重启服务：

```bash
docker compose -f deploy/docker-compose.yml --profile db --profile cache up -d
```

4. 验证注入成功（能看到 GPU 列表即可）：

```bash
docker exec jcloud nvidia-smi
```

5. 登录 jcloud，在 系统 → 影视设置 中将硬解方式改为 NVENC。

> 硬解方式由系统首次启动时按 NVENC > QSV > VAAPI 优先级自动探测并持久化；部署完成后才启用 GPU 的，需在影视设置中手动修改。

### TrueNAS SCALE

24.10（Electric Eel）起无需手动安装 toolkit：在 Apps → Configure → Settings 勾选 **Install NVIDIA Drivers**，系统会自动安装 NVIDIA 驱动与容器工具包，然后按上文第 2~5 步操作即可。

### 无法安装 nvidia-container-toolkit 的受限系统

老版本 TrueNAS、部分锁死宿主环境的 NAS 系统无法安装 toolkit 时，可改为手动挂载驱动库与设备节点（等效 toolkit 的注入）：在 jcloud 服务中映射 `/dev/nvidia0`、`/dev/nvidiactl`、`/dev/nvidia-uvm`、`/dev/nvidia-uvm-tools` 设备节点，并将宿主机的 `libcuda.so.1`、`libnvidia-encode.so.1`、`libnvcuvid.so.1` 以只读方式挂载到容器 `/usr/lib/x86_64-linux-gnu/` 目录下。参考实现见仓库根目录 `start-docker.sh` 中的 NVIDIA 注入段。

局限：驱动库路径因发行版而异（先用 `readlink -f` 定位真实路径）；宿主机驱动升级后需确认库路径未变化；容器内无 `nvidia-smi`，需通过实际转码验证。

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
