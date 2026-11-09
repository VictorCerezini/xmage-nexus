import { useEffect, useState } from 'react'
import Button from '../ui/Button'
import Icon from '../ui/Icon'
import { useTranslation } from '../i18n'
import { useStore } from '../state/store'
import { PhaseName } from './PhaseBar'
import { confirmUndoPreview, endUndoPreview, undoBrowseBack, undoBrowseForward } from '../state/undoPreview'
import './UndoPreviewBar.css'

/**
 * While looking back with Ctrl+Z (KEY-13): how far back the board on screen is, back and forward,
 * and "Confirm rollback" or cancel. Nothing changes in the game until it is confirmed.
 */
export default function UndoPreviewBar() {
  const { t } = useTranslation()
  const preview = useStore((s) => s.undoPreview)
  const gameId = useStore((s) => s.gameId)
  const [busy, setBusy] = useState(false)
  const browsing = !!preview && !!gameId && preview.mode === 'browse' && preview.gameId === gameId

  useEffect(() => {
    if (!browsing || !gameId) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      endUndoPreview(gameId)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [browsing, gameId])

  if (!browsing || !preview || !gameId) return null

  const loading = preview.pendingSteps != null
  const board = preview.board
  const steps = preview.steps
  const at = preview.pendingSteps ?? steps
  const shown = !!board && steps > 0

  const onConfirm = async () => {
    if (busy) return
    setBusy(true)
    try {
      await confirmUndoPreview(gameId)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      className="undo-preview-bar chrome-surface"
      role="group"
      aria-label={t('game', 'undo_preview_title')}
      data-testid="undo-preview-bar"
      data-steps={steps}
      data-loading={loading ? 'true' : 'false'}
    >
      <div className="undo-preview-status" aria-live="polite">
        <span className="undo-preview-badge">{t('game', 'undo_preview_badge')}</span>
        {shown ? (
          <>
            <span className="undo-preview-steps" data-testid="undo-preview-steps">
              {steps === 1 ? t('game', 'undo_preview_steps_one') : t('game', 'undo_preview_steps', { count: steps })}
            </span>
            <span className="undo-preview-where" data-testid="undo-preview-where">
              {t('game', 'undo_preview_turn', { turn: board.turn })}
              {board.step && <> · <PhaseName step={board.step} /></>}
            </span>
          </>
        ) : (
          <span className="undo-preview-steps">{t('game', 'undo_preview_loading')}</span>
        )}
      </div>
      <div className="undo-preview-actions">
        <Button
          size="sm"
          variant="ghost"
          data-testid="undo-preview-back"
          title={t('game', 'undo_preview_back_hint')}
          disabled={at >= preview.available}
          onClick={() => undoBrowseBack(gameId)}
        >
          <Icon name="chevronLeft" size={12} /> {t('game', 'undo_preview_back')}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          data-testid="undo-preview-forward"
          title={t('game', 'undo_preview_forward_hint')}
          onClick={() => undoBrowseForward(gameId)}
        >
          {t('game', 'undo_preview_forward')} <Icon name="chevronRight" size={12} />
        </Button>
        <Button
          size="sm"
          variant="primary"
          data-testid="undo-preview-confirm"
          disabled={!shown || loading || busy}
          onClick={() => void onConfirm()}
        >
          <Icon name="check" size={12} /> {t('game', 'undo_preview_confirm')}
        </Button>
        <Button
          size="sm"
          variant="subtle"
          data-testid="undo-preview-cancel"
          title={t('game', 'undo_preview_cancel_hint')}
          onClick={() => endUndoPreview(gameId)}
        >
          {t('game', 'undo_preview_cancel')}
        </Button>
      </div>
    </div>
  )
}
