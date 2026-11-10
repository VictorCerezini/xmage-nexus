import { useEffect, useRef, useState } from 'react'
import type { HTMLAttributes, PointerEvent as ReactPointerEvent } from 'react'
import { useStore, voteRollback, hideRollbackVote, dismissRollbackVote } from '../state/store'
import type { RollbackVoter } from '../state/store'
import { soundManager } from '../audio/soundManager'
import DialogShell from '../ui/DialogShell'
import Icon from '../ui/Icon'
import Button from '../ui/Button'
import { useTranslation } from '../i18n'
import { withdrawUndoRequest } from '../state/actions'
import { StepName } from './PhaseBar'
import { clampDragPos } from '../ui/useDraggablePos'
import './RollbackVoteDialog.css'

export const ROLLBACK_HIDE_AFTER_MS = 30_000
export const ROLLBACK_AUTO_DISMISS_MS = 8_000

/** How far the dialog was dragged from its usual place, in the panel's own CSS pixels. */
interface Offset {
  x: number
  y: number
}

// its controls keep their clicks; anywhere else on the dialog drags it
const NO_DRAG = 'button, a, input, select, textarea, [role="button"]'

/** Screen pixels per CSS pixel of the panel (the interface scale zooms the page). */
function panelScale(el: HTMLElement, rect: DOMRect): number {
  return el.offsetWidth > 0 && rect.width > 0 ? rect.width / el.offsetWidth : 1
}

/**
 * Drag the dialog aside to see the board under it (in multiplayer it can cover a whole
 * player's board). It stays inside the screen, also when the window gets smaller, follows the
 * pointer at any interface scale (the page is CSS-zoomed: pointer and screen pixels are divided
 * by the panel's scale), and each new vote (`resetKey`) opens in the usual place again.
 */
function useMoveAside(resetKey: string) {
  const [offset, setOffset] = useState<Offset>({ x: 0, y: 0 })
  const [moving, setMoving] = useState(false)
  const drag = useRef<{ x: number; y: number; rect: DOMRect; from: Offset; scale: number } | null>(null)
  const panel = useRef<HTMLElement | null>(null)
  const moved = offset.x !== 0 || offset.y !== 0

  useEffect(() => {
    drag.current = null
    panel.current = null
    setMoving(false)
    setOffset({ x: 0, y: 0 })
  }, [resetKey])

  // a smaller window could leave a moved dialog (and its buttons) off the screen: pull it back in
  useEffect(() => {
    if (!moved) return
    const onResize = () => {
      const el = panel.current
      if (!el?.isConnected) return
      const rect = el.getBoundingClientRect()
      const scale = panelScale(el, rect)
      const to = clampDragPos(rect.left, rect.top, rect.width, rect.height, window.innerWidth, window.innerHeight)
      if (to.left === rect.left && to.top === rect.top) return
      setOffset((o) => ({ x: o.x + (to.left - rect.left) / scale, y: o.y + (to.top - rect.top) / scale }))
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [moved])

  const onPointerDown = (e: ReactPointerEvent<HTMLElement>) => {
    if (e.button !== 0) return
    if ((e.target as Element | null)?.closest?.(NO_DRAG)) return
    const el = e.currentTarget
    const rect = el.getBoundingClientRect()
    panel.current = el
    drag.current = { x: e.clientX, y: e.clientY, rect, from: offset, scale: panelScale(el, rect) }
    el.setPointerCapture?.(e.pointerId)
    setMoving(true)
  }
  const onPointerMove = (e: ReactPointerEvent<HTMLElement>) => {
    const d = drag.current
    if (!d) return
    const to = clampDragPos(d.rect.left + e.clientX - d.x, d.rect.top + e.clientY - d.y, d.rect.width, d.rect.height, window.innerWidth, window.innerHeight)
    setOffset({ x: d.from.x + (to.left - d.rect.left) / d.scale, y: d.from.y + (to.top - d.rect.top) / d.scale })
  }
  const onPointerEnd = () => {
    drag.current = null
    setMoving(false)
  }

  return {
    moving,
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp: onPointerEnd,
      onPointerCancel: onPointerEnd,
      // the panel is position: relative in the centred backdrop, so left/top shift it from there
      style: moved ? { left: `${offset.x}px`, top: `${offset.y}px` } : undefined,
    },
  }
}

function VoterRow({ voter }: { voter: RollbackVoter }) {
  const { t } = useTranslation()
  const icon = voter.status === 'accepted' || voter.status === 'requested' ? 'check' : voter.status === 'denied' ? 'x' : 'hourglass'
  return (
    <li className={`rollback-voter is-${voter.status}`} data-testid="rollback-voter" data-status={voter.status}>
      <span className="rollback-voter-icon"><Icon name={icon} size={13} /></span>
      <span className="rollback-voter-name">
        {voter.name}
        {voter.me && <span className="rollback-voter-me"> ({t('dialogs', 'rollback_vote_you')})</span>}
      </span>
      <span className="rollback-voter-status">{t('dialogs', `rollback_vote_status_${voter.status}`)}</span>
    </li>
  )
}

export default function RollbackVoteDialog() {
  const { t } = useTranslation()
  const vote = useStore((s) => s.rollbackVote)
  const currentTurn = useStore((s) => s.game?.turn ?? null)
  const currentStep = useStore((s) => s.game?.step ?? null)
  // the game where the undo would go back to, on screen while the others are asked
  const undoBoard = useStore((s) => (s.undoPreview?.mode === 'vote' && s.undoPreview.gameId === s.rollbackVote?.gameId ? s.undoPreview.board : null))
  const [busy, setBusy] = useState(false)
  const [canHide, setCanHide] = useState(false)
  const moveAside = useMoveAside(vote ? `${vote.gameId}:${vote.kind ?? ''}:${vote.startedAt ?? ''}` : '')

  const voting = vote?.outcome === 'voting'
  const startedAt = vote?.startedAt

  useEffect(() => {
    setCanHide(false)
    if (!voting || startedAt == null) return
    const id = setTimeout(() => setCanHide(true), Math.max(0, startedAt + ROLLBACK_HIDE_AFTER_MS - Date.now()))
    return () => clearTimeout(id)
  }, [voting, startedAt])

  useEffect(() => {
    if (!vote || voting || vote.hidden) return
    const id = setTimeout(() => dismissRollbackVote(), ROLLBACK_AUTO_DISMISS_MS)
    return () => clearTimeout(id)
  }, [vote, voting])

  if (!vote || vote.hidden) return null

  const isUndo = vote.kind === 'undo'
  const targetTurn = Math.max(1, vote.requestedAtTurn - vote.turns)
  // an undo goes back from wherever the game is when it runs, so there is no turn to drift from
  const drifted = !isUndo && voting && currentTurn != null && currentTurn > vote.requestedAtTurn
  const needsMyVote = voting && vote.myVote === 'pending'
  const accepted = vote.voters.filter((v) => v.status === 'accepted' || v.status === 'requested').length

  const onWithdraw = async () => {
    if (busy) return
    soundManager.play('ui_click', 'ui')
    setBusy(true)
    try {
      await withdrawUndoRequest(vote.gameId)
    } finally {
      setBusy(false)
    }
  }

  const onVote = async (accept: boolean) => {
    if (busy) return
    soundManager.play('ui_click', 'ui')
    setBusy(true)
    try {
      await voteRollback(vote.gameId, accept, vote.requesterUserId)
    } finally {
      setBusy(false)
    }
  }

  const prefix = isUndo ? 'undo_vote' : 'rollback_vote'
  const steps = Math.max(1, vote.steps ?? 1)
  // an undo of several priorities says how many: "... go back 3 priorities"
  const several = isUndo && steps > 1
  const title = vote.requestedByMe
    ? t('dialogs', several ? 'undo_vote_title_mine_n' : `${prefix}_title_mine`, { count: steps })
    : t('dialogs', several ? 'undo_vote_title_n' : `${prefix}_title`, { name: vote.requester || '?', count: steps })
  // where the game is now and where it goes back to, so it is clear how far: the board on screen
  // shows the point back there (the server sends it with the request); the store keeps the live game
  const where = isUndo && undoBoard ? (
    <span className="rollback-vote-points">
      {currentTurn != null && (
        <span className="rollback-vote-now" data-testid="undo-vote-now">
          {t('dialogs', 'undo_vote_now', { turn: currentTurn })}
          {currentStep && <> · <StepName step={currentStep} /></>}
        </span>
      )}
      <span className="rollback-vote-where" data-testid="undo-vote-where">
        {t('dialogs', 'undo_vote_where', { turn: undoBoard.turn })}
        {undoBoard.step && <> · <StepName step={undoBoard.step} /></>}
      </span>
    </span>
  ) : null
  const message = isUndo
    ? (
      <>
        {where}
        {vote.requestedByMe
          ? t('dialogs', where ? 'undo_vote_preview_mine' : 'undo_vote_target_mine')
          : where
            ? t('dialogs', 'undo_vote_preview')
            : t('dialogs', 'undo_vote_target', { name: vote.requester || '?' })}
      </>
    )
    : t('dialogs', 'rollback_vote_target', { turn: targetTurn, from: vote.requestedAtTurn })

  let banner: { tone: 'wait' | 'ok' | 'err'; text: string }
  if (vote.outcome === 'applied') {
    banner = { tone: 'ok', text: t('dialogs', `${prefix}_applied`, { turn: vote.appliedTurn ?? targetTurn }) }
  } else if (vote.outcome === 'denied' && vote.withdrawn) {
    banner = { tone: 'err', text: t('dialogs', 'undo_vote_withdrawn', { name: vote.deniedBy ?? '?' }) }
  } else if (vote.outcome === 'denied') {
    banner = { tone: 'err', text: t('dialogs', `${prefix}_denied`, { name: vote.deniedBy ?? '?' }) }
  } else if (vote.outcome === 'failed') {
    banner = { tone: 'err', text: t('dialogs', `${prefix}_failed`, { reason: vote.failReason ?? '' }) }
  } else if (needsMyVote) {
    banner = { tone: 'wait', text: t('dialogs', 'rollback_vote_your_turn') }
  } else {
    banner = { tone: 'wait', text: t('dialogs', 'rollback_vote_waiting') }
  }

  return (
    <DialogShell
      labelledBy="rollback-vote-title"
      titleId="rollback-vote-title"
      testId="rollback-vote"
      legacyBackdropClass={undoBoard ? 'feedback-backdrop rollback-vote-backdrop--preview' : 'feedback-backdrop'}
      legacyPanelClass={`feedback-dialog rollback-vote-dialog chrome-surface${moveAside.moving ? ' is-moving' : ''}`}
      kickerIcon="undo"
      kickerLabel={t('dialogs', `${prefix}_kicker`)}
      title={title}
      message={message}
      sectionProps={{ 'data-space-shortcut-off': 'true', ...moveAside.handlers } as HTMLAttributes<HTMLElement>}
    >
      <div className="rollback-vote-progress" aria-live="polite">
        {t('dialogs', 'rollback_vote_progress', { count: accepted, total: vote.voters.length })}
      </div>
      <ul className="rollback-voter-list">
        {vote.voters.map((v) => <VoterRow key={v.name} voter={v} />)}
      </ul>

      {drifted && (
        <div className="rollback-priority-notice" role="alert">
          <Icon name="alert" size={13} /> {t('dialogs', 'rollback_vote_drift', { turn: currentTurn, target: Math.max(1, (currentTurn ?? 0) - vote.turns), requested: targetTurn })}
        </div>
      )}

      <div className={`rollback-vote-banner is-${banner.tone}`} role="status" data-testid="rollback-vote-outcome" data-outcome={vote.outcome}>
        {banner.tone === 'wait' && <span className="rollback-vote-spinner" aria-hidden="true" />}
        {banner.tone === 'ok' && <Icon name="check" size={14} />}
        {banner.tone === 'err' && <Icon name="x" size={14} />}
        <span>{banner.text}</span>
      </div>

      <div className="feedback-dialog-actions rollback-actions">
        {needsMyVote ? (
          <>
            <Button variant="primary" disabled={busy} onClick={() => void onVote(true)} data-testid="rollback-vote-accept">
              <Icon name="check" size={13} /> {t('dialogs', 'rollback_vote_accept')}
            </Button>
            <Button variant="subtle" className="cancel-btn" disabled={busy} onClick={() => void onVote(false)} data-testid="rollback-vote-deny">
              <Icon name="x" size={13} /> {t('dialogs', 'rollback_vote_deny')}
            </Button>
          </>
        ) : voting ? (
          <>
            {isUndo && vote.requestedByMe && (
              <Button variant="subtle" className="cancel-btn" disabled={busy} onClick={() => void onWithdraw()} data-testid="undo-vote-withdraw">
                <Icon name="x" size={13} /> {t('dialogs', 'undo_vote_withdraw')}
              </Button>
            )}
            {canHide && (
              <Button variant="subtle" onClick={hideRollbackVote} data-testid="rollback-vote-hide">
                {t('dialogs', 'rollback_vote_hide')}
              </Button>
            )}
          </>
        ) : (
          <Button variant="primary" onClick={dismissRollbackVote} data-testid="rollback-vote-close">
            {t('dialogs', 'rollback_vote_close')}
          </Button>
        )}
      </div>
    </DialogShell>
  )
}
