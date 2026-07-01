#!/bin/sh
set -e

APP_PORT="${JCLOUD_APP_PORT:-8080}"
CADDY_PORT="${JCLOUD_PORT:-80}"

echo "Starting jcloud backend on port ${APP_PORT}..."
java -jar /app/app.jar --server.port="${APP_PORT}" &

echo "Starting Caddy on port ${CADDY_PORT}..."
caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
