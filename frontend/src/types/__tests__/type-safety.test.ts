import { describe, it, expectTypeOf } from 'vitest'
import { mapFrame } from '@/lib/sse'
import { ETL_STATUS_META, type StatusMeta } from '@/lib/status-meta'
import type { EtlStatus, Reference, DocumentUploadResponse } from '@/types/document'
import type { SseFrame, ChatDetail, AgentMetadata } from '@/types/chat'

/**
 * 编译期类型护栏（FE-021）。
 *
 * expectTypeOf 断言在运行时是 no-op，由 `tsc -b`（tsconfig.app 覆盖 src 全量）
 * 在编译期把关——下列契约被意外放宽/破坏时 typecheck 直接失败，
 * 而不是等到某个消费方悄悄丢掉收窄。
 */
describe('编译期类型护栏', () => {
  it('ETL_STATUS_META 与 EtlStatus 穷尽绑定（新状态漏配元数据即编译失败）', () => {
    expectTypeOf(ETL_STATUS_META).toEqualTypeOf<Record<EtlStatus, StatusMeta>>()
  })

  it('mapFrame 契约：返回 SseFrame | null（帧家族变更需显式同步此契约）', () => {
    expectTypeOf(mapFrame(null, '')).toEqualTypeOf<SseFrame | null>()
  })

  it('ChatDetail 判别联合：refs 变体必带 refs、agent 变体必带 meta（FE-017）', () => {
    expectTypeOf<ChatDetail>().toEqualTypeOf<
      { type: 'refs'; refs: Reference[] } | { type: 'agent'; meta: AgentMetadata }
    >()
  })

  it('DocumentUploadResponse 契约：id 可空（批量部分失败项 id=null）+ EtlStatus', () => {
    expectTypeOf<DocumentUploadResponse>().toEqualTypeOf<{
      id: number | null
      fileName: string
      status: EtlStatus
    }>()
  })
})
