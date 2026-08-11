import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Toaster } from '@/components/ui/sonner'
import { queryClient } from '@/lib/query-client'
import { AppDataLoader } from '@/components/guards/app-data-loader'
import { App } from './App'

// 设计系统字体（DS §5.1：Inter + JetBrains Mono，中文系统兜底）
import '@fontsource-variable/inter'
import '@fontsource/jetbrains-mono'
import './app.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider delayDuration={500}>
        <BrowserRouter>
          <AppDataLoader>
            <App />
          </AppDataLoader>
        </BrowserRouter>
        <Toaster />
      </TooltipProvider>
    </QueryClientProvider>
  </StrictMode>,
)
