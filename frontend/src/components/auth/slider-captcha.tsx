import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { RefreshCw, CheckCircle2, ChevronRight, Loader2 } from 'lucide-react'
import { getCaptcha } from '@/api/auth'
import type { CaptchaResult } from '@/types/auth'
import { cn } from '@/lib/utils'

/**
 * SliderCaptcha 滑块验证码（DS §11.1 / wireframe §3）
 *
 * 关键约定（CaptchaService）：
 * - 背景图 310×155，缺口 y 固定居中（answerY=54），x 随机
 * - puzzleImage top 写死 39px（= answerY - padding15），仅水平拖动
 * - captchaCode = 拼图块左边缘 x = handleX + 15（补偿 padding）
 * - 容差 ±5px 由后端校验；前端拖到右端即乐观置成功，提交时由登录结果裁决
 */
export interface SliderCaptchaHandle {
  /** 获取新验证码（失败/过期/登录验证码错误后调用） */
  refresh: () => void
  /** 标记失败（抖动回弹），用于登录返回验证码错误时 */
  fail: () => void
}

export interface SliderCaptchaProps {
  onVerified: (captchaId: string, captchaCode: number) => void
  /** 验证失效（每次重置后通知父级清空已记录的验证态） */
  onReset?: () => void
}

const BG_W = 310
const BG_H = 155
const TRACK_H = 40
const PUZZLE_TOP = 39

type Status = 'idle' | 'dragging' | 'verifying' | 'success' | 'fail'

export const SliderCaptcha = forwardRef<SliderCaptchaHandle, SliderCaptchaProps>(
  function SliderCaptcha({ onVerified, onReset }, ref) {
    const [captcha, setCaptcha] = useState<CaptchaResult | null>(null)
    const [status, setStatus] = useState<Status>('idle')
    const [handleX, setHandleX] = useState(0)
    const [loading, setLoading] = useState(true)
    const draggingRef = useRef(false)
    const startXRef = useRef(0)
    const startHandleXRef = useRef(0)

    // 用 ref 持有最新回调，使 load 依赖稳定（空数组），
    // 避免父组件 re-render（submitting/dialogError 变化）→ onReset 身份变化 → load 重建 → 重复 fetch
    const onVerifiedRef = useRef(onVerified)
    onVerifiedRef.current = onVerified
    const onResetRef = useRef(onReset)
    onResetRef.current = onReset
    // in-flight 守卫：防止 StrictMode/并发导致的重复请求
    const loadingTokenRef = useRef(0)
    // 挂载一次守卫：StrictMode dev 会双跑 effect，确保只 fetch 一次
    const didMountRef = useRef(false)

    const load = useCallback(async () => {
      const token = ++loadingTokenRef.current
      setLoading(true)
      setStatus('idle')
      setHandleX(0)
      try {
        const data = await getCaptcha()
        if (token !== loadingTokenRef.current) return // 已被更新的请求取代
        setCaptcha(data)
        onResetRef.current?.()
      } catch {
        if (token === loadingTokenRef.current) setCaptcha(null)
      } finally {
        if (token === loadingTokenRef.current) setLoading(false)
      }
    }, [])

    // 仅挂载时加载一次（load 恒定；StrictMode dev 双跑被 didMountRef 拦截）
    useEffect(() => {
      if (didMountRef.current) return
      didMountRef.current = true
      void load()
    }, [load])

    useImperativeHandle(ref, () => ({
      refresh: () => void load(),
      fail: () => {
        setStatus('fail')
        // 抖动后回弹并刷新（仅一次）
        setTimeout(() => void load(), 500)
      },
    }))

    const maxX = BG_W - TRACK_H

    const onPointerDown = (e: React.PointerEvent) => {
      if (status === 'success' || status === 'verifying' || loading) return
      draggingRef.current = true
      startXRef.current = e.clientX
      startHandleXRef.current = handleX
      setStatus('dragging')
      ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    }

    const onPointerMove = (e: React.PointerEvent) => {
      if (!draggingRef.current) return
      const delta = e.clientX - startXRef.current
      const next = Math.max(0, Math.min(maxX, startHandleXRef.current + delta))
      setHandleX(next)
    }

    const onPointerUp = () => {
      if (!draggingRef.current) return
      draggingRef.current = false
      if (!captcha) return
      // 松手即提交位移。captchaCode = handleX + 15（拼图块在图内偏移 15px，对齐后端 answerX）。
      // 拼图块随滑块 1:1 移动，用户视觉对齐缺口时 handleX+15 ≈ answerX；±5px 容差由后端裁决。
      // 注意：不要用"拖到右端才算成功"——缺口 x 随机分布于 [10,253]，强制拖到右端必然失败。
      setStatus('success')
      const captchaCode = Math.round(handleX) + 15
      onVerified(captcha.captchaId, captchaCode)
    }

    const trackFillW = status === 'success' ? BG_W : handleX + TRACK_H
    const solved = status === 'success'

    return (
      <div className="w-[310px] select-none">
        {/* 背景图 + 拼图块 */}
        <div className="relative overflow-hidden rounded-md border border-line-subtle bg-base" style={{ width: BG_W, height: BG_H }}>
          {captcha?.backgroundImage && (
            <img src={`data:image/png;base64,${captcha.backgroundImage}`} alt="" draggable={false} className="absolute inset-0 size-full" />
          )}
          {captcha?.puzzleImage && (
            <img
              src={`data:image/png;base64,${captcha.puzzleImage}`}
              alt=""
              draggable={false}
              className={cn('absolute size-auto transition-transform', status === 'fail' && 'animate-[shake_0.4s]')}
              style={{ top: PUZZLE_TOP, transform: `translateX(${handleX}px)`, left: 0 }}
            />
          )}
          {/* 刷新按钮 */}
          <button
            onClick={() => void load()}
            className="absolute right-1.5 top-1.5 rounded-md bg-black/40 p-1 text-white transition-colors hover:bg-black/60"
            aria-label="刷新验证码"
            type="button"
          >
            <RefreshCw className={cn('size-3.5', loading && 'animate-spin')} />
          </button>

          {/* 状态文案 */}
          {loading && (
            <div className="absolute inset-0 flex items-center justify-center text-subtle">
              <Loader2 className="size-4 animate-spin" />
            </div>
          )}
        </div>

        {/* 滑块轨道 */}
        <div
          className={cn(
            'relative mt-2 overflow-hidden rounded-md border border-line-subtle transition-colors',
            solved ? 'bg-success-50' : 'bg-base',
          )}
          style={{ width: BG_W, height: TRACK_H }}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerLeave={onPointerUp}
        >
          {/* 已过填充 */}
          {!solved && (
            <div className="absolute inset-y-0 left-0 bg-primary-50" style={{ width: trackFillW }} />
          )}
          {solved && <div className="absolute inset-0 bg-success-600/15" />}

          {/* 文案 */}
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center gap-1.5 text-sm">
            {solved ? (
              <span className="flex items-center gap-1.5 text-success-700">
                <CheckCircle2 className="size-4" /> 验证成功
              </span>
            ) : status === 'fail' ? (
              <span className="text-error-600">验证失败，请重试</span>
            ) : (
              <span className="flex items-center gap-1.5 text-subtle">
                <ChevronRight className="size-4" /> 向右拖动完成验证
              </span>
            )}
          </div>

          {/* 滑块手柄 */}
          <button
            type="button"
            onPointerDown={onPointerDown}
            disabled={solved || loading}
            className={cn(
              'absolute top-0 flex size-full cursor-grab items-center justify-center rounded-md border shadow-sm transition-colors active:cursor-grabbing disabled:cursor-default',
              solved
                ? 'border-success-600 bg-success-600 text-inv'
                : 'border-line bg-surface text-subtle hover:text-fg',
            )}
            style={{ width: TRACK_H, transform: `translateX(${handleX}px)` }}
            aria-label="拖动滑块"
          >
            {solved ? <CheckCircle2 className="size-4" /> : <ChevronRight className="size-4" />}
          </button>
        </div>
      </div>
    )
  },
)
