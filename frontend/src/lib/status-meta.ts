import {
  FileUp,
  Clock,
  Loader,
  Scissors,
  Sparkles,
  CheckCircle2,
  XCircle,
  Ban,
  ArrowRightLeft,
  MessageCircle,
  MessageSquare,
  MessagesSquare,
  Bot,
  Search,
  Radar,
  Wrench,
  Crown,
  ShieldCheck,
  User,
  type LucideIcon,
} from 'lucide-react'
import type {
  EtlStatus,
} from '@/types/document'
import type { ChatMode, MessageStatus } from '@/types/chat'
import type { ConversationStatus, TitleSource } from '@/types/conversation'
import type { ApprovalStatus, TeamMemberRole } from '@/types/team'
import type { EvalGenJobStatus, EvalItemStatus, EvalRunStatus } from '@/types/evaluation'

export type BadgeVariant = 'neutral' | 'brand' | 'success' | 'warning' | 'error' | 'outline'

export interface StatusMeta {
  label: string
  variant: BadgeVariant
  icon?: LucideIcon
  /** 图标旋转（进行中态） */
  spin?: boolean
}

/* ============ 文档处理状态 EtlStatus（11 值，DS §4.4.1） ============ */
export const ETL_STATUS_META: Record<EtlStatus, StatusMeta> = {
  UPLOADED: { label: '已上传', variant: 'neutral', icon: FileUp },
  PENDING_APPROVAL: { label: '待审批', variant: 'warning', icon: Clock },
  PARSING: { label: '解析中', variant: 'brand', icon: Loader, spin: true },
  CHUNKING: { label: '分块中', variant: 'brand', icon: Scissors, spin: true },
  VECTORIZING: { label: '向量化中', variant: 'brand', icon: Sparkles, spin: true },
  PROCESSING: { label: '处理中', variant: 'brand', icon: Loader, spin: true },
  COMPLETED: { label: '已完成', variant: 'success', icon: CheckCircle2 },
  FAILED: { label: '处理失败', variant: 'error', icon: XCircle },
  VECTOR_FAILED: { label: '向量化失败', variant: 'error', icon: XCircle },
  REJECTED: { label: '已拒绝', variant: 'error', icon: Ban },
  SUPERSEDED: { label: '已被替代', variant: 'neutral', icon: ArrowRightLeft },
}

/* ============ 会话状态（DS §4.4.2） ============ */
export const CONVERSATION_STATUS_META: Record<ConversationStatus, StatusMeta> = {
  ACTIVE: { label: '活跃', variant: 'success', icon: MessageCircle },
  ARCHIVED: { label: '已归档', variant: 'neutral' },
  DELETED: { label: '已删除', variant: 'neutral' },
}

/* ============ 消息状态（DS §4.4.3） ============ */
export const MESSAGE_STATUS_META: Record<MessageStatus, StatusMeta> = {
  IN_PROGRESS: { label: '生成中', variant: 'brand', icon: Loader, spin: true },
  FINISHED: { label: '已完成', variant: 'neutral' },
  ERROR: { label: '出错', variant: 'error', icon: XCircle },
}

/* ============ 审批状态（DS §4.4.4） ============ */
export const APPROVAL_STATUS_META: Record<ApprovalStatus, StatusMeta> = {
  PENDING: { label: '待审批', variant: 'warning', icon: Clock },
  APPROVED: { label: '已通过', variant: 'success', icon: CheckCircle2 },
  REJECTED: { label: '已拒绝', variant: 'error', icon: XCircle },
}

/* ============ 团队成员角色（DS §4.4.5） ============ */
export const TEAM_ROLE_META: Record<TeamMemberRole, StatusMeta> = {
  CREATOR: { label: '创建者', variant: 'brand', icon: Crown },
  ADMIN: { label: '管理员', variant: 'success', icon: ShieldCheck },
  MEMBER: { label: '成员', variant: 'neutral', icon: User },
}

/* ============ 聊天模式（DS §4.4.7） ============ */
export const CHAT_MODE_META: Record<ChatMode, StatusMeta & { desc: string }> = {
  SIMPLE: { label: '单轮', variant: 'neutral', icon: MessageSquare, desc: '不维护上下文' },
  MULTI_TURN: { label: '记忆', variant: 'brand', icon: MessagesSquare, desc: '维护会话记忆，可开启思考' },
  AGENT: { label: 'Agent', variant: 'brand', icon: Bot, desc: 'Agentic RAG，可展开推理过程' },
}

/* ============ Agent 意图（DS §4.4.6） ============ */
export const AGENT_INTENT_META: Record<string, StatusMeta> = {
  DIRECT_ANSWER: { label: '直接回答', variant: 'neutral', icon: MessageSquare },
  RETRIEVAL: { label: '检索', variant: 'brand', icon: Search },
  DEEP_RETRIEVAL: { label: '深度检索', variant: 'brand', icon: Radar },
  GENERAL_TOOL: { label: '通用工具', variant: 'warning', icon: Wrench },
}

/** Agent 意图中文（兜底未知值） */
export function agentIntentLabel(intent?: string): string {
  if (!intent) return '—'
  return AGENT_INTENT_META[intent]?.label ?? intent
}

/* ============ 标题来源（DS §4.4.8） ============ */
export const TITLE_SOURCE_META: Record<TitleSource, { label: string; auto: boolean }> = {
  SYSTEM: { label: '自动', auto: true },
  USER: { label: '自定义', auto: false },
}

/** 来源 Tool 名 → 中文（ReferenceCard，DS §11.8） */
export const SOURCE_LABEL: Record<string, string> = {
  hybridSearch: '混合检索',
  vectorSearch: '向量检索',
  keywordSearch: '关键词检索',
  rerank: '重排',
  docDetail: '文档详情',
}
export function sourceLabel(source?: string | null): string | null {
  if (!source) return null
  return SOURCE_LABEL[source] ?? source
}

/* ============ 评估：数据集条目状态（线框 09 §2.2） ============ */
export const EVALUATION_ITEM_STATUS_META: Record<EvalItemStatus, StatusMeta> = {
  draft: { label: '草稿', variant: 'neutral' },
  approved: { label: '已通过', variant: 'success', icon: CheckCircle2 },
  rejected: { label: '已拒绝', variant: 'error', icon: XCircle },
}

/* ============ 评估：运行状态（线框 09 §3.2） ============ */
export const EVALUATION_RUN_STATUS_META: Record<EvalRunStatus, StatusMeta> = {
  pending: { label: '待启动', variant: 'neutral', icon: Clock },
  running: { label: '运行中', variant: 'brand', icon: Loader, spin: true },
  completed: { label: '已完成', variant: 'success', icon: CheckCircle2 },
  failed: { label: '失败', variant: 'error', icon: XCircle },
}

/* ============ 评估：数据集生成任务状态 ============ */
export const EVALUATION_GEN_JOB_STATUS_META: Record<EvalGenJobStatus, StatusMeta> = {
  pending: { label: '排队中', variant: 'neutral', icon: Clock },
  running: { label: '生成中', variant: 'brand', icon: Loader, spin: true },
  completed: { label: '已完成', variant: 'success', icon: CheckCircle2 },
  failed: { label: '失败', variant: 'error', icon: XCircle },
}
