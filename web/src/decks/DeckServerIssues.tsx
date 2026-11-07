import CloseButton from '../ui/CloseButton'
import Button from '../ui/Button'
import { useEffect, useState } from 'react'
import { useTranslation } from '../i18n'
import Icon from '../ui/Icon'
import type { ServerIssueItem } from './useDeckValidation'

interface Props {
  issues: ServerIssueItem[]
}

const itemKey = (it: ServerIssueItem) => `${it.kind}|${it.name}|${it.set}|${it.num}`

/**
 * Cards in this deck the server cannot play: not implemented, or loaded as another card with no
 * printing of the right one. A card the server only lacks in this printing is not listed: the app
 * swaps the printing by itself when the deck is sent and keeps the deck's art (`deckIssues.ts`).
 */
export default function DeckServerIssues({ issues }: Props) {
  const { t } = useTranslation()
  const [dismissed, setDismissed] = useState(false)
  const [accepted, setAccepted] = useState<Set<string>>(() => new Set())
  useEffect(() => {
    setDismissed(false)
  }, [issues])
  const visible = issues.filter((it) => !accepted.has(itemKey(it)))
  if (visible.length === 0 || dismissed) return null
  const accept = (it: ServerIssueItem) => setAccepted((prev) => new Set(prev).add(itemKey(it)))
  return (
    <div className="builder-server-issues" data-testid="builder-server-issues" role="status" aria-live="polite">
      <div className="bsi-title">
        <span><Icon name="alert" size={14} /> {t('decks', 'issues_banner_title')}</span>
      </div>
      <CloseButton variant="plain" size="sm" className="bsi-close" onClick={() => setDismissed(true)} />
      <ul>
        {visible.map((it, i) => (
          <li key={`${itemKey(it)}-${i}`}>
            <strong>{it.amount}× {it.name} ({it.set} #{it.num})</strong> — {it.message}
            {it.kind === 'mismatch' && (
              <Button variant="subtle" size="sm" className="bsi-repair-btn"
                data-testid="builder-issue-accept"
                title={t('decks', 'issues_accept_hint')}
                onClick={() => accept(it)}>
                {t('decks', 'issues_accept')}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
