import { useNavigate } from 'react-router'
import { ChevronDown } from 'lucide-react'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'
import { useTeams } from '@/api/teams'

/** 个人 / 团队作用域切换（wireframe §2） */
export function ScopeTabs({ scope, activeTeamId }: { scope: 'personal' | 'team'; activeTeamId: number | null }) {
  const navigate = useNavigate()
  const { data: teams } = useTeams()
  const activeTeam = teams?.find((t) => t.id === activeTeamId)

  return (
    <div className="flex items-center gap-2">
      <Tabs value={scope}>
        <TabsList>
          <TabsTrigger value="personal" onClick={() => void navigate('/app/knowledge/personal')}>
            个人文档
          </TabsTrigger>
          {scope === 'team' ? (
            <TabsTrigger value="team">
              {activeTeam?.name ?? '团队文档'}
            </TabsTrigger>
          ) : (
            <TabsTrigger value="team">团队文档</TabsTrigger>
          )}
        </TabsList>
      </Tabs>

      {/* 团队下拉选择器 */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" disabled={!teams?.length}>
            <ChevronDown className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {teams?.length === 0 && <DropdownMenuItem disabled>暂无团队</DropdownMenuItem>}
          {teams?.map((t) => (
            <DropdownMenuItem key={t.id} onClick={() => void navigate(`/app/knowledge/team/${t.id}`)}>
              {t.name}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}
