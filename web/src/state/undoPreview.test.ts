import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { makeGameView, makePlayer } from '../__fixtures__/gameViews'
import type { GameView } from '../net/types'
import { getState, setState } from './state'
import { handleMessage } from './store'
import { reset } from './gateway'
import * as cmds from '../net/commands'
import {
  UNDO_APPLIED_GRACE_MS,
  UNDO_APPLY_TIMEOUT_MS,
  confirmUndoPreview,
  startUndoBrowse,
  undoBrowseBack,
  waitsOnMe,
} from './undoPreview'
import { parseUndoSteps } from './rollbackVote'
import { withdrawUndoRequest } from './actions'

vi.mock('../net/commands', () => ({
  setGateway: vi.fn(),
  getGateway: vi.fn(),
  joinGame: vi.fn(),
  getGameChatId: vi.fn().mockResolvedValue(undefined),
  joinChat: vi.fn(),
  sendChatMessage: vi.fn().mockResolvedValue({ ok: true }),
  sendPlayerAction: vi.fn().mockResolvedValue({ ok: true }),
  sendPlayerBoolean: vi.fn(),
  sendPlayerUUID: vi.fn(),
}))

const GAME = 'g-preview'

/** Alice (me, with priority) against Bob: a computer player unless bobIsHuman. */
function view(opts: { turn: number; step: string; bobIsHuman?: boolean; count?: number }): GameView {
  return makeGameView({
    turn: opts.turn,
    phase: opts.step === 'UPKEEP' ? 'BEGINNING' : 'PRECOMBAT_MAIN',
    step: opts.step,
    rollbackTurnsAllowed: true,
    canUndoPriority: true,
    undoPriorityCount: opts.count ?? 3,
    myPlayerId: 'p-Alice',
    priorityPlayerName: 'Alice',
    players: [
      makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: true, hasPriority: true }),
      makePlayer({ playerId: 'p-Bob', name: 'Bob', isHuman: opts.bobIsHuman ?? false }),
    ],
  })
}

let id = 0
function update(v: GameView) {
  handleMessage({ type: 'event', method: 'GAME_UPDATE', messageId: ++id, objectId: GAME, data: v })
}

function chat(message: string) {
  handleMessage({
    type: 'event',
    method: 'CHATMESSAGE',
    messageId: ++id,
    objectId: 'c-preview',
    data: { chatId: 'c-preview', username: '', message, messageType: 'GAME' },
  } as never)
}

function preview(data: Record<string, unknown>) {
  handleMessage({ type: 'event', method: 'GAME_UNDO_PREVIEW', messageId: ++id, objectId: GAME, data } as never)
}

const announce = (name: string, what: string, turn: number, step: string) =>
  `<font color='#ff0000'>Player request: ${name} goes back ${what} (turn ${turn}, ${step})</font>`

/** Look back with Ctrl+Z until the board `steps` priorities back is on screen (turn 4 → turn 3). */
function browseTo(steps: number) {
  expect(startUndoBrowse(GAME)).toBe(true)
  preview({ board: view({ turn: 4, step: 'UPKEEP' }), steps: 1, available: 3, vote: false, requesterName: 'Alice' })
  for (let i = 2; i <= steps; i++) {
    undoBrowseBack(GAME)
    preview({ board: view({ turn: 3, step: 'PRECOMBAT_MAIN' }), steps: i, available: 3, vote: false, requesterName: 'Alice' })
  }
  expect(getState().undoPreview?.steps).toBe(steps)
}

const undoCalls = () => vi.mocked(cmds.sendPlayerAction).mock.calls.filter((c) => c[0] === 'UNDO_LAST_PRIORITY')

describe('KEY-13 looking back, then going back', () => {
  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    setState({ gameId: GAME })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('KEY-13: the game waits on me with a question, my priority, or the priority of a player I control', () => {
    const v = view({ turn: 4, step: 'PRECOMBAT_MAIN' })
    expect(waitsOnMe({ feedback: null, game: v })).toBe(true)
    const theirs: GameView = { ...v, priorityPlayerName: 'Bob', players: (v.players ?? []).map((p) => ({ ...p, hasPriority: p.name === 'Bob' })) }
    expect(waitsOnMe({ feedback: null, game: theirs })).toBe(false)
    expect(waitsOnMe({ feedback: { method: 'GAME_TARGET' }, game: theirs })).toBe(true)
    expect(waitsOnMe({ feedback: null, game: { ...theirs, opponentHands: { Bob: {} } } })).toBe(true)
  })

  it('KEY-13: the server-restored board ends the confirmed preview (the board comes first)', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    browseTo(2)
    await confirmUndoPreview(GAME)
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', GAME, 2]])
    expect(getState().undoPreview?.mode).toBe('applying')
    update(view({ turn: 3, step: 'PRECOMBAT_MAIN' }))
    expect(getState().game?.turn).toBe(3)
    expect(getState().undoPreview).toBeNull()
  })

  it('KEY-13: the announce coming first: the preview ends with the next board', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    browseTo(2)
    await confirmUndoPreview(GAME)
    chat(announce('Alice', '2 priorities', 3, 'Precombat Main'))
    expect(getState().undoPreview?.appliedAt).toBeTypeOf('number')
    update(view({ turn: 3, step: 'PRECOMBAT_MAIN' }))
    expect(getState().undoPreview).toBeNull()
  })

  it('KEY-13: the announce and no new board: the preview ends after a moment anyway', async () => {
    vi.useFakeTimers()
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    browseTo(2)
    await confirmUndoPreview(GAME)
    chat(announce('Alice', '2 priorities', 3, 'Precombat Main'))
    vi.advanceTimersByTime(UNDO_APPLIED_GRACE_MS + 10)
    expect(getState().undoPreview).toBeNull()
  })

  it('KEY-13: no answer at all from the server: back to the game as it is after a while', async () => {
    vi.useFakeTimers()
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    browseTo(1)
    await confirmUndoPreview(GAME)
    expect(getState().undoPreview?.mode).toBe('applying')
    vi.advanceTimersByTime(UNDO_APPLY_TIMEOUT_MS - 100)
    expect(getState().undoPreview?.mode).toBe('applying')
    vi.advanceTimersByTime(200)
    expect(getState().undoPreview).toBeNull()
    expect(getState().game?.turn).toBe(5)
  })

  it('KEY-13: the server refusing the request ends the preview and shows the error', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    browseTo(1)
    vi.mocked(cmds.sendPlayerAction).mockResolvedValueOnce({ ok: false, error: 'nope' } as never)
    await confirmUndoPreview(GAME)
    expect(getState().undoPreview).toBeNull()
    expect(getState().error).toBe('nope')
  })

  it('KEY-13: against a human, the vote shows how many priorities, and the announce of 2 marks it done', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN', bobIsHuman: true }))
    browseTo(2)
    await confirmUndoPreview(GAME)
    const vote = getState().rollbackVote
    expect(vote?.kind).toBe('undo')
    expect(vote?.steps).toBe(2)
    expect(getState().undoPreview?.mode).toBe('vote')
    // the server shows the requester the same board as the voters
    preview({ board: view({ turn: 3, step: 'PRECOMBAT_MAIN' }), steps: 2, available: 3, vote: true, requesterName: 'Alice' })
    expect(getState().undoPreview?.requestedByMe).toBe(true)
    chat(announce('Alice', '2 priorities', 3, 'Precombat Main'))
    expect(getState().rollbackVote?.outcome).toBe('applied')
    expect(getState().rollbackVote?.appliedTurn).toBe(3)
  })

  it('KEY-13: denied: the board goes back to the game as it is, and the vote says who denied it', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN', bobIsHuman: true }))
    browseTo(2)
    await confirmUndoPreview(GAME)
    preview({ board: null, steps: 0, available: 0, vote: false, requesterName: '' })
    chat('Undo request denied by Bob')
    expect(getState().undoPreview).toBeNull()
    expect(getState().rollbackVote?.outcome).toBe('denied')
    expect(getState().rollbackVote?.deniedBy).toBe('Bob')
    expect(getState().game?.turn).toBe(5)
  })

  it('KEY-13: I can withdraw my request; nobody is marked as denying it', async () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN', bobIsHuman: true }))
    browseTo(1)
    await confirmUndoPreview(GAME)
    await withdrawUndoRequest(GAME)
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('DENY_PERMISSION_TO_ROLLBACK_TURN', GAME)
    chat('Undo request withdrawn by Alice')
    const vote = getState().rollbackVote
    expect(vote?.outcome).toBe('denied')
    expect(vote?.withdrawn).toBe(true)
    expect(vote?.voters.find((v) => v.name === 'Bob')?.status).toBe('pending')
    expect(getState().undoPreview).toBeNull()
  })

  it("KEY-13: the other player's request says how many priorities back", () => {
    expect(parseUndoSteps('Allow Bob to go back 3 priorities (turn 2, Upkeep)?')).toBe(3)
    expect(parseUndoSteps('Allow Bob to go back to their last priority (turn 2, Upkeep)?')).toBe(1)
  })

  it('KEY-13: an answer to an older look back is dropped, and none at all while not looking back', () => {
    update(view({ turn: 5, step: 'PRECOMBAT_MAIN' }))
    preview({ board: view({ turn: 4, step: 'UPKEEP' }), steps: 1, available: 3, vote: false, requesterName: 'Alice' })
    expect(getState().undoPreview).toBeNull()
    expect(startUndoBrowse(GAME)).toBe(true)
    undoBrowseBack(GAME)
    preview({ board: view({ turn: 4, step: 'UPKEEP' }), steps: 1, available: 3, vote: false, requesterName: 'Alice' })
    expect(getState().undoPreview?.steps).toBe(0)
    expect(getState().undoPreview?.board).toBeNull()
  })
})
