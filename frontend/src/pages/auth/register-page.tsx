import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQueryClient } from '@tanstack/react-query'
import { User, Mail, Lock, Smile, Eye, EyeOff, AlertCircle } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { SliderCaptcha, type SliderCaptchaHandle } from '@/components/auth/slider-captcha'
import { useRegister, fetchMe, authKeys } from '@/api/auth'
import { useAuthStore } from '@/stores/auth-store'
import { ROLE } from '@/lib/constants'
import type { ApiError } from '@/types/api'

// 校验与后端注解对齐（RegisterRequest：username 2-50 / password 8-72 / email / nickname ≤50）
const schema = z
  .object({
    username: z.string().min(2, '用户名长度必须为2到50个字符').max(50, '用户名长度必须为2到50个字符'),
    nickname: z.string().max(50, '昵称最多50个字符').optional().or(z.literal('')),
    email: z.string().email('邮箱格式不正确').max(100, '邮箱过长'),
    password: z.string().min(8, '密码长度必须为8到72个字符').max(72, '密码长度必须为8到72个字符'),
    confirmPassword: z.string(),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: '两次密码不一致',
    path: ['confirmPassword'],
  })
type FormValues = z.infer<typeof schema>

export default function RegisterPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const captchaRef = useRef<SliderCaptchaHandle>(null)
  const [captcha, setCaptcha] = useState<{ id: string; code: number } | null>(null)
  const [showPwd, setShowPwd] = useState(false)
  const [topError, setTopError] = useState<string | null>(null)
  const registerMut = useRegister()
  // FE-010：注册即登录后不再手动 setUser；走订阅链路（AppDataLoader）写入 store，
  // 本组件订阅 user 做响应式导航（与 login-page 一致）。
  const user = useAuthStore((s) => s.user)
  const pendingNavRef = useRef(false)

  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { username: '', nickname: '', email: '', password: '', confirmPassword: '' },
  })

  // FE-010：注册成功后置位 pendingNavRef；待 user 入 store 后再导航
  useEffect(() => {
    if (!pendingNavRef.current || !user) return
    pendingNavRef.current = false
    const isAdmin = user.roles?.includes(ROLE.ADMIN)
    void navigate(isAdmin ? '/admin' : '/app/chat', { replace: true })
  }, [user, navigate])

  async function onSubmit(values: FormValues) {
    if (!captcha) return
    setTopError(null)
    try {
      // 注册即登录（AUTH-3：issueTokensAndPersist 签发 token）
      await registerMut.mutateAsync({
        username: values.username,
        password: values.password,
        email: values.email,
        nickname: values.nickname || undefined,
        captchaId: captcha.id,
        // 后端 captchaCode 为 @NotBlank String
        captchaCode: String(captcha.code),
      })
      // FE-010：拉 /me 兜底并温暖 RQ 缓存，订阅链路统一写 store；导航由上面的 effect 执行
      const me = await fetchMe()
      qc.setQueryData(authKeys.me, me)
      pendingNavRef.current = true
    } catch (e) {
      captchaRef.current?.fail()
      setTopError((e as ApiError).message || '注册失败，请重试')
    }
  }

  return (
    <form onSubmit={(e) => void handleSubmit(onSubmit)(e)} className="rounded-3xl border border-line bg-surface p-8 shadow-lg">
      {topError && (
        <div className="mb-4 flex items-center gap-2 rounded-md bg-error-50 px-3 py-2 text-sm text-error-700">
          <AlertCircle className="size-4 shrink-0" />
          <span>{topError}</span>
        </div>
      )}

      <div className="space-y-4">
        <Field id="username" label="用户名" placeholder="2-50 字符" error={errors.username?.message}
          icon={<User className="size-4" />} register={register('username')} />

        <Field id="nickname" label="昵称（选填）" placeholder="选填，最多 50 字符" error={errors.nickname?.message}
          icon={<Smile className="size-4" />} register={register('nickname')} />

        <Field id="email" label="邮箱" placeholder="name@example.com" error={errors.email?.message}
          icon={<Mail className="size-4" />} register={register('email')} />

        <div className="space-y-1.5">
          <Label htmlFor="password">密码</Label>
          <div className="relative">
            <Lock className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
            <Input id="password" className="px-9" type={showPwd ? 'text' : 'password'} placeholder="8-72 字符" autoComplete="new-password" {...register('password')} />
            <button type="button" onClick={() => setShowPwd((v) => !v)} className="absolute right-3 top-1/2 -translate-y-1/2 text-faint hover:text-muted" aria-label="显示密码">
              {showPwd ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </button>
          </div>
          {errors.password && <p className="text-sm text-error-600">{errors.password.message}</p>}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="confirmPassword">确认密码</Label>
          <div className="relative">
            <Lock className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
            <Input id="confirmPassword" className="pl-9" type={showPwd ? 'text' : 'password'} placeholder="再次输入密码" autoComplete="new-password" {...register('confirmPassword')} />
          </div>
          {errors.confirmPassword && <p className="text-sm text-error-600">{errors.confirmPassword.message}</p>}
        </div>

        <div className="flex justify-center">
          <SliderCaptcha ref={captchaRef} onVerified={(id, code) => setCaptcha({ id, code })} onReset={() => setCaptcha(null)} />
        </div>

        <Button type="submit" className="h-11 w-full" loading={registerMut.isPending} disabled={!captcha}>
          注册
        </Button>

        <p className="text-center text-sm text-muted">
          已有账号？{' '}
          <Link to="/auth/login" className="text-link hover:underline">
            登录
          </Link>
        </p>
      </div>
    </form>
  )
}

/** 单字段（含前置图标） */
function Field({
  id, label, placeholder, error, icon, register,
}: {
  id: string
  label: string
  placeholder?: string
  error?: string
  icon: React.ReactNode
  register: ReturnType<ReturnType<typeof useForm<FormValues>>['register']>
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <div className="relative">
        <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint">{icon}</span>
        <Input id={id} className="pl-9" placeholder={placeholder} {...register} />
      </div>
      {error && <p className="text-sm text-error-600">{error}</p>}
    </div>
  )
}
