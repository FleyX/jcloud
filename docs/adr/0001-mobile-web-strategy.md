# 运行时 UA 判定 + PC/Mobile 目录拆分的移动端策略

我们决定为 JCloud 前端增加移动端适配，但不使用响应式布局。具体方案为：在应用启动时通过 `navigator.userAgent` 判定设备类型，将 `views/` 和 `components/` 拆分为 `pc/` 和 `mobile/` 两个并行目录；同一路径维护一份路由定义，由视图代理函数根据设备类型动态 import 对应的目录。平板设备归入 PC 端处理。

## 背景与取舍

考虑过三种主流方案：

1. **响应式布局**：同一套组件通过 CSS/断点适配不同屏幕。被明确排除，因为项目要求“直接创建移动端页面”，且 PC 端复杂的表格与管理系统并不适合简单缩放。
2. **独立构建/独立入口**：服务器根据 User-Agent 返回不同的 `index.html` 或不同构建产物。这会成倍增加部署复杂度，且与“状态管理、HTTP 请求复用”的目标冲突。
3. **运行时 UA 判定 + 目录拆分**：单构建产物，同一路由表，动态加载 PC 或 Mobile 组件。兼顾复用与独立设计，符合当前需求。

## 关键决策

- **判定时机与规则**：在 `router.beforeEach` 及 `App.vue` 挂载前完成判定，使用纯 User-Agent 匹配；平板（iPad 等）视为 PC 端，避免大屏幕被强制渲染为手机布局。
- **目录结构**：`views/pc/`、`views/mobile/`、`components/pc/`、`components/mobile/`。共享的 `api/`、`store/`、`types/`、`utils/`、`directives/` 保持在原位置。
- **路由映射**：route name 保持唯一，通过 `deviceView(path)` 工厂函数在运行时选择 `views/pc/${path}` 或 `views/mobile/${path}`。
- **布局外壳**：`App.vue` 作为薄分发层，非公开路由根据设备渲染 `PcAppShell` 或 `MobileAppShell`。移动端外壳采用底部 TabBar，条目由 `menuStore` 中有权限的一级模块驱动。
- **缺省策略**：未实现完整移动端页面时，先放置占位页，不回退到 PC 视图，防止 PC 表格布局被塞进移动外壳。
- **全局组件**：`Toast`、`ConfirmDialog` 等通用组件先复用现有实现，内部根据 `deviceStore.isMobile` 做 minor 调整；差异过大时再拆分为移动端专用组件。
