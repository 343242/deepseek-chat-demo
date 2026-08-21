/** 权限码（IA §2.2 权限码与模块映射，后端种子） */
export const PERMISSION = {
  CHAT_SEND: 'chat:send',
  CHAT_STREAM: 'chat:stream',
  CONVERSATION_MANAGE: 'conversation:manage',
  USAGE_VIEW: 'usage:view',
  USAGE_VIEW_ALL: 'usage:view:all',
  MODEL_CONFIG: 'model:config',
  PROMPT_MANAGE: 'prompt:manage',
  USER_MANAGE: 'user:manage',
  ROLE_MANAGE: 'role:manage',
  EVALUATION_MANAGE: 'evaluation:manage',
  TEAM_MANAGE: 'team:manage',
  TEAM_VIEW: 'team:view',
  TRACE_VIEW: 'trace:view',
} as const

/** 后台管理权限集合（TopBar 后台切换钮 / 后台入口判定，IA §2.3） */
export const ADMIN_PERMISSIONS = [
  PERMISSION.PROMPT_MANAGE,
  PERMISSION.USER_MANAGE,
  PERMISSION.ROLE_MANAGE,
  PERMISSION.EVALUATION_MANAGE,
] as const

/** 角色 */
export const ROLE = {
  ADMIN: 'ADMIN',
} as const

/** 错误码分段（DS §4.4.12） */
export const ERROR_CODE = {
  SUCCESS: 0,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  RATE_LIMIT: 42900,
  INTERNAL: 50000,
} as const

/** 应用品牌（占位，DS §15.3 品牌替换） */
export const APP = {
  name: 'Smart RAG',
  logo: 'SR',
} as const

/** localStorage keys */
export const STORAGE_KEYS = {
  theme: 'srag.theme',
  sidebarAppCollapsed: 'sidebar.app.collapsed',
  sidebarAdminCollapsed: 'sidebar.admin.collapsed',
  lastAppPage: 'last.app',
  lastAdminPage: 'last.admin',
  /** 上次选中的聊天模型 id（chat-input 记忆） */
  lastModel: 'srag.lastModel',
} as const

/** 聊天输入限制（FE-015：收口到 constants，与 UPLOAD_LIMITS 同层） */
export const CHAT_LIMITS = {
  /** 单条消息最大字符数 */
  maxLength: 10000,
} as const

/** 文档上传限制（DocumentProperties） */
export const UPLOAD_LIMITS = {
  /** 允许的 MIME/扩展（PDF/DOCX/PPTX/XLSX/TXT/MD/HTML） */
  allowedExtensions: ['pdf', 'docx', 'pptx', 'xlsx', 'txt', 'md', 'markdown', 'html', 'htm'],
  maxSize: 50 * 1024 * 1024, // 50MB
  chunkThreshold: 5 * 1024 * 1024, // >5MB 走分片
  chunkSize: 5 * 1024 * 1024, // 默认分片 5MB（1-50MB 区间）
  concurrentChunks: 4,
  maxBatch: 10,
} as const

/** 评估模块限制（线框 09 EVAL-6 + 后端 EvaluationRunController.MAX_COMPARE_RUNS / EvaluationProperties.runner） */
export const EVALUATION = {
  /** 同时运行的评测上限（超出直接 FAILED，summary.error="concurrency limit exceeded"） */
  maxConcurrentRuns: 2,
  /** 单次对比的 run 数上限（POST /runs/compare 校验） */
  maxCompareRuns: 10,
  /** 对比最少 run 数（基线 + 1） */
  minCompareRuns: 2,
  /** topK 取值区间（EvaluationRunner.EvalConfig） */
  topKMin: 1,
  topKMax: 50,
  /** 结果表分页大小（后端 size 上限 500） */
  resultsPageSize: 50,
  /** 条目表前端分页大小（GET /{id} 全量返回） */
  itemsPageSize: 20,
  /** 生成任务轮询间隔（design.md §2：轮询而非 SSE） */
  generationPollMs: 3000,
  /** 运行状态轮询间隔（SSE 断流兜底） */
  runPollMs: 5000,
} as const
