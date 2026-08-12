import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// 测试配置独立于 vite.config.ts：避免把 jsdom/globals 混入构建链路。
// 与 vite.config.ts 共用 react 插件与 @ alias，保证源码导入一致。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    // 不开 globals：测试文件显式 import { describe, it, expect } from 'vitest'，
    // 避免给 strict + verbatimModuleSyntax 的 app tsconfig 注入全局类型。
    setupFiles: ['./src/test/setup.ts'],
    // 纯逻辑单测默认快；后续接组件测试再按需放宽
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
