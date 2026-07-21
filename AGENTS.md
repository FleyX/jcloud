# jcloud

高性能个人网盘。前后端分离：

- **后端**：`app/`，Spring Boot 4.1 + MyBatis-Plus + PostgreSQL + Flyway + redis
- **前端**：`web/`，Vite + Vue 3 + TypeScript + Pinia + Tailwind CSS + radix-vue + tailwind-merge

## 开发环境约定

- 任务完成后执行start.sh启动前后端。
- 数据库操作可直接使用 `psql` 命令。
- 开发环境管理员账户admin/admin
- mysql,redis等环境查看application-dev.yml,application-test.yml获取
## 快速启动

```bash
# 后端
cd app && mvn spring-boot:run

# 前端
cd web && pnpm dev
```

# 后端

## 技术栈

Spring Boot 4.1 + MyBatis-Plus + PostgreSQL + Hutool + Flyway

## Java 包结构

严格遵循下面的包结构规范：

```
com.fleyx.jcloud
│
├── config                 // 1. 配置包（Yml映射、Spring Bean注入、Security配置等）
│
├── filter                 // 2. 过滤器
│
├── controller             // 3. 路由/控制层（仅负责参数校验、路由分发、调用Service）
│
├── service                // 4. 业务逻辑接口层
│   ├── impl               //    - 业务逻辑实现类
│   └── support            //    - 跨实现类共享的支撑组件（FileNodeSupport、FilePathSupport、UserSpaceSupport 及 Trash* 回收站支撑组件）
│
├── mapper                 // 5. 数据访问层（MyBatis-Plus 的 Mapper 接口）
│
├── model                  // 6. 统一数据模型包
│   ├── dto                //    - 前端入参（UserSaveDto）
│   ├── vo                 //    - 返回前端的视图（UserVo）
│   ├── bo                 //    - Service 内部业务对象
│   ├── po                 //    - 严格与数据库表一一对应的实体类
│   └── convert            //    - MapStruct 转换接口
│
├── common                 // 7. 公共基础设施包
│   ├── exception          //    - 全局异常定义及异常处理器（GlobalExceptionHandler）
│   ├── constant           //    - 全局常量（或按业务拆分）
│   └── enums              //    - 全局或业务枚举
│
└── util                   // 8. 纯工具类（Hutool中没有的才需要自己编写）

resources/mapper 存放数据库 XML 文件
```

## 开发要求

1. 单个 Java 文件不要超过 400 行，单个方法不要超过 100 行。
2. 新增、修改代码时需要有对应的单元测试代码,单元测试配置文件为main目录下的application-test.yml
3. 工具类优先使用 Hutool 中的，文档：https://doc.hutool.cn/module/core/
4. 依赖包优先使用可用的最新版。
5. 代码设计遵循高内聚低耦合。
6. 使用 Flyway 管理数据库文件，每个开发任务使用一个 Flyway 迁移文件。
7. 数据库表以 `t_` 开头，例如 `t_user`。
8. 异常抛出规范：只有明确的因为业务规则拦截导致的才抛出BusinessException，因为各种调用报错导致的抛出SystemException，并且需要带上原异常
9. 后端不做数据格式化，所有格式化工作均在前端完成
10. 需求开发中如果存在ddl变更，必须要进行一次人工确认ddl内容，再开发
11. 所有数据库字段必须有注释，说明字段含义；
12. 禁止使用外键,存储过程，数据库不能包含业务逻辑
13. 必要的日志使用info打印，辅助排查问题的日志使用debug打印

## 并发与线程模型

后端已全局启用 Spring Boot 虚拟线程，以支撑 I/O 密集型场景下的高并发低内存目标。关键配置如下（详见 `app/src/main/resources/application.yml`）：

```yaml
spring:
  threads:
    virtual:
      enabled: true
server:
  tomcat:
    threads:
      max: 1000
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
```

开发约束：

- 新增阻塞 I/O 或第三方同步客户端时，必须先评估其是否会把虚拟线程钉在载体线程（pinning）上。
- 优先使用构造时初始化、`ReentrantLock` 或并发集合，避免在请求路径使用 `synchronized` 双检锁。
- 背景与取舍见 `docs/adr/0003-virtual-threads.md`。

## 测试与构建

- 单元测试：后端使用 JUnit 5（Spring Boot 默认）。
- 构建：`mvn clean package`

# 前端

在为本项目生成、重构或修改代码时，必须严格遵守以下定义的架构设计、布局规则和编码规范。

## 技术栈

Vite + Vue 3 + TypeScript + Pinia + Tailwind CSS + radix-vue + tailwind-merge

## 系统架构与布局控制

### 布局结构（自上而下的全局策略）

本应用采用平台级的多功能分层导航系统。除非有明确的特殊需求，否则不允许在组件内部创建自定义的全局滚动条。

* **视口限制（Viewport Restriction）：** 最外层容器必须严格占满屏幕 `100vh` 和 `100vw`，并设置 `overflow-hidden`，防止因滚动条导致页面整体抖动。
* **顶部导航栏（Header - 一级菜单）：** 负责全站核心模块的上下文切换。此状态必须通过 Pinia 统一管理。
* **左侧边栏（Sidebar - 二级菜单）：** 根据 Pinia 中当前激活的一级菜单，动态联动渲染对应的二级菜单列表。可包含全局资源微件（例如在 `files` 文件上下文中展示磁盘容量进度条）。
* **主内容区（`<main>`）：** 填充剩余的所有屏幕空间（`flex-1`）。必须包含 `overflow-y-auto`，以便让各个子模块（如文件网格、笔记编辑器）在其内部独立进行垂直滚动，而保持顶部栏和左侧栏固定不滚动。

## 代码编写硬性规范

### Vue 3 & TypeScript

- **禁止使用 Options API**，一律采用 `<script setup lang="ts">`。
- **严禁使用 `any` 类型**。所有后端返回的接口数据、组件 Props、自定义事件（Emits）必须定义明确的接口（`interface`）或类型（`type`）。
- **文件状态枚举**：涉及文件状态、上传状态时，必须使用合法的字面量联合类型（如 `status: 'waiting' | 'uploading' | 'paused' | 'success' | 'error'`）。

### 样式与 Tailwind CSS

- **严禁编写标准的 Style 标签 (`<style>`)**，除非是第三方库的特殊样式覆盖。所有样式必须通过 Tailwind CSS 的类名实现。
- **动态类名拼接**：当需要根据组件状态（如 `active`, `selected`, `disabled`）动态改变 Tailwind 类名时，**必须**使用项目自带的类名合并工具：

```typescript
import { cn } from '@/utils/cn' // 内部封装了 clsx 和 tailwind-merge
const className = cn('base-class', isActive && 'active-class')
```

## 其他规范

1. 单个 Vue 文件最大 500 行，超过 500 行进行组件拆分；可复用组件放到 `src/components`，非复用组件放到当前目录的 `components`。
2. 后端 `long` 类型返回前端会自动转换为 `string`，注意类型定义为 `string`。
3. 编写前端代码需考虑美观，简约风。
4. 除特殊说明外，前端只考虑页面权限，无须到按钮级别。
5. 禁止通过组件属性（Props）进行多层级的状态透传（Props Drilling），须使用 Pinia；单层级可使用 Props 传递。
6. 已统一展示请求异常，无需在业务代码中再次展示。

## Agent 技能配置

**全程使用中文交互**

### Issue tracker

使用 GitHub Issues，通过 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage 标签

使用默认五角色标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### 领域文档

单上下文仓库：读取根目录 `CONTEXT.md` 和 `docs/adr/`。详见 `docs/agents/domain.md`。
