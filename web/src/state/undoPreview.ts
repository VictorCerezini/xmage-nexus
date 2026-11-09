import { getState, setState, addLog } from './state'
import * as cmds from '../net/commands'
import type { GameView } from '../net/types'
import { t as tStatic } from '../i18n'
import { isControllingPriority } from './control'
import { otherRollbackVoters, type RollbackVote } from './rollbackVote'
import { requestUndoPriority } from './actions'

/**
 * Going back some of my priorities (KEY-13), looked at before it happens:
 * - 'browse': I choose how far back with Ctrl+Z / Ctrl+Shift+Z. Only I see it, nothing changes.
 * - 'vote': the other humans are asked to agree. Everyone sees where the game would go back to,
 *   grayed out, until they answer.
 * - 'applying': confirmed without anyone to ask. The board stays on that point until the server
 *   sends the game as it was there.
 */
export type UndoPreviewMode = 'browse' | 'vote' | 'applying'

export interface UndoPreview {
  gameId: string
  mode: UndoPreviewMode
  /** how many priorities back the board on screen is (0 while the first one loads) */
  steps: number
  /** asked the server for this many, the answer isn't here yet */
  pendingSteps: number | null
  /** how many I can go back at most */
  available: number
  /** the game back there, as I see it; null while the first one loads */
  board: GameView | null
  requester: string
  requestedByMe: boolean
  /** the priority request on screen when browsing started: a new one means the game went on */
  atRequest?: GameView | null
  /** the vote for it was on screen (so its end also ends the preview) */
  sawVote?: boolean
  /** when the server said the game went back */
  appliedAt?: number
}

export const UNDO_PREVIEW_ACTION = 'UNDO_PREVIEW'
/** Without the restored game by then, the confirmed board goes back to the game as it is. */
export const UNDO_APPLY_TIMEOUT_MS = 8_000
/** After the server said the game went back, the restored game is on screen by then. */
export const UNDO_APPLIED_GRACE_MS = 1_500

/** The server's GameUndoPreviewView. No board ends the preview (denied, taken back, not possible now). */
interface UndoPreviewData {
  board?: GameView | null
  steps?: number
  available?: number
  vote?: boolean
  requesterName?: string
}

function current(gameId: string | null | undefined): UndoPreview | null {
  const p = getState().undoPreview
  return p && gameId && p.gameId === gameId ? p : null
}

/** The game waits on me (a question to me, my priority, or the priority of a player I control):
 *  only then can I look back. The same rule as the "waiting for" banner (waitingState). */
export function waitsOnMe(s: { feedback: unknown; game: GameView | null }): boolean {
  if (s.feedback) return true
  if (s.game?.players?.some((p) => p.controlled && p.hasPriority)) return true
  return isControllingPriority(s.game)
}

export function gameWaitsOnMe(): boolean {
  return waitsOnMe(getState())
}

export function isUndoBrowsing(gameId: string | null | undefined): boolean {
  return current(gameId)?.mode === 'browse'
}

function ask(gameId: string, steps: number): void {
  void cmds.sendPlayerAction(UNDO_PREVIEW_ACTION, gameId, steps).then((res) => {
    if (!res.ok) {
      endUndoPreview(gameId)
      setState({ error: res.error ?? 'Error going back' })
    }
  })
}

/** Ctrl+Z or the game menu: look back first. A server without the preview (no undoPriorityCount
 *  in its game view) goes back to the last priority at once, as before. */
export function beginUndo(gameId: string): void {
  const live = getState().game
  if (!live?.canUndoPriority || !gameWaitsOnMe()) return
  if (live.undoPriorityCount === undefined) {
    void requestUndoPriority(gameId)
    return
  }
  startUndoBrowse(gameId)
}

/** Ctrl+Z: show the game one priority back, only to me. */
export function startUndoBrowse(gameId: string): boolean {
  const s = getState()
  const live = s.game
  if (s.undoPreview || !live?.canUndoPriority || !gameWaitsOnMe()) return false
  const me = live.players?.find((p) => p.controlled)
  if (!me) return false
  setState({
    undoPreview: {
      gameId,
      mode: 'browse',
      steps: 0,
      pendingSteps: 1,
      available: Math.max(1, live.undoPriorityCount ?? 1),
      board: null,
      requester: me.name,
      requestedByMe: true,
      atRequest: s.priorityRequest,
    },
  })
  ask(gameId, 1)
  return true
}

/** Ctrl+Z while looking back: one priority further back, as far as there are. */
export function undoBrowseBack(gameId: string): void {
  const p = current(gameId)
  if (!p || p.mode !== 'browse') return
  const at = p.pendingSteps ?? p.steps
  const next = Math.min(p.available, at + 1)
  if (next === at) return
  setState({ undoPreview: { ...p, pendingSteps: next } })
  ask(gameId, next)
}

/** Ctrl+Shift+Z while looking back: one priority forward; forward from the last one is the game as it is. */
export function undoBrowseForward(gameId: string): void {
  const p = current(gameId)
  if (!p || p.mode !== 'browse') return
  const at = p.pendingSteps ?? p.steps
  if (at <= 1) {
    endUndoPreview(gameId)
    return
  }
  setState({ undoPreview: { ...p, pendingSteps: at - 1 } })
  ask(gameId, at - 1)
}

export function endUndoPreview(gameId?: string | null): void {
  const p = getState().undoPreview
  if (p && (!gameId || p.gameId === gameId)) setState({ undoPreview: null })
}

/**
 * "Confirm rollback": go back to the priority on screen. Against computer players it runs at once;
 * with other humans they're asked first, and everyone sees this board meanwhile.
 */
export async function confirmUndoPreview(gameId: string) {
  const p = current(gameId)
  if (!p || p.mode !== 'browse' || p.steps < 1 || p.pendingSteps != null) return null
  const asksOthers = otherRollbackVoters(getState().game) > 0
  setState({ undoPreview: { ...p, mode: asksOthers ? 'vote' : 'applying' } })
  if (!asksOthers) {
    setTimeout(() => {
      const still = current(gameId)
      if (still?.mode === 'applying' && still.board === p.board) endUndoPreview(gameId)
    }, UNDO_APPLY_TIMEOUT_MS)
  }
  const res = await requestUndoPriority(gameId, p.steps)
  if (!res.ok) endUndoPreview(gameId)
  return res
}

/** GAME_UNDO_PREVIEW from the server. */
export function ingestUndoPreview(data: unknown, gameId: string | null | undefined): void {
  if (!gameId || !data || typeof data !== 'object' || Array.isArray(data)) return
  const d = data as UndoPreviewData
  const board = d.board && typeof d.board === 'object' && !Array.isArray(d.board) ? d.board : null
  const p = current(gameId)
  if (!board) {
    if (!p || p.mode === 'applying') return
    if (p.mode === 'browse' && p.pendingSteps != null) {
      addLog('partida', tStatic('game', 'undo_preview_unavailable'), gameId)
    }
    endUndoPreview(gameId)
    return
  }
  if (d.vote) {
    const me = getState().game?.players?.find((pl) => pl.controlled)
    const requester = d.requesterName ?? ''
    setState({
      undoPreview: {
        gameId,
        mode: 'vote',
        steps: d.steps ?? 1,
        pendingSteps: null,
        available: d.available ?? 0,
        board,
        requester,
        requestedByMe: p?.requestedByMe ?? (!!me && me.name.trim().toLowerCase() === requester.trim().toLowerCase()),
        sawVote: p?.mode === 'vote' ? p.sawVote : false,
      },
    })
    return
  }
  // looking back on my own: only the answer to what I asked last (an older one comes late)
  if (!p || p.mode !== 'browse') return
  const steps = d.steps ?? 0
  const available = d.available ?? p.available
  if (p.pendingSteps != null && steps !== p.pendingSteps && available >= p.pendingSteps) return
  setState({ undoPreview: { ...p, board, steps, pendingSteps: null, available: Math.max(available, steps) } })
}

/**
 * Keeps the preview in step with the game: looking back ends when the game goes on (a new
 * priority request), and the vote's board when that vote is over, hidden or gone.
 */
export function syncUndoPreview(): void {
  const s = getState()
  const p = s.undoPreview
  if (!p) return
  if (p.gameId !== s.gameId) {
    setState({ undoPreview: null })
    return
  }
  if (p.mode === 'browse') {
    if (p.atRequest !== undefined && s.priorityRequest !== p.atRequest) setState({ undoPreview: null })
    return
  }
  if (p.mode !== 'vote') return
  const vote: RollbackVote | null = s.rollbackVote
  const open = !!vote && vote.gameId === p.gameId && vote.kind === 'undo' && vote.outcome === 'voting' && !vote.hidden
  if (open && !p.sawVote) setState({ undoPreview: { ...p, sawVote: true } })
  else if (!open && p.sawVote) setState({ undoPreview: null })
}

/**
 * The server said the game went back (its log line, which comes by the chat): the restored game
 * comes with it or right after, and the preview ends then, or after a moment if it came first.
 */
export function undoPreviewApplied(gameId: string | null | undefined): void {
  const p = current(gameId)
  if (!p || p.mode === 'browse' || p.appliedAt) return
  setState({ undoPreview: { ...p, appliedAt: Date.now() } })
  setTimeout(() => {
    if (current(gameId)?.appliedAt) endUndoPreview(gameId)
  }, UNDO_APPLIED_GRACE_MS)
}

/** Going back failed or was refused after it was confirmed: back to the game as it is. */
export function undoPreviewFailed(gameId: string | null | undefined): void {
  const p = current(gameId)
  if (p && p.mode !== 'browse') endUndoPreview(gameId)
}

/** A game view went on screen: the restored one, or any after the server said the game went back. */
export function noteLiveGameView(gameId: string | null | undefined, restored: boolean): void {
  const p = current(gameId)
  if (!p || p.mode === 'browse') return
  if (restored || p.appliedAt) endUndoPreview(gameId)
}
