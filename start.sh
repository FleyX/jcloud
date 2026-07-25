#!/usr/bin/env bash

APP_PORT=8080
WEB_PORT=5173

# 兼容 macOS 和 Linux 获取脚本所在目录
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

kill_port() {
  local port=$1
  local pids=""

  if command -v lsof >/dev/null 2>&1; then
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  elif command -v ss >/dev/null 2>&1; then
    pids=$(ss -ltnp "sport = :$port" 2>/dev/null | awk 'NR>1 {for(i=1;i<=NF;i++) if($i ~ /pid=/) {split($i,a,"pid="); print a[2]}}' | sort -u || true)
  fi

  if [[ -n "$pids" ]]; then
    echo "端口 $port 被占用，正在释放..."
    echo "$pids" | xargs kill -9 2>/dev/null || true
  fi
}

cd "$SCRIPT_DIR" || exit 1
rm -f app.pid web.pid app.log web.log

kill_port "$APP_PORT"
kill_port "$WEB_PORT"

# clean compile 避免增量编译导致 MapStruct 生成的 Impl 残缺（No qualifying bean of type ...Convert）
nohup bash -c "cd \"$SCRIPT_DIR/app\" && mvn clean compile spring-boot:run" > app.log 2>&1 &
APP_PID=$!
echo $APP_PID > app.pid
echo "启动后端，端口: $APP_PORT, PID: $APP_PID"

nohup bash -c "cd \"$SCRIPT_DIR/web\" && pnpm run dev" > web.log 2>&1 &
WEB_PID=$!
echo $WEB_PID > web.pid
echo "启动前端，端口: $WEB_PORT, PID: $WEB_PID"

echo "服务已启动: 后端 PID $APP_PID (端口 $APP_PORT), 前端 PID $WEB_PID (端口 $WEB_PORT)"
