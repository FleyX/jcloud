# jcloud 部署指南

## 部署要求

- Docker Engine 24.0+
- Docker Compose v2.20+

## 快速部署

```bash
git clone <仓库地址> jcloud
cd jcloud
docker compose -f deploy/docker-compose.yml up -d
```

一条命令启动 jcloud 与内置 PostgreSQL、Redis。等待健康检查通过后访问 `http://<服务器IP>`（默认 80 端口），首次访问会引导完成系统初始化。

需要改端口、数据目录或运行用户时，在 `deploy/` 下创建 `.env` 覆盖下表变量即可（与 compose 文件同目录）。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JCLOUD_PORT` | `80` | 对外访问端口 |
| `JCLOUD_DATA_PATH` | `./data` | 数据存放目录，相对 `deploy/`，即 `deploy/data` |
| `PUID` | `0` | 容器运行用户 uid（详见附录 B） |
| `PGID` | `0` | 容器运行用户组 gid（详见附录 B） |
| `TZ` | `Asia/Shanghai` | 容器时区，设置宿主机 `TZ` 时透传 |

其余为连接类变量（`SPRING_DATASOURCE_*`、`REDIS_*`），仅使用外部数据库/Redis 时需要，见附录 A。

## 数据持久化

默认数据目录为 `deploy/data`：

```
deploy/data/
├── jcloud/              # 用户文件数据
├── system/              # 密钥（token 签名、远程存储凭据加密）
├── system-cache/        # 系统缓存（全部可再生成，可整体清空）
│   ├── previews/        # 预览图（图片、文本、Office 转换 PDF）
│   ├── media/subtitles/ # 字幕缓存
│   ├── media/transcode/ # 转码切片与播放会话
│   └── zip-tasks/       # ZIP 打包临时文件
├── postgres/            # 内置 PostgreSQL 数据
└── redis/               # 内置 Redis 数据
```

- `system/`：密钥首次启动自动生成，**不可再生成**。删除或丢失会导致全部登录态失效、远程挂载凭据不可解密（需重新登录并重新填写凭据），务必纳入备份。
- `system-cache/`：内容全部可再生成。磁盘紧张时可直接清空整个目录，系统会在后续预览、播放、下载时按需重建，不影响用户文件。

数据目录可通过 `JCLOUD_DATA_PATH` 修改，例如放到项目根目录：`JCLOUD_DATA_PATH=../data`。

## 升级与维护

### 升级版本

```bash
docker compose -f deploy/docker-compose.yml down
docker compose -f deploy/docker-compose.yml up -d
```

> Flyway 会在后端启动时自动执行数据库迁移。

### 查看日志

```bash
docker logs -f jcloud           # jcloud 主服务
docker logs -f jcloud-postgres  # PostgreSQL
docker logs -f jcloud-redis     # Redis
```

### 停止服务

```bash
docker compose -f deploy/docker-compose.yml down
```

## 附录 A：使用外部 PostgreSQL / Redis

已有 PostgreSQL 与 Redis 时，用 `.env` 指定连接信息，并只启动 jcloud 主服务：

```bash
cat > deploy/.env <<EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://your-db-host:5432/jcloud
SPRING_DATASOURCE_USERNAME=jcloud
SPRING_DATASOURCE_PASSWORD=YourStrongPassword
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_DB=0
EOF
```

```bash
docker compose -f deploy/docker-compose.yml up -d jcloud
```

## 附录 B：指定运行用户（PUID/PGID）

默认容器内进程以 root 运行，bind mount 数据目录中的文件在宿主机上归属 root。在 NAS（TrueNAS/群晖/UNRAID 等）或多用户宿主机上，可通过 `PUID`/`PGID` 让容器以指定用户运行：

```bash
cat >> deploy/.env <<EOF
PUID=1000
PGID=1000
EOF
docker compose -f deploy/docker-compose.yml up -d
```

注意事项：

- **存量数据需先改归属**：从默认 root 切换到非 root 前，先在宿主机执行（路径按 `JCLOUD_DATA_PATH` 调整；不要改动 `postgres/`、`redis/`）：

```bash
sudo chown -R 1000:1000 deploy/data/jcloud deploy/data/system deploy/data/system-cache
```

- **GPU 硬解**：非 root 运行时需把宿主机 `/dev/dri` 节点的 video/render 组加入容器。先用 `stat -c '%g' /dev/dri/renderD128 /dev/dri/card0` 查询 gid，再取消 `deploy/docker-compose.yml` 中 jcloud 服务 `group_add` 注释段并替换为查到的 gid。

> 该配置仅作用于 jcloud 主服务；内置 postgres/redis 使用官方镜像自带的用户机制，无需指定。

## 附录 C：NVIDIA 硬件加速（可选）

jcloud 实时转码支持 NVENC/NVDEC 硬解（镜像内 ffmpeg 已内置支持，镜像无需改动），只需将宿主机 NVIDIA GPU 注入容器。

### 通用 Linux 宿主

1. 宿主机安装 NVIDIA 驱动与 [nvidia-container-toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)，按官方文档配置 Docker 运行时后重启 docker 服务。
2. 编辑 `deploy/docker-compose.yml`，取消 jcloud 服务中两处 NVIDIA 注释（`NVIDIA_DRIVER_CAPABILITIES` 环境变量与 `deploy` 资源块）。
3. 重启服务：`docker compose -f deploy/docker-compose.yml up -d`
4. 验证注入成功（能看到 GPU 列表即可）：`docker exec jcloud nvidia-smi`
5. 登录 jcloud，在 系统 → 影视设置 中将硬解方式改为 NVENC。

> 硬解方式由系统首次启动时按 NVENC > QSV > VAAPI 优先级自动探测并持久化；部署完成后才启用 GPU 的，需在影视设置中手动修改。

### TrueNAS SCALE

24.10（Electric Eel）起无需手动安装 toolkit：在 Apps → Configure → Settings 勾选 **Install NVIDIA Drivers**，系统会自动安装 NVIDIA 驱动与容器工具包，然后按上文第 2~5 步操作即可。

### 无法安装 nvidia-container-toolkit 的受限系统

老版本 TrueNAS、部分锁死宿主环境的 NAS 系统无法安装 toolkit 时，可改为手动挂载驱动库与设备节点（等效 toolkit 的注入）：在 jcloud 服务中映射 `/dev/nvidia0`、`/dev/nvidiactl`、`/dev/nvidia-uvm`、`/dev/nvidia-uvm-tools` 设备节点，并将宿主机的 `libcuda.so.1`、`libnvidia-encode.so.1`、`libnvcuvid.so.1` 以只读方式挂载到容器 `/usr/lib/x86_64-linux-gnu/` 目录下。参考实现见仓库根目录 `start-docker.sh` 中的 NVIDIA 注入段。

局限：驱动库路径因发行版而异（先用 `readlink -f` 定位真实路径）；宿主机驱动升级后需确认库路径未变化；容器内无 `nvidia-smi`，需通过实际转码验证。

## 附录 D：HTTPS 与反向代理

jcloud 容器内以纯 HTTP 运行，公网 HTTPS 由外部反向代理（如 Nginx Proxy Manager、Traefik、Cloudflare 等）卸载 TLS：

```
浏览器 --HTTPS--> 外部代理（卸载 TLS）--HTTP--> jcloud 容器
```

前置要求：

- **必须透传 `X-Forwarded-For`**（主流反代默认透传）。否则登录、分享、WebDAV 的 IP 限流会按代理 IP 计数，可能误伤全部用户。
- **必须透传 `X-Forwarded-Proto`**。jcloud 依据该头判定请求是否为 HTTPS，决定认证 cookie 是否带 `Secure`；未透传时 cookie 不带 `Secure`，功能可用但属于安全降级。
- HSTS 等 TLS 相关安全头由外部代理设置。

内置的 Caddy 已自动透传上述请求头；纯内网 HTTP 部署无需外部代理，直接访问即可。

## 常见问题

### 1. 容器启动后无法访问

- 检查防火墙是否放行 `JCLOUD_PORT`。
- 查看日志确认后端是否已正常启动：`docker logs -f jcloud`。
- 确认容器健康状态：`docker compose -f deploy/docker-compose.yml ps`。

### 2. 使用外部数据库时连接失败

- 确保 `SPRING_DATASOURCE_URL` 中的主机名在容器内可解析。
- 若使用宿主机上的数据库，可将 URL 中的主机设为 `host.docker.internal`（Linux 需 Docker 20.10+ 并启用 host gateway）。
