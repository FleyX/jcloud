# 用户存储空间文件同步：物理目录到数据库的增量对齐

我们决定在用户管理后台提供“同步”功能，允许管理员将用户物理存储空间目录（`files/<username>/`）中的文件树单向同步到数据库的 `t_file_node` 表。同步支持立即执行和基于 cron 表达式的定时执行；以物理目录为准，通过 `size` + `last_modified` 判断文件是否变化，复用现有用户级 Redisson 写锁保证并发安全。

## 背景与取舍

业务上需要支持用户直接操作存储空间的物理目录，然后通过管理后台把变更同步到网盘数据库中。这涉及数据库记录与物理文件之间的一致性修复，而不是日常上传/下载路径的替代。

考虑过三种同步策略：

1. **整表清空后全量重建**
   - 优点：实现最简单，不需要逐条 diff。
   - 缺点：会切断 `t_file_node` 与其他表（回收站、预览文件等）的引用关系；重建期间新插入的节点 ID 全部变化，关联数据大面积悬空。

2. **加载整棵树后全量 diff**
   - 优点：可以精确计算新增、更新、删除集合。
   - 缺点：大用户需要一次性把整棵 DB 树加载到内存；失败时大事务回滚成本高。

3. **递归按文件夹 diff（选定）**
   - 逐层读取数据库子节点和物理子节点，在同一文件夹内做 diff：新增、更新、删除。
   - 不加载全树，内存占用只与单层目录大小相关；不依赖大事务，单文件夹内顺序执行。
   - 保留未变化文件的原 ID，避免引用关系断裂。

选择方案 3。

## 关键决策

### 1. 同步语义：单向、物理为准、diff 而非重建

- 方向：仅把物理目录结构写入数据库，不会把数据库状态反向写回磁盘。
- 缺失处理：物理端不存在的文件/文件夹，从数据库中删除；文件夹删除时级联删除其所有后代节点。
- 新增处理：物理端存在但数据库没有的节点，按实际类型插入 `file` 或 `folder` 记录。
- 更新处理：同名节点大小或 `last_modified` 不同，按物理端更新数据库记录，但保留原 ID。
- 未变化：路径存在、大小相同、`last_modified` 相同，跳过，不重新计算 hash。

### 2. 扫描范围与过滤规则

- 扫描根目录：`storageSpace.path/files/<username>/`。
- 不扫描 `trash/`、`tmp/`、`zip-tasks/`、`system/` 等系统内部目录。
- 跳过隐藏文件（`.` 开头）、符号链接、非普通文件（socket、pipe、device）。
- 硬链接按普通文件对待，不做 inode 级去重。
- 对非法文件名、超长路径等异常条目记录到任务日志并跳过，整体任务标记为部分成功。

### 3. 变化判断字段：`t_file_node.last_modified`

- 新增 `last_modified` 字段（毫秒时间戳），同步时读取文件/目录的 `Files.getLastModifiedTime`。
- 判断公式：`path 存在 && size 相同 && last_modified 相同` ⇒ 未变化。
- 第一版不重新计算 hash，避免大文件全量读取带来的性能开销；后续如需检测“同大小但内容变化”，可扩展 hash 采样策略。
- 目录的 `last_modified` 同样参与判断；目录本身大小固定为 0。

### 4. 并发控制：复用现有用户级 Redisson 写锁

- 同步任务开始前获取 `UserReadWriteLock.writeLock(userId)`。
- 使用 `tryLock(30, TimeUnit.SECONDS)`，超时则任务标记为 `FAILED`，原因写“获取用户写锁超时”。
- 与文件上传、删除、移动、重命名、回收站恢复、存储空间迁移共用同一把锁，保证同一用户的写操作串行执行。
- 不阻塞读操作（文件列表、下载等）。

### 5. 任务执行与调度模型

- **立即同步**：接口异步提交，返回 `taskId`，前端每 2 秒轮询最新任务状态。
- **定时同步**：`t_user_sync_config` 表按用户存储 cron 表达式与启用状态；`@Scheduled` 每分钟扫描到期且启用的配置，异步触发同步任务。
- **任务记录**：`t_user_sync_task` 记录每次同步的触发方式（`manual` / `scheduled`）、状态、起止时间、总节点数、成功/失败数、错误信息。
- 手动同步与定时同步共用同一把用户写锁，互斥执行。

### 6. 数据表与接口

- `t_file_node` 新增 `last_modified BIGINT NOT NULL DEFAULT 0` 并加注释。
- 新增 `t_user_sync_config`：
  - `user_id VARCHAR(13) PRIMARY KEY`
  - `cron_expr VARCHAR(128) NOT NULL`
  - `enabled SMALLINT NOT NULL DEFAULT 1`
  - `next_sync_time TIMESTAMP`
  - `create_time`、`update_time`
- 新增 `t_user_sync_task`：
  - `id VARCHAR(13) PRIMARY KEY`
  - `user_id`、`type`、`status`、`start_time`、`end_time`
  - `total_count`、`success_count`、`fail_count`、`error_msg`
  - `create_time`、`update_time`
- 管理后台接口：
  - `POST /jcloud/api/admin/users/{id}/sync/immediate`
  - `GET /jcloud/api/admin/users/{id}/sync/task`
  - `GET /jcloud/api/admin/users/{id}/sync/config`
  - `PUT /jcloud/api/admin/users/{id}/sync/config`

### 7. 前端入口与权限

- 在 `Users.vue` 单行操作栏新增“同步”按钮，与编辑、迁移、删除并列。
- 弹窗分“立即同步”和“定时同步”两个区域：
  - 立即同步：展示最近一次任务状态，提供“立即同步”按钮。
  - 定时同步：cron 输入框、启用开关、保存按钮。
- 权限：新增 4 条 API 资源记录到 `t_resource`，关联到 `user:menu`，与迁移任务保持一致的授权范围。

## 影响

- 新增两张表和一次 `t_file_node` 字段变更，需通过 Flyway 迁移文件执行，且所有字段必须带 COMMENT。
- 同步任务可能长时间持有用户写锁，期间该用户无法上传、删除、移动、重命名文件或执行另一次同步/迁移。
- 大目录同步可能持续数秒到数分钟，前端通过轮询展示进度，接口不会阻塞 HTTP 线程。
- 不重新计算 hash 意味着无法检测“同大小但内容变化”的文件；业务上可接受，后续可升级。
- 同步删除数据库节点时不会主动清理 `t_preview_file`、`t_recycle_bin` 等关联记录（diff 策略保留未变化节点 ID，主要避免悬空）；对已删除节点的关联数据残留，由各自业务模块按自身生命周期处理。
