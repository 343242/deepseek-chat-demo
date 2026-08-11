/** 团队相关契约（最小集，供知识库团队切换器与团队页占位用） */

/** 团队成员角色（TeamMemberRole，DS §4.4.5） */
export type TeamMemberRole = 'CREATOR' | 'ADMIN' | 'MEMBER'

/** 审批状态（ApprovalStatus，DS §4.4.4） */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface Team {
  id: number
  name: string
  description?: string
  memberCount?: number
  role?: TeamMemberRole
}

export interface ApprovalVO {
  id: number
  documentId: number
  fileName: string
  fileSize: number
  uploaderId: number
  uploaderName?: string
  status: ApprovalStatus
  reviewerId?: number | null
  reviewerName?: string | null
  reviewComment?: string | null
  createdAt: string
  reviewedAt?: string | null
}
