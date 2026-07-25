# 权限模型：YAML 注册表 + 纯内存加载

我们决定废除"权限/资源数据存数据库、通过 DML 和维护页面管理"的模型，改为：权限与资源的唯一定义来源是代码库中的 `app/src/main/resources/permissions.yml`，应用启动时加载为不可变的内存注册表（`PermissionRegistry`），数据库只保留角色与"角色 → 权限编码"的绑定关系。

## 背景与取舍

旧模型（V1~V12 迁移脚本时期）中，`t_permission`、`t_resource`、`t_permission_resource` 三张表的数据通过多个迁移文件里的 DML 东补一块西补一块地维护，另配了一套权限管理页面和 CRUD 接口。问题在于：

- 权限定义本质上是代码的附属物（URL 变了代码必须跟着改），却要走"写迁移 SQL / 登录管理页操作"的流程，维护成本高且容易与代码脱节。
- 资源/权限的 id（13 位 base36）人不可读，DML 里全是魔法字符串。

考虑过的方案：

1. **YAML → 启动时同步进数据库**
   - 保留了 SQL 联查能力，但引入了"YAML 与 DB 对账"的整套同步逻辑（增/改/删/逻辑删除列如何 reconcile），且 DB 数据从此没有运行时写入口，只是 YAML 的只读镜像——同步逻辑纯属负担。
2. **纯内存注册表（选定）**
   - YAML 加载为不可变 bean，权限/资源/关联关系完全不落库。解析从多次 SQL 变为纯内存查找，code 成为天然主键，id 字段取消。
   - 代价：权限变更必须改 YAML 并重启——这与代码发布节奏一致，可接受。

选择方案 2。

## 关键决策

- **YAML 结构**：`public`（免登录 API）、`login`（登录即可访问 API）、`permissions`（权限树，`code`/`name`/`parent` code 引用/内嵌 `resources`）。条目只有 `code` + `name`，无 id。
- **资源 code 即类型**：API 资源为 `METHOD:path`（如 `GET:/jcloud/api/users`），前端菜单资源以 `VIEW:` 为前缀（如 `VIEW:/admin/users`），靠前缀区分，无独立 type 字段。原 `PAGE` 类型资源废弃（其对过滤器而言本来就是匹配不到的死数据）。
- **权限退化为分组结构**：权限（permission）只用于"给角色打包资源"。过滤器与前端统一认资源 code；`LoginVo.permissions` 改为 `LoginVo.resources`，下发解析后的资源 code 列表（含祖先权限展开）。
- **前端只做菜单级权限**：菜单显隐和动态路由用 `VIEW:` 资源 code 判断，页面访问由后端 API 鉴权兜底；`v-permission` 指令简化为同步的资源 code 检查。
- **角色绑定存 code**：`t_role_permission` 的 `permission_id` 改为 `permission_code`；`t_permission`、`t_resource`、`t_permission_resource` 三表删除（V13 迁移，系统未上线，存量绑定清空重建）。
- **超级管理员短路**：`is_admin` 用户直接获得注册表全量资源 code；`super_admin` 角色保持零绑定，新增权限后超管自动拥有。
- **fail-fast 校验**：启动时校验资源/权限 code 重复、父权限悬空、树成环、资源 code 格式非法，任一错误直接启动失败。
- **不做热更新、不做外部路径覆盖**：权限定义随代码走 git，改 YAML 即改代码，需要重启生效。
- **悬空绑定只告警不清理**：角色绑定的 code 从 YAML 删除后，解析时静默忽略；启动时 `PermissionBindingAuditor` 打 warn 日志提醒到角色管理中清理。
- **权限管理页面与写接口全部删除**，仅保留只读的 `GET /permissions/tree`（数据源为注册表）供角色分配界面渲染勾选树。

## 影响

- 新增后端接口必须在 `permissions.yml` 登记资源，否则请求会被鉴权过滤器拒绝（超级管理员除外）。
- 新增需要鉴权的前端菜单/页面：在对应权限下加 `VIEW:` 资源，前端用 `userStore.hasResource('VIEW:...')` 判断。
- `PermissionResolver`/`AuthTokenFilter` 不再查询权限相关表；用户资源缓存（`UserPermissionCache`）机制不变，角色变更仍即时不必要重启。
