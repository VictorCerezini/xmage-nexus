import { useState } from 'react'
import { useTranslation } from '../../i18n'
import Button from '../../ui/Button'
import Icon from '../../ui/Icon'
import type { LlmUnavailable } from '../../net/types'
import { copyText } from '../../utils/clipboard'
import { desktopInvoke } from '../../system/desktopApp'

/** Why a model seat provider cannot play here yet and the command that fixes it: one that saves a missing key,
 *  or for a sign-in program (Claude Code) the subscription sign-in, which the desktop app can open (LBY-33). */
export default function LlmUnreadyNote({ id, entry, refreshing, onRefresh }: {
  id: string
  entry: LlmUnavailable
  refreshing: boolean
  onRefresh: () => void
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const [windowFailed, setWindowFailed] = useState(false)
  const signIn = entry.signIn
  const name = entry.playerName ?? entry.id
  const program = signIn ? entry.signInProgram : undefined
  const shell = entry.signInShell ?? 'PowerShell'
  // only the desktop app can open a window on this computer; a browser gets the command to copy
  const invoke = program ? desktopInvoke() : null
  const openWindow = invoke && program
    ? () => {
        setWindowFailed(false)
        invoke('open_claude_sign_in', { program }).catch(() => setWindowFailed(true))
      }
    : null

  let steps: string | null = null
  if (openWindow) steps = t('lobby', 'create_llm_login_steps_app', { name })
  else if (program) steps = t('lobby', 'create_llm_login_steps', { name, shell })
  else if (signIn) {
    steps = entry.signInShell
      ? t('lobby', 'create_llm_unready_sign_in_shell', { shell: entry.signInShell })
      : t('lobby', 'create_llm_unready_sign_in')
  }

  return (
    <div className="wizard-hint-box seat-llm-unready" id={id} data-testid={id}>
      <strong><Icon name="lock" size={11} /> {t('lobby', 'create_llm_unready_title', { name })}</strong>
      {entry.reason && <span>{entry.reason}</span>}
      {steps && <span data-testid={`${id}-steps`}>{steps}</span>}
      {windowFailed && <span role="alert" data-testid={`${id}-window-failed`}>{t('lobby', 'create_llm_login_failed')}</span>}
      {signIn && (
        <>
          {openWindow && <span>{t('lobby', 'create_llm_login_or_shell', { shell })}</span>}
          <code className="seat-llm-sign-in" data-testid={`${id}-command`}>{signIn}</code>
        </>
      )}
      <div className="seat-llm-unready-actions">
        {openWindow && (
          <Button variant="primary" size="sm" icon="user" data-testid={`${id}-sign-in`} onClick={openWindow}>
            {t('lobby', 'create_llm_login_button')}
          </Button>
        )}
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
