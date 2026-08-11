import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import { useAuthStore } from '@/stores/auth-store'
import type { CaptchaResult, LoginRequest, LoginResponse, RegisterRequest, UserInfo } from '@/types/auth'

export const authKeys = {
  me: ['auth', 'me'] as const,
}

/** GET /api/auth/me —— 权限兜底权威入口（IA-5：登录响应 permissions 可能为空，须立即调 /me） */
export function useMe(options?: { enabled?: boolean }) {
  const setUser = useAuthStore((s) => s.setUser)
  return useQuery({
    queryKey: authKeys.me,
    queryFn: async () => {
      const data = await api.get<UserInfo>('/auth/me')
      setUser(data)
      return data
    },
    enabled: options?.enabled ?? true,
    retry: false,
    staleTime: 60_000,
    meta: { silent401: true },
  })
}

/** GET /api/auth/captcha —— 滑块验证码 */
export function getCaptcha() {
  return api.get<CaptchaResult>('/auth/captcha')
}

export function useLogin() {
  const qc = useQueryClient()
  const setUser = useAuthStore((s) => s.setUser)
  return useMutation({
    mutationFn: (req: LoginRequest) => api.post<LoginResponse>('/auth/login', req),
    onSuccess: (data) => {
      // 登录响应 permissions 可能为空，写 user 但标记未初始化；由调用方立即触发 /me 兜底
      setUser({ ...data.user, permissions: data.user.permissions ?? [] })
      qc.invalidateQueries({ queryKey: authKeys.me })
    },
  })
}

export function useRegister() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: RegisterRequest) => api.post<LoginResponse>('/auth/register', req),
    onSuccess: () => qc.invalidateQueries({ queryKey: authKeys.me }),
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
