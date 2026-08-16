import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ScopeTabs } from '@/components/knowledge/scope-tabs'
import { DocumentTable } from '@/components/knowledge/document-table'
import { DocumentDetailDrawer } from '@/components/knowledge/document-detail-drawer'
import { UploadButton } from '@/components/knowledge/upload-button'
import { PageContainer } from '@/components/common/page-placeholder'
import { IN_FLIGHT_DOC_STATUSES, subscribeDocumentStatus } from '@/lib/document-events'
import { docKeys, useDocuments } from '@/api/documents'
import { useAuthStore } from '@/stores/auth-store'
import { toast } from 'sonner'
import type { DocumentDTO } from '@/types/document'

export default function KnowledgePage() {
  const queryClient = useQueryClient()
  const { teamId } = useParams<{ teamId?: string }>()
  const parsedTeamId = teamId ? Number(teamId) : null
  const scope: 'personal' | 'team' = parsedTeamId != null ? 'team' : 'personal'
  const [openDoc, setOpenDoc] = useState<DocumentDTO | null>(null)
  const [newVersionFor, setNewVersionFor] = useState<DocumentDTO | null>(null)

  // 复用 DocumentTable 的同一 query（key 相同，react-query 去重），据此判断自己是否有在途文档
  const { data: docData } = useDocuments({ teamId: parsedTeamId, size: 20 })
  const currentUserId = useAuthStore((s) => s.user?.id)
  const hasOwnInFlight = useMemo(
    () =>
      (docData?.pages ?? []).some((p) =>
        p.content.some((d) => d.userId === currentUserId && IN_FLIGHT_DOC_STATUSES.has(d.status)),
      ),
    [docData, currentUserId],
  )

  // SSE 跟随在途：事件按 owner 路由（只推给上传者本人），仅自己在途时订阅、全部终态即关闭。
  // 不再页面常驻——常驻流吊到服务端超时后靠浏览器无限重连续命，整夜空转，
  // 且 10 分钟连接跨过 token 15 分钟寿命后，收尾 dispatch 重验失败（Access Denied 噪音）
  useEffect(() => {
    if (!hasOwnInFlight) return
    const es = subscribeDocumentStatus(() =>
      void queryClient.invalidateQueries({ queryKey: docKeys.all }),
    )
    return () => es.close()
  }, [hasOwnInFlight, queryClient])

  // 初态/终态兜底轮询：SSE 只推自己，他人（团队）文档的初态/终态靠列表轮询感知。
  // 仅前台可见时刷新——后台 tab 不轮询，避免挂着过夜产生空转流量
  useEffect(() => {
    const t = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void queryClient.invalidateQueries({ queryKey: docKeys.all })
      }
    }, 60_000)
    return () => clearInterval(t)
  }, [queryClient])

  return (
    <PageContainer
      title={scope === 'team' ? '团队文档' : '个人文档'}
      subtitle="上传文档后即可在聊天中用 RAG 检索。支持 PDF/DOCX/PPTX/XLSX/TXT/MD/HTML，单文件 ≤ 50MB。"
      actions={<ScopeTabs scope={scope} activeTeamId={parsedTeamId} />}
    >
      {/* 上传区：按钮 + 拖拽 dropzone（覆盖内容区）。
          行菜单"上传新版本"会把 replaceDocumentId 传给下一次上传 */}
      <UploadButton
        teamId={parsedTeamId}
        replaceDocumentId={newVersionFor?.id}
        onDone={() => {
          if (newVersionFor) {
            toast.success('已上传新版本')
            setNewVersionFor(null)
          }
        }}
      />

      <DocumentTable
        teamId={parsedTeamId}
        onOpenDoc={(doc) => setOpenDoc(doc)}
        onNewVersion={(doc) => {
          setNewVersionFor(doc)
          toast.info(`请点击「上传文档」选择 ${doc.fileName} 的新版本`)
        }}
      />

      <DocumentDetailDrawer
        docId={openDoc?.id ?? null}
        open={!!openDoc}
        onOpenChange={(o) => !o && setOpenDoc(null)}
      />
    </PageContainer>
  )
}
