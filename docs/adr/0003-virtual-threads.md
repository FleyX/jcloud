# 后端启用虚拟线程

为了在 2C4G 的部署规格下支撑数百并发、I/O 密集型的文件传输场景，我们决定在后端全局启用 Spring Boot 虚拟线程。具体配置为 `spring.threads.virtual.enabled=true`，Hikari 连接池最大 50，Tomcat 请求处理线程上限 1000。同时把 `AuthTokenFilter` 中的 `synchronized` 懒加载改为启动时预加载，避免虚拟线程被载体线程短暂钉住。

这次改动只涉及虚拟线程开关和相关上限调整，文件上传/下载、分片、远程挂载等具体实现不在本次范围内。
