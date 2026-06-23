import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'node:path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Tailwind CSS v4 官方 Vite 插件，替代 postcss.config.js + autoprefixer
    tailwindcss(),
  ],
  resolve: {
    // 路径别名 `@` 指向 `src` 目录，配合 tsconfig.json 的 paths 使用
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    open: true,
    proxy: {
      '/jcloud/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'esnext',
    outDir: 'dist',
  },
})
