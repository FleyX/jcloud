import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { vPermission } from '@/directives/permission'
import './assets/styles/index.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.directive('permission', vPermission)

app.mount('#app')
