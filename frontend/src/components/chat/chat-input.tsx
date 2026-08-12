import { useEffect, useRef, useState } from 'react'
import { SendHorizontal, Square, BookOpen } from 'lucide-react'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { ModelSelector } from './model-selector'
import { useModels } from '@/api/models'
import { useChatStore } from '@/stores/chat-store'
import { CHAT_MODE_META } from '@/lib/status-meta'
import { CHAT_LIMITS, STORAGE_KEYS } from '@/lib/constants'
import type { ChatMode } from '@/types/chat'
import { cn } from '@/lib/utils'

const MODES: { value: ChatMode; label: string }[] = [
  { value: 'SIMPLE', label: '单轮' },
  { value: 'MULTI_TURN', label: '记忆' },
  { value: 'AGENT', label: 'Agent' },
]

export function ChatInput() {
  const { data: models } = useModels()
  const streaming = useChatStore((s) => s.streaming)
  const send = useChatStore((s) => s.send)
  const stop = useChatStore((s) => s.stop)

  const [text, setText] = useState('')
  const [model, setModel] = useState<string>(() => localStorage.getItem(STORAGE_KEYS.lastModel) ?? '')
  const [mode, setMode] = useState<ChatMode>('SIMPLE')
  const [rag, setRag] = useState(false)
  const [thinking, setThinking] = useState(false)
  const taRef = useRef<HTMLTextAreaElement>(null)

  // 默认模型：首个可用候选
  useEffect(() => {
    if (!model && models?.length) {
      const first = models.find((m) => m.available) ?? models[0]
      if (first) setModel(first.id)
    }
  }, [models, model])

  // 记忆模式关 → 思考开关自动关且不可用（ChatRequest.isThinkingEnabled 仅 MULTI_TURN）
  const canThink = mode === 'MULTI_TURN'
  useEffect(() => {
    if (!canThink) setThinking(false)
  }, [canThink])

  // 自动高度
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`
  }, [text])

  function persistModel(id: string) {
    setModel(id)
    localStorage.setItem(STORAGE_KEYS.lastModel, id)
  }

  function doSend() {
    const t = text.trim()
    if (!t || streaming || !model) return
    send(t, { model, mode, ragEnabled: rag, enableThinking: canThink && thinking })
    setText('')
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      doSend()
    }
  }

  const overLimit = text.length > CHAT_LIMITS.maxLength
  const canSend = !!text.trim() && !!model && !streaming && !overLimit

  return (
    <div className="border-t border-line-subtle bg-surface px-4 py-3">
      <div className="mx-auto w-full max-w-[var(--layout-chat-input-w)]">
        {/* 控制行 */}
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <ModelSelector value={model} onChange={persistModel} />

          <Select value={mode} onValueChange={(v) => setMode(v as ChatMode)}>
            <SelectTrigger className="h-9 w-[88px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {MODES.map((m) => (
                <SelectItem key={m.value} value={m.value}>
                  <span className="flex items-center gap-1.5">{m.label}</span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <ToggleSwitch checked={rag} onChange={setRag} icon={<BookOpen className="size-3.5" />} label="RAG" />

          <ToggleSwitch checked={canThink && thinking} onChange={setThinking} disabled={!canThink} label="思考" />

          <span className="ml-auto text-xs text-faint">
            {mode === 'AGENT' ? 'Agent 模式 · 可展开推理' : CHAT_MODE_META[mode].desc}
          </span>
        </div>

        {/* 输入框 + 发送 */}
        <div className="relative rounded-md border border-line bg-field focus-within:border-accent focus-within:shadow-focus transition-colors">
          <Textarea
            ref={taRef}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="输入消息…（Shift+Enter 换行，Enter 发送）"
            className="min-h-20 resize-none border-0 bg-transparent shadow-none focus-visible:shadow-none pr-16"
          />
          <div className="flex items-center justify-between px-3 pb-2">
            <span className={cn('text-xs', overLimit ? 'text-error-600' : 'text-faint')}>
              {text.length}/{CHAT_LIMITS.maxLength}
            </span>
            {streaming ? (
              <Button size="sm" variant="secondary" onClick={stop}>
                <Square className="size-3.5" /> 停止
              </Button>
            ) : (
              <Button size="sm" onClick={doSend} disabled={!canSend}>
                <SendHorizontal className="size-4" /> 发送
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function ToggleSwitch({
  checked, onChange, disabled, icon, label,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  icon?: React.ReactNode
  label: string
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Label
          className={cn(
            'flex h-9 cursor-pointer items-center gap-1.5 rounded-md border border-line bg-field px-2.5 text-sm transition-colors',
            checked ? 'border-primary-600 text-primary-700' : 'text-muted hover:text-fg',
            disabled && 'cursor-not-allowed opacity-50',
          )}
        >
          {icon}
          <span>{label}</span>
          <Switch checked={checked} disabled={disabled} onCheckedChange={onChange} className="scale-90" />
        </Label>
      </TooltipTrigger>
      {disabled && <TooltipContent>仅记忆（多轮）模式可用</TooltipContent>}
    </Tooltip>
  )
}
