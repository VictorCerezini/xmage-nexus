import { useTranslation } from '../../i18n'
import Field from '../../ui/Field'
import Icon from '../../ui/Icon'
import type { LlmProvider } from '../../net/types'
import type { LlmSeatChoice } from './llmSeats'

export default function LlmSeatFields({ idx, provider, choice, onModel, onEffort }: {
  idx: number
  provider: LlmProvider
  choice: LlmSeatChoice
  onModel: (modelId: string) => void
  onEffort: (effort: string) => void
}) {
  const { t } = useTranslation()
  const efforts = provider.models.find((m) => m.id === choice.model)?.efforts ?? []
  const quota = provider.freeRequestsToday
  return (
    <>
      <Field label={t('lobby', 'create_seat_llm_model')}>
        <select data-testid={`seat-llm-model-${idx}`} value={choice.model} onChange={(e) => onModel(e.target.value)} className="create-seat-select">
          {provider.models.map((m) => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>
      </Field>
      <Field label={t('lobby', 'create_seat_llm_effort')}>
        <select data-testid={`seat-llm-effort-${idx}`} value={choice.effort} onChange={(e) => onEffort(e.target.value)} disabled={efforts.length <= 1} className="create-seat-select">
          {efforts.map((effort) => (
            <option key={effort} value={effort}>{effort}</option>
          ))}
        </select>
      </Field>
      <span className="wizard-hint-box"><Icon name="bot" size={11} /> {t('lobby', 'create_seat_llm_hint', { auth: provider.auth })}</span>
      {typeof quota?.remaining === 'number' && typeof quota.limit === 'number' && (
        <span className="wizard-hint-box">{t('lobby', 'create_seat_llm_free_left', { remaining: quota.remaining, limit: quota.limit })}</span>
      )}
    </>
  )
}
