/**
 * 会话 ID 转换（后端 ConversationIdUtil.buildIsolatedId = "u_{userId}_{raw}"）
 *
 * 后端约定：ChatRequest.conversationId 传 **raw** id；列表/详情/消息接口用 **isolated** id。
 * 前端统一持有 isolated id（URL、列表），发送聊天时转成 raw。
 */
const PREFIX = 'u_'
const SEP = '_'

/** isolated → raw（剥离 u_{userId}_ 前缀；无法识别则原样返回） */
export function toRawConversationId(isolatedId: string | null | undefined, userId?: number | null): string | null {
  if (!isolatedId) return null
  if (userId != null) {
    const prefix = `${PREFIX}${userId}${SEP}`
    if (isolatedId.startsWith(prefix)) return isolatedId.slice(prefix.length)
  }
  // 兜底：尝试剥离首个 "u_<digits>_" 前缀
  const m = isolatedId.match(/^u_(\d+)_(.+)$/)
  return m ? m[2] : isolatedId
}

/** raw → isolated（已知 userId 时拼前缀） */
export function buildIsolatedId(raw: string, userId: number): string {
  return `${PREFIX}${userId}${SEP}${raw}`
}

/** 生成新 raw id（客户端生成，后端按确定性方案拼 isolated） */
export function newRawId(): string {
  return crypto.randomUUID()
}
