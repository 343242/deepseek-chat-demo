import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { User, Lock, Eye, EyeOff, AlertCircle } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { SliderCaptcha, type SliderCaptchaHandle } from '@/components/auth/slider-captcha'
import { useLogin } from '@/api/auth'
import { api } from '@/lib/api-fetch'
import { useAuthStore } from '@/stores/auth-store'
import { ROLE, ERROR_CODE } from '@/lib/constants'
import type { UserInfo } from '@/types/auth'
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
  const captchaRef = useRef<SliderCaptchaHandle>(null)
  const [captcha, setCaptcha] = useState<{ id: string; code: number } | null>(null)
  const [showPwd, setShowPwd] = useState(false)
  const [topError, setTopError] = useState<string | null>(null)
  const login = useLogin()
  const setUser = useAuthStore((s) => s.setUser)

  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { username: '', password: '' },
  })

  const verified = !!captcha

  async function onSubmit(values: FormValues) {
    if (!captcha) return
    setTopError(null)
    try {
      await login.mutateAsync({
        username: values.username,
        password: values.password,
        captchaId: captcha.id,
        captchaCode: captcha.code,
      })
      // 登录响应 permissions 可能为空，立即调 /me 兜底（IA-5）
      const me = await api.get<UserInfo>('/auth/me')
      setUser(me)

      const redirect = safeRedirect(params.get('redirect'))
      if (redirect) return navigate(redirect, { replace: true })
      const isAdmin = me.roles?.includes(ROLE.ADMIN)
      navigate(isAdmin ? '/admin' : '/app/chat', { replace: true })
    } catch (e) {
      const err = e as ApiError
      // 验证码已消费，刷新；凭据错误清空密码
      captchaRef.current?.fail()
      if (err.code === ERROR_CODE.RATE_LIMIT) {
        setTopError(err.message || '登录尝试过于频繁，请稍后再试')
      } else {
        setTopError(err.message || '用户名或密码错误')
      }
    }
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="rounded-3xl border border-line bg-surface p-8 shadow-lg"
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

        <div className="flex justify-center">
          <SliderCaptcha
            ref={captchaRef}
            onVerified={(id, code) => setCaptcha({ id, code })}
            onReset={() => setCaptcha(null)}
          />
        </div>

        <Button type="submit" className="h-11 w-full" loading={login.isPending} disabled={!verified}>
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
  )
}
