import { useTranslation } from '../i18n'
import { useStore } from '../state/store'
import { undoHeldByOther } from '../state/undoPreview'
import './UndoPreviewBar.css'

/**
 * Another player stopped the game to look back at their priorities (KEY-13): it's paused until
 * they're done, and what I do meanwhile happens when it goes on.
 */
export default function UndoHoldNotice() {
  const { t } = useTranslation()
  const name = useStore((s) => undoHeldByOther(s.game))
  const browsing = useStore((s) => !!s.undoPreview && s.undoPreview.gameId === s.gameId)
  if (!name || browsing) return null
  return (
    <div className="undo-preview-bar undo-hold-notice chrome-surface" role="status" aria-live="polite" data-testid="undo-hold-notice">
      <div className="undo-preview-status">
        <span className="undo-preview-badge">{t('game', 'undo_hold_badge')}</span>
        <span className="undo-hold-text">{t('game', 'undo_hold_notice', { name })}</span>
      </div>
    </div>
  )
}
