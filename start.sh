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

# 使用增量编译保证速度。MapStruct 生成的 Impl 在以下场景会缺失：
# 源码无改动但 Impl 类被删（如构建中断、IDE 与命令行混用），此时增量编译不会
# 重新生成，启动报 No qualifying bean of type ...Convert。
# 检测每个 Convert 接口是否有对应的 Impl 类，缺失时才 touch 接口源码强制重编重建。
CONVERT_SRC_DIR="$SCRIPT_DIR/app/src/main/java/com/fleyx/jcloud/model/convert"
CONVERT_CLS_DIR="$SCRIPT_DIR/app/target/classes/com/fleyx/jcloud/model/convert"

need_regen=0
if [[ ! -d "$CONVERT_CLS_DIR" ]]; then
  need_regen=1
else
  for src in "$CONVERT_SRC_DIR"/*Convert.java; do
    name=$(basename "$src" .java)
    if [[ ! -f "$CONVERT_CLS_DIR/${name}Impl.class" ]]; then
      need_regen=1
      break
    fi
  done
fi

if [[ $need_regen -eq 1 ]]; then
  echo "检测到 MapStruct Impl 缺失，强制重新生成..."
  rm -rf "$SCRIPT_DIR/app/target/generated-sources/annotations"
  touch "$CONVERT_SRC_DIR"/*Convert.java
fi

nohup bash -c "cd \"$SCRIPT_DIR/app\" && mvn compile spring-boot:run" > app.log 2>&1 &
APP_PID=$!
echo $APP_PID > app.pid
echo "启动后端，端口: $APP_PORT, PID: $APP_PID"

nohup bash -c "cd \"$SCRIPT_DIR/web\" && pnpm run dev" > web.log 2>&1 &
WEB_PID=$!
echo $WEB_PID > web.pid
echo "启动前端，端口: $WEB_PORT, PID: $WEB_PID"

# 等待后端启动完毕（mvn clean compile + 应用启动可能需要较长时间）
echo "等待后端就绪..."
for i in $(seq 1 300); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "后端进程已退出，启动失败，请查看 app.log" >&2
    exit 1
  fi
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$APP_PORT" 2>/dev/null; then
    echo "后端已就绪，耗时 ${i}s"
    break
  fi
  if [[ $i -eq 300 ]]; then
    echo "等待后端就绪超时（300s），请查看 app.log" >&2
    exit 1
  fi
  sleep 1
done

echo "服务已启动: 后端 PID $APP_PID (端口 $APP_PORT), 前端 PID $WEB_PID (端口 $WEB_PORT)"
