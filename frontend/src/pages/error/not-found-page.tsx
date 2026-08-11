import { Compass } from 'lucide-react'
import { ErrorLayout } from './error-layout'

export default function NotFoundPage() {
  return (
    <ErrorLayout
      icon={<Compass />}
      title="页面不存在"
      description="你访问的页面可能已被移动或删除，请检查地址是否正确。"
    />
  )
}
