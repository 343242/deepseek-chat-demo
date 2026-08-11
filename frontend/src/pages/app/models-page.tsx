import { Cpu } from 'lucide-react'
import { PagePlaceholder } from '@/components/common/page-placeholder'
// 模型配置：预留接口（IA §2.3 v0.3.0 收回 USER，仅 ADMIN）
export default function ModelsPage() {
  return <PagePlaceholder icon={Cpu} title="模型配置" description="模型参数配置（预留接口），待 IA-8 个人化落地后开放。" />
}
