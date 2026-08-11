import { Toaster as Sonner, type ToasterProps } from 'sonner'

/** Toast 全局提示（DS §10.14）—— sonner，中文/品牌主题 */
export function Toaster(props: ToasterProps) {
  return (
    <Sonner
      position="top-right"
      richColors={false}
      closeButton
      duration={3500}
      toastOptions={{
        classNames: {
          toast:
            'group !rounded-lg !border !border-line !bg-surface !text-fg !shadow-lg !p-3 data-[type=success]:!border-success-600/40 data-[type=warning]:!border-warning-600/40 data-[type=error]:!border-error-600/40',
          title: '!text-sm !font-medium',
          description: '!text-sm !text-muted',
          actionButton: '!bg-primary-600 !text-inv',
          cancelButton: '!bg-base !text-muted',
        },
      }}
      {...props}
    />
  )
}

/** 便捷 toast（success/warning/error/info） */
export { toast } from 'sonner'
