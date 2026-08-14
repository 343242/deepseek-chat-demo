import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ScopeTabs } from '@/components/knowledge/scope-tabs'
import { DocumentTable } from '@/components/knowledge/document-table'
import { DocumentDetailDrawer } from '@/components/knowledge/document-detail-drawer'
import { UploadButton } from '@/components/knowledge/upload-button'
import { PageContainer } from '@/components/common/page-placeholder'
import { subscribeDocumentStatus } from '@/lib/document-events'
import { docKeys } from '@/api/documents'
import { toast } from 'sonner'
import type { DocumentDTO } from '@/types/document'

export default function KnowledgePage() {
  const queryClient = useQueryClient()
  const { teamId } = useParams<{ teamId?: string }>()
  const parsedTeamId = teamId ? Number(teamId) : null
  const scope: 'personal' | 'team' = parsedTeamId != null ? 'team' : 'personal'
  const [openDoc, setOpenDoc] = useState<DocumentDTO | null>(null)
  const [newVersionFor, setNewVersionFor] = useState<DocumentDTO | null>(null)

  // SSE 订阅：后端文档状态变更（解析/分块/向量化/完成/失败）实时推送，无需手动刷新
  useEffect(() => {
    const es = subscribeDocumentStatus(() =>
      queryClient.invalidateQueries({ queryKey: docKeys.all }),
    )
    return () => es.close()
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
          toast.info(`请点击「上传文档」选择 {doc.fileName} 的新版本`.replace('{doc.fileName}', doc.fileName))
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
