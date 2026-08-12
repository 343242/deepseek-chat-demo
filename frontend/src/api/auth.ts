import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import { useAuthStore } from '@/stores/auth-store'
import type { CaptchaResult, LoginRequest, LoginResponse, RegisterRequest, UserInfo } from '@/types/auth'

export const authKeys = {
  me: ['auth', 'me'] as const,
}

/** GET /api/auth/me —— 权限兜底权威入口（IA-5：登录响应 permissions 可能为空，须立即调 /me）。
 *  FE-010：纯取数，不再在 queryFn 内写 store；store 写入由 AppDataLoader 订阅 me.data 统一完成。 */
export function fetchMe() {
  return api.get<UserInfo>('/auth/me')
}

export function useMe(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: fetchMe,
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
  return useMutation({
    mutationFn: (req: LoginRequest) => api.post<LoginResponse>('/auth/login', req),
    onSuccess: () => {
      // FE-010：不在此写 store；login-page 登录后会 setQueryData(authKeys.me) 温暖订阅源，
      // 由 AppDataLoader 订阅链路统一 setUser。
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
