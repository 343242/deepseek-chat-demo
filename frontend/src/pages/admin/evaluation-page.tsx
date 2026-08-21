import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { FlaskConical } from 'lucide-react'
import { PageContainer } from '@/components/common/page-placeholder'
import { EmptyState } from '@/components/common/empty-state'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { DatasetList } from '@/components/evaluation/dataset-list'
import { DatasetDetailTab } from '@/components/evaluation/dataset-detail-tab'
import { RunsTab } from '@/components/evaluation/runs-tab'
import { CompareTab } from '@/components/evaluation/compare-tab'
import { StartRunDialog } from '@/components/evaluation/start-run-dialog'
import { RunProgressPanel } from '@/components/evaluation/run-progress-panel'
import { evalKeys, useDataset } from '@/api/evaluation'
import type { EvalRun } from '@/types/evaluation'

const TAB_VALUES = ['detail', 'runs', 'compare'] as const
type TabValue = (typeof TAB_VALUES)[number]

function parseIdParam(value: string | null): number | null {
  return value != null && /^\d+$/.test(value) ? Number(value) : null
}

/**
 * 评估工作台（线框 09 v0.1.1）——左数据集列表 + 右三 Tab 工作区。
 * 选中态持久化 URL query（?dataset=&tab=&run=），刷新可恢复（线框 §1）；
 * 启动 Modal 与 SSE 进度面板为临时浮层不入 URL，由页面统一持有以便跨 Tab 联动。
 */
export default function EvaluationPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const qc = useQueryClient()

  const datasetId = parseIdParam(searchParams.get('dataset'))
  const tabParam = searchParams.get('tab')
  const tab: TabValue = TAB_VALUES.includes(tabParam as TabValue) ? (tabParam as TabValue) : 'detail'
  const selectedRunId = parseIdParam(searchParams.get('run'))

  const [startRunOpen, setStartRunOpen] = useState(false)
  const [progressRun, setProgressRun] = useState<EvalRun | null>(null)

  const patchParams = useCallback(
    (patch: Record<string, string | null>) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const [k, v] of Object.entries(patch)) {
            if (v == null) next.delete(k)
            else next.set(k, v)
          }
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  function selectDataset(id: number) {
    patchParams({ dataset: String(id), tab: null, run: null })
  }

  function handleStarted(runId: number) {
    // 202 返回即切到运行 Tab 并打开进度面板（线框 §3.1）
    patchParams({ tab: 'runs', run: null })
    setProgressRun({ id: runId })
  }

  function handleProgressFinished(failed: boolean) {
    const runId = progressRun?.id ?? null
    if (datasetId != null) void qc.invalidateQueries({ queryKey: evalKeys.runs(datasetId) })
    if (!failed && runId != null) patchParams({ tab: 'runs', run: String(runId) })
    setProgressRun(null)
  }

  const dataset = useDataset(datasetId)
  const datasetName = dataset.data?.name ?? (datasetId != null ? `数据集 ${datasetId}` : '')

  return (
    <PageContainer title="评估" subtitle="数据集管理 · 评测运行 · 结果对比——RAG 质量闭环">
      <div className="flex min-h-0 flex-1 gap-4">
        <DatasetList selectedId={datasetId} onSelect={selectDataset} />

        <div className="min-w-0 flex-1">
          {datasetId == null ? (
            <EmptyState
              icon={<FlaskConical />}
              title="选择或生成一个数据集"
              description="左侧选择数据集，或点击「生成」从测试用户知识库合成问答对"
              className="h-full"
            />
          ) : (
            <Tabs value={tab} onValueChange={(v) => patchParams({ tab: v, run: null })} className="flex min-h-0 flex-col">
              <TabsList className="mb-3 self-start">
                <TabsTrigger value="detail">数据集详情</TabsTrigger>
                <TabsTrigger value="runs">评测运行</TabsTrigger>
                <TabsTrigger value="compare">结果对比</TabsTrigger>
              </TabsList>

              {progressRun?.id != null && (
                <div className="mb-4">
                  <RunProgressPanel
                    runId={progressRun.id}
                    runName={progressRun.name ?? `运行 ${progressRun.id}`}
                    onBackground={() => setProgressRun(null)}
                    onFinished={handleProgressFinished}
                  />
                </div>
              )}

              <TabsContent value="detail" className="min-h-0 flex-1">
                <DatasetDetailTab datasetId={datasetId} onStartRun={() => setStartRunOpen(true)} />
              </TabsContent>
              <TabsContent value="runs" className="min-h-0 flex-1">
                <RunsTab
                  datasetId={datasetId}
                  selectedRunId={selectedRunId}
                  onSelectRun={(runId) => patchParams({ run: runId == null ? null : String(runId) })}
                  onViewProgress={setProgressRun}
                  onStartRun={() => setStartRunOpen(true)}
                />
              </TabsContent>
              <TabsContent value="compare" className="min-h-0 flex-1">
                <CompareTab datasetId={datasetId} />
              </TabsContent>
            </Tabs>
          )}
        </div>
      </div>

      {datasetId != null && (
        <StartRunDialog
          open={startRunOpen}
          onOpenChange={setStartRunOpen}
          datasetId={datasetId}
          datasetName={datasetName}
          onStarted={handleStarted}
        />
      )}
    </PageContainer>
  )
}
