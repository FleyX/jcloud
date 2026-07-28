# syntax=docker/dockerfile:1

# 是否使用国内镜像加速（Maven/Alpine/npm）。
# 本地构建默认开启；CI（GitHub Actions）构建时通过 --build-arg CN_MIRROR=false 关闭。
ARG CN_MIRROR=true

# 阶段 1：构建后端
# 使用 BUILDPLATFORM 加速跨平台构建（产物为平台无关的字节码）
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25-alpine AS backend-builder
ARG CN_MIRROR
WORKDIR /build

# 国内镜像加速：阿里云 Maven 镜像（不放在 /root/.m2，避免被缓存挂载遮蔽）
COPY deploy/maven-settings.xml /opt/maven-settings.xml

# 先复制 pom.xml 并下载依赖，充分利用缓存层
COPY app/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    MAVEN_OPTS="" && \
    if [ "$CN_MIRROR" = "true" ]; then MAVEN_OPTS="-s /opt/maven-settings.xml"; fi && \
    mvn $MAVEN_OPTS dependency:go-offline -q

# 再复制源码构建；依赖未变化时不会重新下载
COPY app/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    MAVEN_OPTS="" && \
    if [ "$CN_MIRROR" = "true" ]; then MAVEN_OPTS="-s /opt/maven-settings.xml"; fi && \
    mvn $MAVEN_OPTS clean package -DskipTests

# 阶段 2：构建前端
# 产物为静态文件，同样可在任意平台运行
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend-builder
ARG CN_MIRROR
ENV CI=true
# 国内镜像加速：corepack 下载 pnpm 及依赖安装走 npmmirror
RUN if [ "$CN_MIRROR" = "true" ]; then \
        export COREPACK_NPM_REGISTRY=https://registry.npmmirror.com; \
    fi && \
    corepack enable && corepack prepare pnpm@latest --activate && \
    if [ "$CN_MIRROR" = "true" ]; then \
        pnpm config set registry https://registry.npmmirror.com; \
    fi
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
ARG CN_MIRROR
COPY --from=jre-base /opt/java /opt/java
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# 国内镜像加速：阿里云 Alpine 镜像源
RUN if [ "$CN_MIRROR" = "true" ]; then \
        sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories; \
    fi

# 安装 LibreOffice（Office 文档转 PDF 预览）与中文字体（Noto CJK，避免转换后中文乱码）
RUN apk add --no-cache libreoffice font-noto-cjk

# 安装 ffmpeg（视频转码、缩略图、字幕提取、ffprobe 探测）
RUN apk add --no-cache ffmpeg

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
