# jcloud 开发指南

本文档面向参与 jcloud 开发的工程师，说明环境搭建、代码规范、架构约定与提交流程。

## 目录

- [环境准备](#环境准备)
- [项目启动](#项目启动)
- [代码规范](#代码规范)
- [架构约定](#架构约定)
- [数据库与迁移](#数据库与迁移)
- [测试](#测试)
- [提交与协作](#提交与协作)

---

## 环境准备

### 必需依赖

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | 25+ | 后端编译运行 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 22+ | 前端运行 |
| pnpm | 9+ | 前端包管理 |
| PostgreSQL | 15+ | 主数据库 |
| Redis | 7+ | 缓存与分布式锁 |

### 数据库初始化

1. 创建应用数据库：

```sql
CREATE DATABASE jcloud WITH ENCODING = 'UTF8';
CREATE DATABASE jcloud_test WITH ENCODING = 'UTF8';
```

2. 后端使用 Flyway 自动执行迁移脚本，首次启动时会在 `app/src/main/resources/db/migration` 目录下按版本顺序应用。

3. 配置文件位置：

- 通用配置：`app/src/main/resources/application.yml`
- 开发配置：`app/src/main/resources/application-dev.yml`
- 测试配置：`app/src/main/resources/application-test.yml`

开发环境默认连接 `jdbc:postgresql://dev.lan:5432/jcloud`，请按本地环境修改 `application-dev.yml`。

---

## 项目启动

### 一键启动（推荐）

```bash
./start.sh
```

脚本会清理 8080、5173 端口占用，并分别在 `app.log`、`web.log` 输出日志。

### 手动启动

```bash
# 终端 1：后端
cd app && mvn spring-boot:run

# 终端 2：前端
cd web && pnpm dev
```

### 常用命令

```bash
# 后端测试
cd app && mvn test

# 后端打包
cd app && mvn clean package

# 前端依赖安装
cd web && pnpm install

# 前端构建
cd web && pnpm build

# 前端测试
cd web && pnpm test

# 前端 lint
cd web && pnpm lint
```

---

## 代码规范

### 后端（Java）

- 单个 Java 文件不超过 400 行，单个方法不超过 100 行。
- 优先使用 Hutool 工具类，避免重复造轮子。
- 工具类放在 `com.fleyx.jcloud.util`，仅当 Hutool 不满足时才自行编写。
- 异常区分：
  - 业务规则拦截 → `BusinessException`
  - 调用报错、系统异常 → `SystemException`，必须带上原异常
- 后端不做数据格式化，日期、大小、百分比等格式化统一在前端完成。
- 禁止使用外键、存储过程，数据库不包含业务逻辑。
- 数据库表统一以 `t_` 开头，例如 `t_user`、`t_file_node`。
- 所有数据库字段必须有注释说明含义。
- 新增或修改代码必须补充对应单元测试。

### 前端（Vue 3 + TypeScript）

- 统一使用 `<script setup lang="ts">`，禁止使用 Options API。
- 严禁使用 `any` 类型；Props、Emits、API 返回数据必须定义接口或类型。
- 状态枚举使用字面量联合类型，例如：

```typescript
type UploadStatus = 'waiting' | 'uploading' | 'paused' | 'success' | 'error'
```

- 样式必须使用 Tailwind CSS 类名实现，禁止写标准 `<style>` 标签（第三方库覆盖除外）。
- 动态类名拼接使用 `cn` 工具：

```typescript
import { cn } from '@/utils/cn'
const className = cn('base-class', isActive && 'active-class')
```

- 单个 Vue 文件不超过 500 行，超出需拆分组件。
- 禁止跨层级 Props Drilling，多层级状态统一使用 Pinia。
- 后端 `long` 类型返回前端会自动转为 `string`，前端类型定义需使用 `string`。
- 请求异常已统一处理，业务代码中无需重复展示。

---

## 架构约定

### 后端包结构

```
com.fleyx.jcloud
├── common          # 异常、常量、枚举、上下文、工具
├── config          # 配置类
├── filter          # 过滤器
├── controller      # 控制层：参数校验、路由分发、调用 Service
├── service         # 业务接口
│   └── impl        # 业务实现
├── mapper          # MyBatis-Plus Mapper
├── model           # 数据模型
│   ├── dto         # 前端入参
│   ├── vo          # 返回视图
│   ├── bo          # 内部业务对象
│   ├── po          # 数据库实体
│   └── convert     # MapStruct 转换接口
└── util            # 纯工具类
```

### 前端目录结构

```
web/src
├── api             # 接口请求
├── assets          # 静态资源与样式
├── components      # 可复用组件
├── composables     # 组合式函数
├── directives      # 自定义指令
├── hooks           # 业务 Hooks
├── router          # 路由配置
├── store           # Pinia Store
├── types           # 类型定义
├── utils           # 工具函数
└── views           # 页面视图
    ├── pc          # PC 端视图
    └── mobile      # 移动端视图
```

### 并发与线程模型

后端已启用 Spring Boot 虚拟线程：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

开发约束：

- 新增阻塞 I/O 或第三方同步客户端时，先评估是否会将虚拟线程钉在载体线程上。
- 优先使用构造时初始化、`ReentrantLock` 或并发集合。
- 避免在请求路径使用 `synchronized` 双检锁。

更多背景见 [docs/adr/0003-virtual-threads.md](./docs/adr/0003-virtual-threads.md)。

---

## 数据库与迁移

- 使用 Flyway 管理数据库版本，迁移文件位于 `app/src/main/resources/db/migration/`。
- 每个开发任务使用一个 Flyway 迁移文件，命名格式：

```
V{版本号}__{描述}.sql
```

例如：`V1.0.0__init_schema.sql`。

- 需求开发中如果存在 DDL 变更，必须先人工确认 DDL 内容，再进行开发。
- 禁止在迁移文件中编写业务逻辑，仅包含结构变更与必要的基础数据。

---

## 测试

### 后端测试

- 使用 JUnit 5（Spring Boot 默认）。
- 测试配置文件使用 `app/src/main/resources/application-test.yml`。
- 新增/修改代码需同步补充单元测试。

```bash
cd app && mvn test
```

### 前端测试

- 使用 Vitest。

```bash
cd web && pnpm test
```

---

## 提交与协作

### 分支管理

- 功能开发从主分支切出特性分支。
- 分支命名建议：`feature/{模块}-{简述}`、`fix/{问题简述}`。

### 提交信息

- 使用中文或英文均可，但需在同一仓库内保持一致。
- 提交信息应简明描述本次改动的范围与意图。

### 代码审查

- 合并前至少通过一次本地构建与测试。
- 后端 `mvn clean package` 与前端 `pnpm build` 均需成功。

### 相关文档

- [领域术语表](./CONTEXT.md)
- [架构决策记录](./docs/adr/)
- [Agent 协作规范](./docs/agents/)
