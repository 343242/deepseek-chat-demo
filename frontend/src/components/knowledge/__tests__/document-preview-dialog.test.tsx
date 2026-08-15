import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import { DocumentPreviewDialog } from '../document-preview-dialog'
import { downloadDocument } from '@/api/documents'
import type { DocumentDTO } from '@/types/document'

const doc: DocumentDTO = {
  id: 7,
  fileName: 'report.pdf',
  fileSize: 1234567,
  mimeType: 'application/pdf',
  chunkCount: 3,
  status: 'COMPLETED',
  errorMessage: null,
  userId: 1,
  teamId: null,
  version: 1,
  supersededBy: null,
  documentGroupId: null,
  createTime: '2026-08-16T10:00:00',
  previewable: true,
}

// vitest 未开 globals，testing-library 自动 cleanup 不会注册，需手动卸载
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
})

describe('DocumentPreviewDialog（KB-2 安全契约，design §4.3）', () => {
  it('iframe 以预览端点为 src，sandbox 全限制且不含 allow-same-origin', () => {
    render(<DocumentPreviewDialog doc={doc} open onOpenChange={vi.fn()} />)
    const iframe = screen.getByTitle<HTMLIFrameElement>('report.pdf 预览')
    expect(iframe.getAttribute('src')).toBe('/api/documents/7/preview')
    // sandbox 空值 = 全部限制生效（无脚本 / 无同源 / 无表单 / 无弹窗）
    expect(iframe.getAttribute('sandbox')).toBe('')
    expect(iframe.getAttribute('sandbox')).not.toContain('allow-same-origin')
  })

  it('加载完成前显示 loading，iframe onLoad 后隐藏', () => {
    render(<DocumentPreviewDialog doc={doc} open onOpenChange={vi.fn()} />)
    expect(screen.getByText('正在加载预览…')).toBeInTheDocument()
    fireEvent.load(screen.getByTitle('report.pdf 预览'))
    expect(screen.queryByText('正在加载预览…')).not.toBeInTheDocument()
  })

  it('弹窗内下载按钮触发 downloadDocument', () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<DocumentPreviewDialog doc={doc} open onOpenChange={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '下载' }))
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(clickSpy.mock.instances[0]).toHaveProperty('href', expect.stringContaining('/api/documents/7/download'))
  })

  it('doc 为 null 时不渲染', () => {
    const { container } = render(<DocumentPreviewDialog doc={null} open={false} onOpenChange={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('downloadDocument（KB-3）', () => {
  it('通过同源 <a> 导航触发 attachment 下载', () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    downloadDocument(42)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    const a = clickSpy.mock.instances[0] as HTMLAnchorElement
    expect(a.getAttribute('href')).toBe('/api/documents/42/download')
    expect(a.rel).toBe('noopener')
  })
})
