import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { vPermission } from '@/directives/permission'
import './assets/styles/index.css'
import { useThemeStore } from '@/store/theme'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.directive('permission', vPermission)

// 主题在挂载前初始化（首帧即应用持久化主题）；等待路由首跳完成后再挂载，
// 保证 App.vue 首次渲染看到最终路由 meta，独立播放页在渲染前即可禁用文档主题（避免首屏主题闪现）。
useThemeStore(pinia)

await router.isReady()

app.mount('#app')
