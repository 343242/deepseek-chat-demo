import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// 后端开发服务器（application-dev.yml: server.port=10808，无 context-path，API 前缀 /api）
const BACKEND = process.env.VITE_BACKEND_URL ?? 'http://localhost:10808'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // /api 反代到后端，Cookie 同源携带（IA §5 / DESIGN-SYSTEM 跨域陷阱）
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        // SSE 流式需要：禁用超时 + 关闭缓冲，让 token 逐块透传
        timeout: 0,
        proxyTimeout: 0,
        selfHandleResponse: false,
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    rollupOptions: {
      output: {
        // echarts/shiki/katex 体积大，单独拆包
        manualChunks: {
          echarts: ['echarts', 'echarts-for-react'],
          shiki: ['shiki'],
          katex: ['katex'],
        },
      },
    },
  },
})
