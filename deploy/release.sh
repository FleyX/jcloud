#!/usr/bin/env bash
#
# 构建 jcloud 镜像。
# --push 时自动从 GitHub 获取最新 tag 作为镜像版本标签，并同时打 latest 标签。
#
# 用法：
#   ./deploy/release.sh        # 构建当前平台镜像到本地（dev 标签），不推送
#   ./deploy/release.sh --push # 构建多架构镜像并推送到 DockerHub
#   ./deploy/release.sh --test # 构建多架构镜像，仅推送 test 标签到 DockerHub 作为测试镜像
#
# 环境变量：
#   DOCKERHUB_REPO    DockerHub 仓库名，默认 fleyx/jcloud
#   GITHUB_REPO       GitHub 仓库名（owner/repo），默认从 git remote 推断
#

set -euo pipefail

# 解析参数
PUSH=false
TEST=false
while [ $# -gt 0 ]; do
    case "$1" in
        --push)
            PUSH=true
            shift
            ;;
        --test)
            TEST=true
            shift
            ;;
        -h|--help)
            echo "用法："
            echo "  ./deploy/release.sh        # 构建当前平台镜像到本地（dev 标签），不推送"
            echo "  ./deploy/release.sh --push # 构建多架构镜像并推送到 DockerHub"
            echo "  ./deploy/release.sh --test # 构建多架构镜像，仅推送 test 标签到 DockerHub 作为测试镜像"
            exit 0
            ;;
        *)
            echo "错误：未知参数 $1"
            echo "使用 -h 或 --help 查看帮助"
            exit 1
            ;;
    esac
done

# 默认配置
if [ "${PUSH}" = true ] && [ "${TEST}" = true ]; then
    echo "错误：--push 与 --test 不能同时使用"
    exit 1
fi

DOCKERHUB_REPO="${DOCKERHUB_REPO:-fleyx/jcloud}"

echo "DockerHub 仓库: ${DOCKERHUB_REPO}"

# 获取 GitHub 最新 release tag
get_latest_github_tag() {
    local tag=""

    # 优先使用 gh CLI
    if command -v gh >/dev/null 2>&1; then
        tag=$(gh release view --repo "${GITHUB_REPO}" --json tagName -q .tagName 2>/dev/null || true)
    fi

    # 回退到 GitHub API
    if [ -z "${tag}" ]; then
        if command -v curl >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
            tag=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null | jq -r '.tag_name // empty' || true)
        fi
    fi

    # 再回退到本地 git tag
    if [ -z "${tag}" ]; then
        tag=$(git describe --tags --abbrev=0 2>/dev/null || true)
    fi

    echo "${tag}"
}

# 准备 jellyfin-ffmpeg deb 缓存：版本与下载源从 Dockerfile 的 ARG 解析（单一事实来源），
# 同名环境变量可覆盖；按目标架构检查 deploy/jellyfin-ffmpeg7_<版本>-bookworm_<arch>.deb，
# 缺失才从官方源下载到该目录作为宿主机缓存（多架构构建时 amd64/arm64 各缓存一份）
prepare_jellyfin_ffmpeg_debs() {
    local version base_url archs arch deb_file tmp_file

    version="${JELLYFIN_FFMPEG_VERSION:-$(grep -oP 'ARG JELLYFIN_FFMPEG_VERSION=\K.*' deploy/Dockerfile | head -n1 || true)}"
    base_url="${JELLYFIN_FFMPEG_BASE_URL:-$(grep -oP 'ARG JELLYFIN_FFMPEG_BASE_URL=\K.*' deploy/Dockerfile | head -n1 || true)}"

    if [ -z "${version}" ] || [ -z "${base_url}" ]; then
        echo "错误：无法从 deploy/Dockerfile 解析 JELLYFIN_FFMPEG_VERSION / JELLYFIN_FFMPEG_BASE_URL"
        exit 1
    fi

    # 目标架构：本地构建模式取宿主机架构；--push/--test 多架构构建为 amd64 + arm64
    if [ "${PUSH}" = true ] || [ "${TEST}" = true ]; then
        archs="amd64 arm64"
    else
        case "$(uname -m)" in
            x86_64) archs="amd64" ;;
            aarch64) archs="arm64" ;;
            *)
                echo "错误：不支持的宿主机架构 $(uname -m)，仅支持 x86_64/aarch64"
                exit 1
                ;;
        esac
    fi

    for arch in ${archs}; do
        deb_file="deploy/jellyfin-ffmpeg7_${version}-bookworm_${arch}.deb"
        if [ -s "${deb_file}" ]; then
            echo "jellyfin-ffmpeg deb 命中缓存: ${deb_file}"
            continue
        fi

        echo "jellyfin-ffmpeg deb 缺失，开始下载: ${base_url}/${arch}/jellyfin-ffmpeg7_${version}-bookworm_${arch}.deb"
        tmp_file="${deb_file}.tmp"
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL -o "${tmp_file}" "${base_url}/${arch}/jellyfin-ffmpeg7_${version}-bookworm_${arch}.deb"
        elif command -v wget >/dev/null 2>&1; then
            wget -q -O "${tmp_file}" "${base_url}/${arch}/jellyfin-ffmpeg7_${version}-bookworm_${arch}.deb"
        else
            echo "错误：需要 curl 或 wget 下载 jellyfin-ffmpeg deb"
            exit 1
        fi
        mv "${tmp_file}" "${deb_file}"
        echo "jellyfin-ffmpeg deb 下载完成: ${deb_file}"
    done
}

# 版本标签：仅 --push 时需要从 GitHub 获取最新 tag；--test 使用 test 标签；本地构建使用 dev 标签
if [ "${PUSH}" = true ]; then
    # 推断 GitHub 仓库名
    if [ -z "${GITHUB_REPO:-}" ]; then
        GITHUB_REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#^(https://github.com/|git@github.com:)([^/]+/[^.]+)(\.git)?$#\2#') || true
    fi

    if [ -z "${GITHUB_REPO:-}" ]; then
        echo "错误：无法推断 GitHub 仓库名，请通过 GITHUB_REPO 环境变量指定，例如 GITHUB_REPO=FleyX/jcloud"
        exit 1
    fi

    echo "GitHub 仓库: ${GITHUB_REPO}"

    LATEST_TAG=$(get_latest_github_tag)

    if [ -z "${LATEST_TAG}" ]; then
        echo "错误：无法从 GitHub 或本地 git 获取最新 tag"
        exit 1
    fi

    # 去除可能的前导 v
    VERSION_TAG="${LATEST_TAG#v}"

    echo "最新 GitHub tag: ${LATEST_TAG}"
    echo "镜像版本标签: ${VERSION_TAG}"
elif [ "${TEST}" = true ]; then
    echo "测试模式：仅推送 test 标签"
else
    echo "本地构建模式：使用 dev 标签"
fi

# 检查 docker login 状态
if ! docker info >/dev/null 2>&1; then
    echo "错误：Docker 守护进程未运行或当前用户无权限访问"
    exit 1
fi

# 构建前准备 jellyfin-ffmpeg deb 本地缓存（命中缓存/缺失下载均会打印日志）
prepare_jellyfin_ffmpeg_debs

# buildx 构造器仅在多架构构建（--push/--test）时需要
if [ "${PUSH}" = true ] || [ "${TEST}" = true ]; then
    if ! docker buildx inspect jcloud-builder >/dev/null 2>&1; then
        echo "创建 buildx 构造器 jcloud-builder..."
        docker buildx create --use --name jcloud-builder
    else
        docker buildx use jcloud-builder
    fi
fi

if [ "${TEST}" = true ]; then
    echo "开始构建并推送多架构测试镜像..."
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -f deploy/Dockerfile \
        -t "${DOCKERHUB_REPO}:test" \
        --push .

    echo "测试镜像推送完成："
    echo "  ${DOCKERHUB_REPO}:test"
elif [ "${PUSH}" = true ]; then
    echo "开始构建并推送多架构镜像..."
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -f deploy/Dockerfile \
        -t "${DOCKERHUB_REPO}:${VERSION_TAG}" \
        -t "${DOCKERHUB_REPO}:latest" \
        --push .

    echo "发布完成："
    echo "  ${DOCKERHUB_REPO}:${VERSION_TAG}"
    echo "  ${DOCKERHUB_REPO}:latest"
else
    echo "开始构建当前平台镜像到本地（不推送）..."
    # 本机未安装 buildx 插件，使用 dockerd 内置的 BuildKit（DOCKER_BUILDKIT=1），仅构建当前平台
    DOCKER_BUILDKIT=1 docker build \
        -f deploy/Dockerfile \
        -t "${DOCKERHUB_REPO}:dev" \
        .

    echo "构建完成（未推送）："
    echo "  ${DOCKERHUB_REPO}:dev"
    echo ""
    echo "如需推送到 DockerHub，请执行：./deploy/release.sh --push"
fi
