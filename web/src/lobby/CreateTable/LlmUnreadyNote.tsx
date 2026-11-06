import { useState } from 'react'
import { useTranslation } from '../../i18n'
import Button from '../../ui/Button'
import Icon from '../../ui/Icon'
import type { LlmUnavailable } from '../../net/types'
import { copyText } from '../../utils/clipboard'

/** Why a model seat provider cannot play here yet and, for a missing key, the command that saves one. */
export default function LlmUnreadyNote({ id, entry, refreshing, onRefresh }: {
  id: string
  entry: LlmUnavailable
  refreshing: boolean
  onRefresh: () => void
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const signIn = entry.signIn
  return (
    <div className="wizard-hint-box seat-llm-unready" id={id} data-testid={id}>
      <strong><Icon name="lock" size={11} /> {t('lobby', 'create_llm_unready_title', { name: entry.playerName ?? entry.id })}</strong>
      {entry.reason && <span>{entry.reason}</span>}
      {signIn && (
        <>
          <span>
            {entry.signInShell
              ? t('lobby', 'create_llm_unready_sign_in_shell', { shell: entry.signInShell })
              : t('lobby', 'create_llm_unready_sign_in')}
          </span>
          <code className="seat-llm-sign-in" data-testid={`${id}-command`}>{signIn}</code>
        </>
      )}
      <div className="seat-llm-unready-actions">
        {signIn && (
          <Button
            variant="ghost"
            size="sm"
            icon={copied ? 'check' : 'copy'}
            data-testid={`${id}-copy`}
            onClick={() => { void copyText(signIn).then(setCopied) }}
          >
            {copied ? t('lobby', 'create_llm_copied') : t('lobby', 'create_llm_copy')}
          </Button>
        )}
        <Button variant="ghost" size="sm" icon="refresh" data-testid={`${id}-refresh`} disabled={refreshing} onClick={onRefresh}>
          {t('lobby', 'create_llm_refresh')}
        </Button>
      </div>
    </div>
  )
}
