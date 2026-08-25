#!/usr/bin/env bash
#
# 一键本地 Docker 测试：构建 dev 镜像并启动测试容器，直连本机 postgres/redis（与 application-dev.yml 一致）。
# 数据落盘在 dev 库主存储空间路径（t_storage_space.path）对应的宿主机目录，以 rslave 传播挂入容器：
# 该目录下的子挂载（如 files/admin/动漫 的 NFS 挂载，含容器启动后新出现的）自动传入容器。
# 容器进程以 uid:gid 1000:1000 运行（与宿主机当前用户一致），写入存储空间/NFS 的文件归属正确。
#
# 环境变量：
#   STORAGE_PATH  主存储空间路径（宿主机目录，1:1 挂载进容器）。已设置时直接使用，
#                 否则从 dev 库 t_storage_space 查询主存储空间路径；psql 不可用或查不到则报错退出。
#
# 清理测试容器：docker rm -f jcloud-docker-test

set -euo pipefail

# 获取脚本所在目录（兼容 macOS 和 Linux）
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

CONTAINER_NAME=jcloud-docker-test
PORT=8088
JWT_SECRET="change-me-in-production-jcloud-secret-key-2026"

# 1. 构建 dev 镜像（release.sh 本地模式，自动享受 ping 探测与 jellyfin-ffmpeg deb 缓存）
"$SCRIPT_DIR/deploy/release.sh"

# 2. 主存储空间路径：环境变量 STORAGE_PATH 优先；否则从 dev 库查询主存储空间
if [[ -z "${STORAGE_PATH:-}" ]]; then
  if ! command -v psql >/dev/null 2>&1; then
    echo "错误：未找到 psql，无法查询主存储空间路径，请通过 STORAGE_PATH 环境变量指定" >&2
    exit 1
  fi
  STORAGE_PATH=$(PGPASSWORD=postgres psql -h localhost -p 5432 -U postgres -d jcloud -tAc \
    "SELECT path FROM t_storage_space WHERE is_primary=1 AND delete_at=0 LIMIT 1" 2>/dev/null) || true
  if [[ -z "${STORAGE_PATH}" ]]; then
    echo "错误：未能从 dev 库查询到主存储空间路径，请通过 STORAGE_PATH 环境变量指定" >&2
    exit 1
  fi
  echo "从 dev 库查询到主存储空间路径: $STORAGE_PATH"
else
  echo "使用 STORAGE_PATH 指定的存储空间路径: $STORAGE_PATH"
fi

# 3. 清理旧容器（不存在时忽略）
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

# 4. 存在 /dev/dri 时映射 GPU 渲染节点
DEVICE_ARGS=()
if [[ -e /dev/dri ]]; then
  echo "检测到 /dev/dri，映射 GPU 渲染节点"
  DEVICE_ARGS+=(--device /dev/dri:/dev/dri)
fi

# 5. 启动容器：--network host 直连本机 postgres/redis，prod profile
# --user 1000:1000 与宿主机当前用户一致，写入存储空间/NFS 的文件归属正确；
# 容器内无 uid 1000 的 passwd 条目，HOME=/tmp 供 caddy/LibreOffice 等写入；
# --group-add 983/987 为宿主机 /dev/dri 的 video/render 组 gid，保证非 root 下 GPU 可访问
echo "启动容器 $CONTAINER_NAME ..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --network host \
  --user 1000:1000 \
  --group-add 983 \
  --group-add 987 \
  -e HOME=/tmp \
  -e JCLOUD_PORT="$PORT" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/jcloud" \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e REDIS_HOST=localhost \
  -e REDIS_PORT=6379 \
  -e JCLOUD_JWT_SECRET="$JWT_SECRET" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
  --mount "type=bind,source=${STORAGE_PATH},target=${STORAGE_PATH},bind-propagation=rslave" \
  ${DEVICE_ARGS[@]+"${DEVICE_ARGS[@]}"} \
  fleyx/jcloud:dev

# 6. 等待服务就绪（60s 超时），失败输出容器日志提示
echo "等待服务就绪 (http://127.0.0.1:$PORT)..."
for i in $(seq 1 60); do
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT" 2>/dev/null; then
    echo "服务已就绪，耗时 ${i}s"
    break
  fi
  if [[ $i -eq 60 ]]; then
    echo "等待服务就绪超时（60s），最近容器日志：" >&2
    docker logs --tail 50 "$CONTAINER_NAME" >&2 || true
    exit 1
  fi
  sleep 1
done

echo ""
echo "jcloud Docker 测试环境已就绪："
echo "  访问地址: http://localhost:$PORT"
echo "  查看日志: docker logs -f $CONTAINER_NAME"
echo "  清理容器: docker rm -f $CONTAINER_NAME"