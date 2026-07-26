# syntax=docker/dockerfile:1

# 阶段 1：构建后端
# 使用 BUILDPLATFORM 加速跨平台构建（产物为平台无关的字节码）
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25-alpine AS backend-builder
WORKDIR /build

# 先复制 pom.xml 并下载依赖，充分利用缓存层
COPY app/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

# 再复制源码构建；依赖未变化时不会重新下载
COPY app/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

# 阶段 2：构建前端
# 产物为静态文件，同样可在任意平台运行
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend-builder
ENV CI=true
RUN corepack enable && corepack prepare pnpm@latest --activate
WORKDIR /build

# 先复制依赖描述文件并安装，充分利用缓存层
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

# 再复制源码构建；依赖未变化时不会重新安装
COPY web/ ./
RUN pnpm build

# 阶段 3：准备 JRE（与运行镜像同平台）
FROM eclipse-temurin:25-jre-alpine AS jre-base

# 阶段 4：运行镜像
# 使用官方 Caddy 镜像作为基础，并注入 Eclipse Temurin JRE
FROM caddy:2-alpine
COPY --from=jre-base /opt/java /opt/java
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# 安装 LibreOffice（Office 文档转 PDF 预览）与中文字体（Noto CJK，避免转换后中文乱码）
RUN apk add --no-cache libreoffice font-noto-cjk

WORKDIR /app

# 复制后端 jar、前端静态资源、Caddy 配置与启动脚本
COPY --from=backend-builder /build/target/*.jar ./app.jar
COPY --from=frontend-builder /build/dist /srv/web
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY deploy/start.sh /app/start.sh

RUN chmod +x /app/start.sh

# 默认暴露 80，实际监听端口由环境变量 JCLOUD_PORT 控制
EXPOSE 80

CMD ["/app/start.sh"]
