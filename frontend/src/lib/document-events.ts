const SSE_URL = '/api/documents/events'

/**
 * 订阅文档状态变更 SSE。
 *
 * - 原生 EventSource（GET + 无 body），断线由浏览器自动重连，无需手动处理
 * - 后端在 ETL 各阶段（PARSING/CHUNKING/VECTORIZING/COMPLETED/FAILED/VECTOR_FAILED）
 *   状态落库后推送 `event: status` 帧
 * - 5s 心跳注释帧（`:hb`）由 EventSource 原生忽略，不触发监听器
 *
 * @param onChange 收到状态变更时的回调（调用方据此 invalidate react-query）
 * @return EventSource，调用方负责在组件卸载时 close()
 */
export function subscribeDocumentStatus(onChange: () => void): EventSource {
  const es = new EventSource(SSE_URL, { withCredentials: true })
  es.addEventListener('status', onChange)
  return es
}
