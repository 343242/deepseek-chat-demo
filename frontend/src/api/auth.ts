import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import { useAuthStore } from '@/stores/auth-store'
import type { CaptchaResult, LoginRequest, LoginResponse, RegisterRequest, UserInfo } from '@/types/auth'

/** GET /api/auth/me —— 权限兜底权威入口（IA-5：登录响应 permissions 可能为空，须立即调 /me）。
 *  会话用户不经 Query 缓存中转：写入 useAuthStore 由调用方在事件边界完成
 *  （AppDataLoader 启动装载 / 登录注册成功时直写）。 */
export function fetchMe() {
  return api.get<UserInfo>('/auth/me')
}

/** GET /api/auth/captcha —— 滑块验证码 */
export function getCaptcha() {
  return api.get<CaptchaResult>('/auth/captcha')
}

export function useLogin() {
  return useMutation({
    mutationFn: (req: LoginRequest) => api.post<LoginResponse>('/auth/login', req),
  })
}

export function useRegister() {
  return useMutation({
    mutationFn: (req: RegisterRequest) => api.post<LoginResponse>('/auth/register', req),
  })
}

export function useLogout() {
  const qc = useQueryClient()
  const clear = useAuthStore((s) => s.clear)
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSettled: () => {
      clear()
      qc.clear()
    },
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (req: { oldPassword: string; newPassword: string }) =>
      api.post<void>('/auth/me/password', req),
  })
}
