import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { STORAGE_KEYS } from '@/lib/constants'

interface UiState {
  /** 前台侧栏折叠（IA §5.1：前台固定 280px 不折叠，此值仅作预留/记忆，当前不启用折叠） */
  appSidebarCollapsed: boolean
  adminSidebarCollapsed: boolean
  /** 当前活跃团队（知识库团队模式 / 团队详情共享上下文，IA-3） */
  activeTeamId: number | null
  toggleAppSidebar: () => void
  toggleAdminSidebar: () => void
  setActiveTeam: (id: number | null) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      appSidebarCollapsed: false,
      adminSidebarCollapsed: false,
      activeTeamId: null,
      toggleAppSidebar: () => set((s) => ({ appSidebarCollapsed: !s.appSidebarCollapsed })),
      toggleAdminSidebar: () =>
        set((s) => ({ adminSidebarCollapsed: !s.adminSidebarCollapsed })),
      setActiveTeam: (id) => set({ activeTeamId: id }),
    }),
    {
      name: 'srag.ui',
      partialize: (s) => ({
        appSidebarCollapsed: s.appSidebarCollapsed,
        adminSidebarCollapsed: s.adminSidebarCollapsed,
        activeTeamId: s.activeTeamId,
      }),
    },
  ),
)

/** 前后台最近访问页（IA-4：localStorage 区分前台/后台） */
export const lastPage = {
  get(shell: 'app' | 'admin'): string | null {
    return localStorage.getItem(shell === 'app' ? STORAGE_KEYS.lastAppPage : STORAGE_KEYS.lastAdminPage)
  },
  set(shell: 'app' | 'admin', path: string) {
    localStorage.setItem(shell === 'app' ? STORAGE_KEYS.lastAppPage : STORAGE_KEYS.lastAdminPage, path)
  },
}
