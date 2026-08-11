import { ShieldAlert } from 'lucide-react'
import { ErrorLayout } from './error-layout'

export default function ForbiddenPage() {
  return (
    <ErrorLayout
      icon={<ShieldAlert />}
      title="没有访问权限"
      description="你当前的角色无法访问此页面，请联系管理员获取相应权限。"
    />
  )
}
