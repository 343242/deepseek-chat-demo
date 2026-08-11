import { FileText, FileSpreadsheet, Presentation, FileImage, File } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { extOf } from '@/lib/format'

/** 按 mimeType/扩展名取文档类型图标 + 色（DS §11.5） */
const MAP: Record<string, { icon: LucideIcon; color: string }> = {
  pdf: { icon: FileText, color: '#E11D48' },
  doc: { icon: FileText, color: '#2563EB' },
  docx: { icon: FileText, color: '#2563EB' },
  ppt: { icon: Presentation, color: '#EA580C' },
  pptx: { icon: Presentation, color: '#EA580C' },
  xls: { icon: FileSpreadsheet, color: '#16A34A' },
  xlsx: { icon: FileSpreadsheet, color: '#16A34A' },
  png: { icon: FileImage, color: '#7C3AED' },
  jpg: { icon: FileImage, color: '#7C3AED' },
  jpeg: { icon: FileImage, color: '#7C3AED' },
}

export function FileTypeIcon({ fileName, className }: { fileName: string; className?: string }) {
  const ext = extOf(fileName)
  const entry = MAP[ext] ?? { icon: File, color: 'var(--neutral-500)' }
  const Icon = entry.icon
  return <Icon className={className} style={{ color: entry.color }} />
}
