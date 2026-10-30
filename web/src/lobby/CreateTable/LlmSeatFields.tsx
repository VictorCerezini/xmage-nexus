import { useTranslation } from '../../i18n'
import Field from '../../ui/Field'
import Icon from '../../ui/Icon'
import type { LlmProvider } from '../../net/types'
import { llmBackupValue, type LlmSeatChoice } from './llmSeats'

export default function LlmSeatFields({ idx, provider, providers, choice, onModel, onEffort, onBackup }: {
  idx: number
  provider: LlmProvider
  /** Every signed-in provider: any of their models may back this seat up. */
  providers: LlmProvider[]
  choice: LlmSeatChoice
  onModel: (modelId: string) => void
  onEffort: (effort: string) => void
  onBackup: (modelId: string) => void
}) {
  const { t } = useTranslation()
  const efforts = provider.models.find((m) => m.id === choice.model)?.efforts ?? []
  const quota = provider.freeRequestsToday
  const backup = choice.backupModel ? llmBackupValue(choice.backupProvider ?? choice.provider, choice.backupModel) : ''
  const backupGroups = providers
    .map((p) => ({ provider: p, models: p.models.filter((m) => p.id !== choice.provider || m.id !== choice.model) }))
    .filter((group) => group.models.length > 0)
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
      {backupGroups.length > 0 && (
        <Field label={t('lobby', 'create_seat_llm_backup')} hint={t('lobby', 'create_seat_llm_backup_hint')}>
          <select data-testid={`seat-llm-backup-${idx}`} value={backup} onChange={(e) => onBackup(e.target.value)} className="create-seat-select">
            <option value="">{t('lobby', 'create_seat_llm_backup_none')}</option>
            {backupGroups.map((group) => (
              <optgroup key={group.provider.id} label={group.provider.playerName}>
                {group.models.map((m) => (
                  <option key={m.id} value={llmBackupValue(group.provider.id, m.id)}>{m.name}</option>
                ))}
              </optgroup>
            ))}
          </select>
        </Field>
      )}
      <span className="wizard-hint-box"><Icon name="bot" size={11} /> {t('lobby', 'create_seat_llm_hint', { auth: provider.auth })}</span>
      {typeof quota?.remaining === 'number' && typeof quota.limit === 'number' && (
        <span className="wizard-hint-box">{t('lobby', 'create_seat_llm_free_left', { remaining: quota.remaining, limit: quota.limit })}</span>
      )}
    </>
  )
}
