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

echo "启动后端（端口 $APP_PORT）..."
nohup bash -c "cd app && mvn spring-boot:run" > app.log 2>&1 &
echo $! > app.pid

echo "后端 PID: $(cat app.pid)，日志: app.log"
