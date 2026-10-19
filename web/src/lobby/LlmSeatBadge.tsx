import { useTranslation } from '../i18n'
import Chip, { type ChipTone } from '../ui/Chip'
import type { LlmCatalog, LlmSeatInfo } from '../net/types'

export default function LlmSeatBadge({ info, catalog, testId }: {
  info: LlmSeatInfo
  catalog: LlmCatalog | null
  testId?: string
}) {
  const { t } = useTranslation()
  const model = catalog?.providers?.find((p) => p.id === info.provider)?.models.find((m) => m.id === info.model)?.name ?? info.model
  const detail = `${model} · ${info.effort}`
  let label = detail
  let tone: ChipTone = 'brand'
  if (info.status === 'starting') {
    label = t('lobby', 'staging_llm_starting', { name: info.username })
    tone = 'neutral'
  } else if (info.status === 'failed') {
    label = t('lobby', 'staging_llm_failed', { name: info.username })
    tone = 'err'
  } else if (info.status === 'stopped') {
    label = t('lobby', 'staging_llm_stopped', { name: info.username })
    tone = 'warn'
  }
  const title = [t('lobby', 'staging_llm_seat_title'), detail, info.reason].filter(Boolean).join(' · ')
  return (
    <Chip tone={tone} size="xs" pill icon={info.status === 'failed' ? 'alert' : 'bot'} title={title} data-testid={testId} data-status={info.status}>
      {label}
    </Chip>
  )
}
