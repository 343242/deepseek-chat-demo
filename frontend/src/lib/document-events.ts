import type { EtlStatus } from '@/types/document'

const SSE_URL = '/api/documents/events'

/**
 * 在途（非终态）文档状态——SSE 订阅跟随此集合：列表中出现这些状态的「自己的」文档时才订阅，
 * 全部流转到终态后关闭（见 knowledge-page 的订阅 effect）。
 * PENDING_APPROVAL 不产生 ETL 状态事件（审批流不发文），不在此列。
 */
export const IN_FLIGHT_DOC_STATUSES: ReadonlySet<EtlStatus> = new Set([
  'UPLOADED',
  'PARSING',
  'CHUNKING',
  'VECTORIZING',
  'PROCESSING',
])

/**
 * 订阅文档状态变更 SSE。
 *
 * - 原生 EventSource（GET + 无 body）；断线由浏览器自动重连，覆盖处理期间的瞬时断连
 * - 后端在 ETL 各阶段（PARSING/CHUNKING/VECTORIZING/COMPLETED/FAILED/VECTOR_FAILED）
 *   状态落库后推送 `event: status` 帧；事件按 owner 路由——只推给上传者本人，
 *   团队成员收不到他人文档的事件，其初态/终态靠列表轮询感知
 * - 5s 心跳注释帧（`:hb`）由 EventSource 原生忽略，不触发监听器
 * - 服务端在「无在途文档 + 宽限期无事件」时主动 complete（app.sse.document-idle-grace-ms），
 *   10 分钟为 emitter 兜底超时
 *
 * @param onChange 收到状态变更时的回调（调用方据此 invalidate react-query）
 * @return EventSource，调用方负责在订阅条件消失或组件卸载时 close()
 */
export function subscribeDocumentStatus(onChange: () => void): EventSource {
  const es = new EventSource(SSE_URL, { withCredentials: true })
  es.addEventListener('status', onChange)
  return es
}
