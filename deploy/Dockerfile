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

# 阶段 3：准备 JRE（与运行镜像同平台；jammy 的 glibc 2.35 可前向兼容 bookworm 的 2.36，temurin 无 Debian 变体）
FROM eclipse-temurin:25-jre-jammy AS jre-base

# 阶段 4：运行镜像
# 基础镜像为 Debian（caddy 官方镜像无 Debian 变体，Caddy 静态二进制从其 Alpine 镜像拷贝）；注入 Eclipse Temurin JRE
FROM debian:bookworm-slim
# Caddy 官方镜像（含 caddy:2-alpine）为 Alpine 且无 Debian 变体；其 /usr/bin/caddy 为全静态二进制，跨发行版可跑
COPY --from=caddy:2-alpine /usr/bin/caddy /usr/bin/caddy
ARG CN_MIRROR
ARG TARGETARCH
ARG JELLYFIN_FFMPEG_VERSION=7.1.4-3
COPY --from=jre-base /opt/java /opt/java
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
# UTF-8 locale（bookworm-slim 自带 C.UTF-8，无需装 locales 包）：JVM 的 sun.jnu.encoding 依赖 locale，
# 缺失时退化为 ASCII，中文文件名经 Java 文件系统 API 落盘会被替换为 ?
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
# jellyfin-ffmpeg 二进制不进 PATH，通过 Spring 宽松绑定注入 ffmpeg/ffprobe 路径（jcloud.media.ffmpeg-path/ffprobe-path）
ENV JCLOUD_MEDIA_FFMPEG_PATH=/usr/lib/jellyfin-ffmpeg/ffmpeg
ENV JCLOUD_MEDIA_FFPROBE_PATH=/usr/lib/jellyfin-ffmpeg/ffprobe

# 国内镜像加速：阿里云 Debian 镜像源（deb822 格式，文件存在才替换，防御基础镜像变化）
RUN if [ "$CN_MIRROR" = "true" ] && [ -f /etc/apt/sources.list.d/debian.sources ]; then \
        sed -i 's#deb.debian.org#mirrors.aliyun.com#g' /etc/apt/sources.list.d/debian.sources; \
    fi

# 安装 LibreOffice（Office 文档转 PDF 预览，nogui 变体专为 headless 转换设计）、中文字体（Noto CJK，避免转换后中文乱码）、mesa VAAPI 驱动（AMD 硬解/硬编）与 vainfo
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer-nogui \
        libreoffice-calc-nogui \
        libreoffice-impress-nogui \
        fonts-noto-cjk \
        mesa-va-drivers \
        vainfo \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 安装 jellyfin-ffmpeg（自带 Intel iHD 驱动、oneVPL GPU 运行时、NVENC 支持），版本由 ARG 固定；
# deb 由 release.sh 在构建前下载到 deploy/ 作为本地缓存（缺失时自动补下），此处直接 COPY 安装，不再在容器内 curl 下载；
# 上一层已清理 apt lists，安装 deb 前需重新 update 才能解析系统依赖
ARG JELLYFIN_FFMPEG_BASE_URL=https://repo.jellyfin.org/files/ffmpeg/debian/latest-7.x
COPY deploy/jellyfin-ffmpeg7_${JELLYFIN_FFMPEG_VERSION}-bookworm_${TARGETARCH}.deb /tmp/jellyfin-ffmpeg.deb
RUN apt-get update && apt-get install -y --no-install-recommends /tmp/jellyfin-ffmpeg.deb \
    && rm -f /tmp/jellyfin-ffmpeg.deb \
    && rm -rf /var/lib/apt/lists/*

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
