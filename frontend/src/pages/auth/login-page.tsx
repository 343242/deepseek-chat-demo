import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { User, Lock, Eye, EyeOff, AlertCircle, ShieldCheck } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from '@/components/ui/dialog'
import { SliderCaptcha, type SliderCaptchaHandle } from '@/components/auth/slider-captcha'
import { useLogin, fetchMe } from '@/api/auth'
import { useAuthStore } from '@/stores/auth-store'
import { ROLE, ERROR_CODE } from '@/lib/constants'
import { ApiError } from '@/types/api'

const schema = z.object({
  username: z.string().min(1, '用户名不能为空'),
  password: z.string().min(1, '密码不能为空'),
})
type FormValues = z.infer<typeof schema>

/** 校验 redirect 是否本站路径（防开放重定向，IA §7.2） */
function safeRedirect(raw: string | null): string | null {
  if (!raw) return null
  if (!raw.startsWith('/') || raw.startsWith('//')) return null
  return raw
}

export default function LoginPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const login = useLogin()
  const { register, handleSubmit, formState: { errors, isValid } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    mode: 'onChange',
    defaultValues: { username: '', password: '' },
  })

  const [showPwd, setShowPwd] = useState(false)
  const [topError, setTopError] = useState<string | null>(null)
  // 图形验证弹窗：用户填完用户名密码点登录才弹出
  const [captchaOpen, setCaptchaOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const captchaRef = useRef<SliderCaptchaHandle>(null)
  const credsRef = useRef<FormValues | null>(null)

  // 表单提交：不直接登录，而是暂存凭据并弹出验证码（修复 #1）
  const openCaptcha = (values: FormValues) => {
    setTopError(null)
    setDialogError(null)
    credsRef.current = values
    setCaptchaOpen(true)
  }

  // 滑块验证完成 → 用凭据 + captchaCode 登录
  async function onVerified(captchaId: string, captchaCode: number) {
    const creds = credsRef.current
    if (!creds) return
    setSubmitting(true)
    setDialogError(null)
    // TEMP-DEBUG(联调诊断): 登录提交载荷（captchaCode 契约：String 形式的滑块位移）
    console.info(`[trace] login submit user=${creds.username} captchaId=${captchaId} code=${captchaCode}`)
    try {
      await login.mutateAsync({
        username: creds.username,
        password: creds.password,
        captchaId,
        // 后端 captchaCode 为 @NotBlank String：发送滑块位移的字符串形式（对齐后 = answerX）
        captchaCode: String(captchaCode),
      })
      // 登录响应 permissions 可能为空，立即拉 /me 兜底（IA-5），
      // 在事件边界同步写 store（唯一写入点）后直接导航——RequireAuth 必见 user，零闪烁。
      const me = await fetchMe()
      useAuthStore.getState().setUser(me)
      setCaptchaOpen(false)
      const redirect = safeRedirect(params.get('redirect'))
      if (redirect) {
        void navigate(redirect, { replace: true })
        return
      }
      const isAdmin = me.roles?.includes(ROLE.ADMIN)
      void navigate(isAdmin ? '/admin' : '/app/chat', { replace: true })
    } catch (e) {
      // 验证码已消费 → 刷新重试（抖动回弹）
      captchaRef.current?.fail()
      if (e instanceof ApiError) {
        if (e.code === ERROR_CODE.RATE_LIMIT) {
          setDialogError(e.message || '尝试过于频繁，请稍后再试')
        } else {
          setDialogError(e.message || '用户名或密码错误')
        }
      } else {
        // 网络层异常（如 fetch 的 TypeError）不是 ApiError，统一兜底文案（FE-016）
        setDialogError('网络异常，请稍后重试')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <form
        onSubmit={(e) => void handleSubmit(openCaptcha)(e)}
        className="rounded-3xl border border-line bg-surface p-8 shadow-lg"
        autoComplete="on"
      >
        {topError && (
          <div className="mb-4 flex items-center gap-2 rounded-md bg-error-50 px-3 py-2 text-sm text-error-700">
            <AlertCircle className="size-4 shrink-0" />
            <span>{topError}</span>
          </div>
        )}

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="username">用户名</Label>
            <div className="relative">
              <User className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
              <Input id="username" className="pl-9" placeholder="请输入用户名" autoComplete="username" {...register('username')} />
            </div>
            {errors.username && <p className="text-sm text-error-600">{errors.username.message}</p>}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="password">密码</Label>
            <div className="relative">
              <Lock className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
              <Input
                id="password"
                className="px-9"
                type={showPwd ? 'text' : 'password'}
                placeholder="请输入密码"
                autoComplete="current-password"
                {...register('password')}
              />
              <button
                type="button"
                onClick={() => setShowPwd((v) => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-faint transition-colors hover:text-muted"
                aria-label={showPwd ? '隐藏密码' : '显示密码'}
              >
                {showPwd ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
            {errors.password && <p className="text-sm text-error-600">{errors.password.message}</p>}
          </div>

          <Button type="submit" className="h-11 w-full" disabled={!isValid}>
            登录
          </Button>

          <p className="text-center text-sm text-muted">
            还没有账号？{' '}
            <Link to="/auth/register" className="text-link hover:underline">
              注册
            </Link>
          </p>
        </div>
      </form>

      {/* 图形验证弹窗（修复 #1：点登录后弹出） */}
      <Dialog
        open={captchaOpen}
        onOpenChange={(o) => {
          setCaptchaOpen(o)
          if (!o) {
            setSubmitting(false)
            setDialogError(null)
            credsRef.current = null
          }
        }}
      >
        <DialogContent className="max-w-[380px]" onPointerDownOutside={(e) => submitting && e.preventDefault()}>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ShieldCheck className="size-5 text-primary-600" /> 完成安全验证
            </DialogTitle>
            <DialogDescription>拖动滑块，使拼图块对齐缺口后松手即可登录</DialogDescription>
          </DialogHeader>

          {dialogError && (
            <div className="flex items-center gap-2 rounded-md bg-error-50 px-3 py-2 text-sm text-error-700">
              <AlertCircle className="size-4 shrink-0" />
              <span>{dialogError}</span>
            </div>
          )}

          <div className="flex flex-col items-center gap-3 py-2">
              <SliderCaptcha
                ref={captchaRef}
                onVerified={(id, code) => void onVerified(id, code)}
                onReset={() => {}}
              />
            {submitting && <p className="text-sm text-subtle">正在登录…</p>}
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}
