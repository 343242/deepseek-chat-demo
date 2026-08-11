import { ServerCrash } from 'lucide-react'
import { ErrorLayout } from './error-layout'

export default function ServerErrorPage() {
  return (
    <ErrorLayout
      icon={<ServerCrash />}
      title="服务暂时不可用"
      description="服务器开小差了，请稍后重试。如果问题持续，请联系管理员。"
    />
  )
}
