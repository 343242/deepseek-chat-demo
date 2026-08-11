import { Sun, Moon } from 'lucide-react'
import { useTheme } from '@/hooks/use-theme'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

/** 主题切换（DS §5.2 TopBar 右侧） */
export function ThemeToggle() {
  const { resolved, toggle } = useTheme()
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon" onClick={toggle} aria-label="切换主题">
          {resolved === 'dark' ? <Sun /> : <Moon />}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{resolved === 'dark' ? '切换到亮色' : '切换到暗色'}</TooltipContent>
    </Tooltip>
  )
}
