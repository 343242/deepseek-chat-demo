/** 认证相关契约 —— 来源 user/dto/{LoginRequest,RegisterRequest,LoginResponse}.java · infrastructure/web/dto/CaptchaResult.java */

export interface UserInfo {
  id: number
  username: string
  nickname?: string
  email?: string
  avatar?: string
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  user: UserInfo
}

/** 登录请求（LoginRequest: username/password/captchaId/captchaCode） */
export interface LoginRequest {
  username: string
  password: string
  captchaId: string
  captchaCode: number
}

/** 注册请求（RegisterRequest: username/password/email/nickname/captchaId/captchaCode） */
export interface RegisterRequest {
  username: string
  password: string
  email: string
  nickname?: string
  captchaId: string
  captchaCode: number
}

/** 滑块验证码（CaptchaResult） */
export interface CaptchaResult {
  captchaId: string
  /** 310×155 背景 base64 PNG（含缺口） */
  backgroundImage: string
  /** ≈79×79 拼图块 base64 PNG */
  puzzleImage: string
  /** 正确 x 坐标（仅 dev，生产为 null） */
  answer?: number | null
}
