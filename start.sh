#!/usr/bin/env bash

APP_PORT=8080
WEB_PORT=5173

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [[ -n "$pids" ]]; then
    echo "端口 $port 被占用，正在释放..."
    kill -9 $pids 2>/dev/null || true
  fi
}

kill_port "$APP_PORT"
kill_port "$WEB_PORT"

rm -f app.log web.log

nohup bash -c "cd app && mvn spring-boot:run" > app.log 2>&1 &
APP_PID=$!
echo $APP_PID > app.pid
echo "启动后端，端口: $APP_PORT, PID: $APP_PID"

nohup bash -c "cd web && pnpm run dev" > web.log 2>&1 &
WEB_PID=$!
echo $WEB_PID > web.pid
echo "启动前端，端口: $WEB_PORT, PID: $WEB_PID"

echo "服务已启动: 后端 PID $APP_PID (端口 $APP_PORT), 前端 PID $WEB_PID (端口 $WEB_PORT)"
