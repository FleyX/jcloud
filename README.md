# jcloud

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)

**[部署文档](./deploy.md)**

## 简介

jcloud 是一个面向个人的高性能网盘系统，支持文件上传、下载、浏览、组织、分享等核心能力。项目采用前后端分离设计，后端基于 Spring Boot 虚拟线程支撑高并发 I/O，前端基于 Vite + Vue 3 构建，同时适配 PC 与移动端访问。

## 核心特性

- **文件管理**：上传、下载、浏览、移动、重命名、复制、删除、回收站。
- **分片上传**：默认 10MB 分片，支持断点续传与秒传去重。
- **秒传去重**：基于文件身份 Hash，同一用户内通过硬链接复用物理文件。
- **文件夹上传**：通过浏览器原生能力上传整个文件夹，保持层级结构。
- **冲突处理**：上传、恢复、移动、重命名、复制支持“跳过 / 覆盖 / 自动重命名”两阶段冲突解决。
- **回收站**：删除后保留 7 天，支持还原到原始路径。
- **公开分享**：生成带密码与有效期的分享链接，支持公开浏览与下载。
- **多端适配**：PC 端采用顶部导航 + 左侧边栏布局；移动端采用底部 TabBar + 顶部抽屉布局。
- **存储空间管理**：每用户绑定独立存储空间，支持扩容与迁移。

## 技术栈

### 后端（`app/`）

- Spring Boot 4.1
- MyBatis-Plus
- PostgreSQL + Flyway
- Redis + Redisson
- Hutool、MapStruct、JJWT
- Java 25 + Spring Boot 虚拟线程

### 前端（`web/`）

- Vite + Vue 3 + TypeScript
- Pinia
- Tailwind CSS + radix-vue + tailwind-merge
- Vue Router 5
- Vitest

## 快速开始

### 环境要求

- JDK 25+
- Maven 3.9+
- Node.js 22+ + pnpm
- PostgreSQL 15+
- Redis 7+

### 一键启动

```bash
./start.sh
```

该脚本会释放 8080 与 5173 端口，并以后台方式启动前后端服务。

### 手动启动

```bash
# 后端
cd app && mvn spring-boot:run

# 前端
cd web && pnpm dev
```

前端默认访问地址：http://localhost:5173  
后端默认访问地址：http://localhost:8080

## 项目结构

```
jcloud
├── app/              # 后端 Spring Boot 应用
│   └── src/main/java/com/fleyx/jcloud
│       ├── common/   # 异常、常量、枚举、工具
│       ├── config/   # 配置类
│       ├── filter/   # 过滤器
│       ├── controller/   # 控制层
│       ├── service/      # 业务逻辑
│       ├── mapper/       # 数据访问
│       └── model/        # DTO / VO / BO / PO / Convert
├── web/              # 前端 Vue 3 应用
│   └── src
│       ├── api/      # 接口请求
│       ├── components/   # 组件
│       ├── router/       # 路由
│       ├── store/        # Pinia 状态
│       ├── views/        # 页面视图
│       └── utils/        # 工具函数
└── docs/             # 架构决策与文档
    ├── adr/          # 架构决策记录
    ├── agents/       # Agent 协作规范
    └── prd/          # 产品需求文档
```

## 文档

- [开发规范与指南](./develop.md)
- [领域术语表](./CONTEXT.md)
- [架构决策记录](./docs/adr/)

## 许可证

[GNU Affero General Public License v3.0](LICENSE)

Copyright (C) 2026 fanxb
