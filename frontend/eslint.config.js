import { defineConfig, globalIgnores } from 'eslint/config'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import importX from 'eslint-plugin-import-x'
import globals from 'globals'

// 质量门第四道：收口 tsc 查不出的错误类别——Rules of React、hooks 依赖数组、
// floating promise、import 方向（见 .trellis/spec/frontend/quality-and-testing.md「护栏缺口」）。
// import-x/no-restricted-paths 的 zones 与 directory-structure.md 的分层表逐条对应。
export default defineConfig(
  globalIgnores(['dist', 'coverage']),

  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'import-x': importX,
    },
    settings: {
      'import-x/resolver': {
        typescript: {
          project: './tsconfig.app.json',
          alwaysTryTypes: true,
        },
      },
    },
    rules: {
      ...reactHooks.configs['recommended-latest'].rules,

      // verbatimModuleSyntax 之上再进一步：仅作类型用途的导入也必须写成 import type
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],

      '@typescript-eslint/no-unused-vars': [
        'error',
        { varsIgnorePattern: '^_', argsIgnorePattern: '^_' },
      ],

      // console.error/warn 是正当的错误上报（如 checksum worker 兜底）放行；
      // TEMP-DEBUG 联调日志（api-fetch.ts 等）允许临时存在：warn 不挡门，提交前应清零
      'no-console': ['warn', { allow: ['error', 'warn'] }],

      // lib 最底层不得上翻；特性组件 auth/chat/knowledge 互不跨域；
      // stores 不 import 组件/页面/hooks；chat-store 引用 convKeys 是 directory-structure.md 已登记例外。
      // 注意 zone 语义：target = 发起 import 的文件，from = 被禁止的 import 目标。
      'import-x/no-restricted-paths': ['error', {
        zones: [
          { target: './src/lib', from: './src/api' },
          { target: './src/lib', from: './src/stores' },
          { target: './src/lib', from: './src/components' },
          { target: './src/lib', from: './src/hooks' },
          { target: './src/lib', from: './src/pages' },

          { target: './src/components/chat', from: './src/components/knowledge' },
          { target: './src/components/knowledge', from: './src/components/chat' },
          { target: './src/components/auth', from: './src/components/chat' },
          { target: './src/components/chat', from: './src/components/auth' },
          { target: './src/components/auth', from: './src/components/knowledge' },
          { target: './src/components/knowledge', from: './src/components/auth' },

          { target: './src/stores', from: './src/components' },
          { target: './src/stores', from: './src/pages' },
          { target: './src/stores', from: './src/hooks' },
          { target: './src/stores', from: './src/api', except: ['./conversations.ts'] },

          { target: './src/api', from: './src/components' },
          { target: './src/api', from: './src/pages' },
        ],
      }],
    },
  },
)
