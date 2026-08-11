import { memo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import rehypeKatex from 'rehype-katex'
import { Check, Copy } from 'lucide-react'
import 'katex/dist/katex.min.css'
import { cn } from '@/lib/utils'

/**
 * Markdown 渲染（DS §11.3.2）
 * - react-markdown + remark-gfm（表格/列表/任务列表）+ rehype-raw（原始 HTML）
 * - rehype-sanitize 白名单防 XSS（必备，LLM 输出可能含恶意脚本）
 * - rehype-katex 数学公式
 * - 代码块：暗色主题 + 语言标签 + 复制按钮
 *   （shiki/rehype-pretty-code 高亮为后续增强项；当前用克制的暗色代码块，避免与 sanitize 白名单冲突）
 */

// 扩展 sanitize schema：允许类名（katex/代码语言）与 align 等常见属性
const schema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    code: [...(defaultSchema.attributes?.code ?? []), ['className']],
    span: [...(defaultSchema.attributes?.span ?? []), 'className', 'style'],
    div: [...(defaultSchema.attributes?.div ?? []), 'className', 'style'],
    td: [...(defaultSchema.attributes?.td ?? []), 'align'],
    th: [...(defaultSchema.attributes?.th ?? []), 'align'],
  },
  tagNames: [...(defaultSchema.tagNames ?? []), 'u', 's'],
}

/** react-markdown 子节点（<code> 元素）的最小 props 形状 */
interface CodeNodeProps {
  className?: string
  children?: unknown
}
interface CodeNode {
  props?: CodeNodeProps
}

function CodeBlock({ children }: { children: React.ReactNode }) {
  const [copied, setCopied] = useState(false)
  // 从 children（<code> 元素）提取语言与文本
  const codeEl = (Array.isArray(children) ? children[0] : children) as CodeNode
  const props = codeEl?.props ?? {}
  const className: string = props.className ?? ''
  const lang = /language-(\w+)/.exec(className)?.[1]
  const text = extractText(props.children)

  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* ignore */
    }
  }

  return (
    <div className="group relative my-3 overflow-hidden rounded-md border border-line-subtle bg-neutral-900">
      <div className="flex items-center justify-between border-b border-white/10 px-3 py-1.5">
        <span className="text-xs font-medium text-neutral-400">{lang || 'text'}</span>
        <button
          onClick={copy}
          className="flex items-center gap-1 text-xs text-neutral-400 transition-colors hover:text-white"
          type="button"
        >
          {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="overflow-x-auto p-3 text-sm leading-relaxed">
        <code className={cn('font-mono text-neutral-100', className)}>{props.children as React.ReactNode}</code>
      </pre>
    </div>
  )
}

function extractText(node: unknown): string {
  if (typeof node === 'string') return node
  if (Array.isArray(node)) return node.map(extractText).join('')
  if (node && typeof node === 'object' && 'props' in node) {
    return extractText((node as CodeNode).props?.children)
  }
  return ''
}

export const MarkdownViewer = memo(function MarkdownViewer({ content }: { content: string }) {
  return (
    <div className="prose-chat max-w-none break-words text-md leading-relaxed text-fg">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeRaw, { allowDangerousHtml: true }], [rehypeSanitize, schema], rehypeKatex]}
        components={{
          pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
          code: ({ className, children }) => (
            <code className={cn('rounded bg-base px-1 py-0.5 font-mono text-[0.85em]', className)}>{children}</code>
          ),
          a: ({ children, ...props }) => (
            <a {...props} target="_blank" rel="noopener noreferrer" className="text-link hover:underline">
              {children}
            </a>
          ),
          table: ({ children }) => (
            <div className="my-3 overflow-x-auto rounded-md border border-line-subtle">
              <table className="w-full border-collapse text-sm">{children}</table>
            </div>
          ),
          th: ({ children }) => <th className="border-b border-line-subtle bg-base px-3 py-2 text-left font-medium">{children}</th>,
          td: ({ children }) => <td className="border-b border-line-subtle px-3 py-2">{children}</td>,
          blockquote: ({ children }) => <blockquote className="my-2 border-l-2 border-line pl-3 text-muted">{children}</blockquote>,
          ul: ({ children }) => <ul className="my-2 list-disc space-y-1 pl-5 marker:text-subtle">{children}</ul>,
          ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-5 marker:text-subtle">{children}</ol>,
          h1: ({ children }) => <h1 className="mb-2 mt-4 text-xl font-semibold">{children}</h1>,
          h2: ({ children }) => <h2 className="mb-2 mt-4 text-lg font-semibold">{children}</h2>,
          h3: ({ children }) => <h3 className="mb-1 mt-3 text-md font-semibold">{children}</h3>,
          p: ({ children }) => <p className="my-2 leading-relaxed">{children}</p>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
})
