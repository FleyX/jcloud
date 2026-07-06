# 增加 WebDAV 服务端点

决定在 JCloud 中增加 WebDAV 服务端能力：登录用户开启后，可通过 `/dav/{userCode}` 使用第三方 WebDAV 客户端挂载并操作自己的虚拟文件树。认证使用 HTTP Basic Auth，密码与主密码相同；服务端复用现有 Spring Boot 端口，写操作复用现有用户级读写锁与文件服务逻辑。

## 关键决策

1. **服务端而非客户端**：JCloud 作为 WebDAV 服务器对外暴露访问端点，与现有作为客户端连接外部 WebDAV 的远程挂载能力属于不同领域。
2. **复用现有端口与架构**：WebDAV 服务运行在 `/dav/*` 路径下，复用 8080 端口、虚拟线程、数据库连接池、Redisson 锁，不引入独立进程或端口。
3. **Basic Auth + 主密码**：WebDAV 客户端事实标准使用 Basic Auth；为降低使用门槛，本期不引入独立 WebDAV 密码，直接使用用户主密码。前端在开启时明确提示 HTTP 明文传输密码的风险。
4. **默认关闭的用户级开关**：`t_user` 表新增 `webdav_enabled` 字段，默认 false。用户需在个人中心手动开启，关闭后立即拒绝该用户的 WebDAV 认证。
5. **完整文件树访问**：`/dav/{userCode}/` 直接映射用户虚拟文件树根目录，含本地文件节点与远程挂载点镜像；不暴露回收站、系统数据目录或临时目录。
6. **完整读写能力**：支持 OPTIONS、PROPFIND、GET、HEAD、PUT、MKCOL、DELETE、MOVE、COPY、LOCK、UNLOCK。同名文件/文件夹直接覆盖；删除物理删除不进回收站。
7. **复用现有写操作语义**：写操作获取用户级读写锁；跨来源（local ↔ remote）写操作拒绝；远程挂载点内写操作走远程写回，失败回滚本地 FileNode。
8. **用户自负其责**：管理员不能通过 WebDAV 代理访问其他用户文件；每个用户只能访问自己的 `/dav/{userCode}` 路径。
9. **最小化 LOCK/UNLOCK**：兼容客户端编辑锁定习惯，lock token 保存于 Redis，不实现严格超时强制校验，用户级写锁已保证串行写。
10. **配额与日志**：PUT 上传受用户配额与存储空间容量限制，超限时返回 507；WebDAV 访问复用现有 traceId access_log，本期不新建审计表。
