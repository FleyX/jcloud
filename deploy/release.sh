#!/usr/bin/env bash
#
# 发布 jcloud 镜像到 DockerHub。
# 自动从 GitHub 获取最新 tag 作为镜像版本标签，并同时推送 latest。
#
# 用法：
#   ./deploy/release.sh
#
# 环境变量：
#   DOCKERHUB_REPO    DockerHub 仓库名，默认 fleyx/jcloud
#   GITHUB_REPO       GitHub 仓库名（owner/repo），默认从 git remote 推断
#   DOCKERHUB_USER    DockerHub 用户名（可选，用于 docker login 提示）
#

set -euo pipefail

# 默认配置
DOCKERHUB_REPO="${DOCKERHUB_REPO:-fleyx/jcloud}"

# 推断 GitHub 仓库名
if [ -z "${GITHUB_REPO:-}" ]; then
    GITHUB_REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#^(https://github.com/|git@github.com:)([^/]+/[^.]+)(\.git)?$#\2#') || true
fi

if [ -z "${GITHUB_REPO:-}" ]; then
    echo "错误：无法推断 GitHub 仓库名，请通过 GITHUB_REPO 环境变量指定，例如 GITHUB_REPO=FleyX/jcloud"
    exit 1
fi

echo "GitHub 仓库: ${GITHUB_REPO}"
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

LATEST_TAG=$(get_latest_github_tag)

if [ -z "${LATEST_TAG}" ]; then
    echo "错误：无法从 GitHub 或本地 git 获取最新 tag"
    exit 1
fi

# 去除可能的前导 v
VERSION_TAG="${LATEST_TAG#v}"

echo "最新 GitHub tag: ${LATEST_TAG}"
echo "镜像版本标签: ${VERSION_TAG}"

# 检查 docker login 状态
if ! docker info >/dev/null 2>&1; then
    echo "错误：Docker 守护进程未运行或当前用户无权限访问"
    exit 1
fi

if ! docker buildx inspect jcloud-builder >/dev/null 2>&1; then
    echo "创建 buildx 构造器 jcloud-builder..."
    docker buildx create --use --name jcloud-builder
else
    docker buildx use jcloud-builder
fi

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
