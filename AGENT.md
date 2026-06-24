当你使用浏览器调试skill时，无须启动前端服务，使用现有即可，后端需要你手动启动。
如果需要操作数据库可使用psql bash命令

# 后端

## 技术栈

SpringBoot4 + MybatisPlus + PostgreSQL + Hutools core + flyway

## java包结构

严格遵循下面的包结构规范

```
com.fleyx.jcloud
│
├── config                 // 1. 配置包（Yml映射、Spring Bean注入、Security配置等）
│
├── filter                 // 过滤器
│
├── config                 // 1. 配置包（Yml映射、Spring Bean注入、Security配置等）
│
├── controller             // 2. 路由/控制层（仅负责参数校验、路由分发、调用Service）
│
├── model                  // 3. 统一数据模型包
│   ├── dto                //    - 前端入参（UserSaveDto)
│   ├── vo                 //    - 返回前端的视图（UserVo）
│   ├── bo                 //    - Service 内部业务对象
│   ├── po                 //    - 严格与数据库表一一对应的实体类
│   └── convert            //    - MapStruct 转换接口
│
├── service                //  业务逻辑接口层(UserService)
│   └── impl               //    - 业务逻辑实现类(UserServiceImpl)
│
├── mapper                 //  数据访问层（MyBatis-Plus 的 Mapper 接口）
│
├── common                 // 6. 提取出来的公共基础设施包
│   ├── exception          //    - 全局异常定义及异常处理器（GlobalExceptionHandler）
│   ├── constant           //    - 全局常量（或按业务拆分）
│   └── enums              //    - 全局或业务枚举
│
└── util                   // 7. 纯工具类（Hutool中没有的才需要自己编写）

resource.mapper 存放数据库xml文件
```

## 开发要求

1. 单个java文件不要超过400行，单个方法不要超过100行
2. 新增，修改代码时需要有对应的单元测试代码
3. 工具类优先使用Hutool中的，文档：https://doc.hutool.cn/module/core/
4. 依赖包优先使用可用的最新版
5. 代码设计遵循高内聚低耦合
6. 使用flyway管理数据库文件，每个开发任务使用一个flyway文件
7. 数据库表以"t_"开头，比如t_user

# 前端

在为本项目生成、重构或修改代码时，必须严格遵守以下定义的架构设计、布局规则和编码规范。

## 技术栈

Vite + Vue 3 + TypeScript + Pinia + Tailwind CSS + Radix Vue + Tailwind-merge

## 系统架构与布局控制

### 布局结构（自上而下的全局策略）

本应用采用平台级的多功能分层导航系统。除非有明确的特殊需求，否则不允许在组件内部创建自定义的全局滚动条。

* **视口限制（Viewport Restriction）：** 最外层容器必须严格占满屏幕 `100vh` 和 `100vw`，并设置 `overflow-hidden`
  ，防止因滚动条导致页面整体抖动。
* **顶部导航栏（Header - 一级菜单）：** 负责全站核心模块的上下文切换。此状态必须通过 Pinia 统一管理。
* **左侧边栏（Sidebar - 二级菜单）：** 根据 Pinia 中当前激活的一级菜单，动态联动渲染对应的二级菜单列表。可包含全局资源微件（例如在
  `files` 文件上下文中展示磁盘容量进度条）。
* **主内容区（`<main>`）：** 填充剩余的所有屏幕空间（`flex-1`）。必须包含 `overflow-y-auto`
  ，以便让各个子模块（如文件网格、笔记编辑器）在其内部独立进行垂直滚动，而保持顶部栏和左侧栏固定不滚动。

## 代码编写硬性规范

### Vue 3 & TypeScript

- **禁止使用 Options API**，一律采用 `<script setup lang="ts">`。
- **严禁使用 `any` 类型**。所有后端返回的接口数据、组件 Props、自定义事件（Emits）必须定义明确的接口（`interface`）或类型（
  `type`）。
- **文件状态枚举**：涉及文件状态、上传状态时，必须使用合法的字面量联合类型（如
  `status: 'waiting' | 'uploading' | 'paused' | 'success' | 'error'`）。

### 样式与 Tailwind CSS

- **严禁编写标准的 Style 标签 (`<style>`)**，除非是第三方库的特殊样式覆盖。所有样式必须通过 Tailwind CSS 的类名实现。
- **动态类名拼接**：当需要根据组件状态（如 `active`, `selected`, `disabled`）动态改变 Tailwind 类名时，**必须**
  使用项目自带的类名合并工具：
  ```typescript
  import { cn } from '@/utils/cn' // 内部封装了 clsx 和 tailwind-merge
  const className = cn('base-class', isActive && 'active-class')

## 其他规范

1. 单个vue文件最大500行，超过500行进行组件拆分，可复用组件放到src/components中，非复用组件放到当前目录的components目录下
2. 后端long类型返回前端会自动转换为string，注意类型定义为string
3. 编写前端代码需考虑美观，简约风
4. 除特殊说明外，前端只考虑页面权限，无须到按钮级别
5. 禁止通过组件属性（Props）进行多层级的状态透传（Props Drilling）。须使用 Pinia。单层级可使用Props传递
6. 已统一展示请求异常，无需在业务代码中再次展示