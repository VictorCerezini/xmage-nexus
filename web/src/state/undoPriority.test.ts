import { beforeEach, describe, expect, it, vi } from 'vitest'
import { makeGameView, makePlayer } from '../__fixtures__/gameViews'
import type { GameView } from '../net/types'
import { getState, setState } from './state'
import { handleMessage, maybeAutoPass, requestUndoPriority, setSetting, voteRollback } from './store'
import { reset } from './gateway'
import * as cmds from '../net/commands'

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

const GAME = 'g-undo'

/** Alice (me) against Bob: a computer player unless bobIsHuman. */
function view(opts: { turn: number; step: string; phase?: string; bobIsHuman?: boolean; me?: string; canUndo?: boolean }): GameView {
  const me = opts.me ?? 'Alice'
  return makeGameView({
    turn: opts.turn,
    phase: opts.phase ?? (opts.step === 'END_TURN' ? 'END' : opts.step === 'UPKEEP' ? 'BEGINNING' : 'PRECOMBAT_MAIN'),
    step: opts.step,
    rollbackTurnsAllowed: true,
    canUndoPriority: opts.canUndo ?? true,
    myPlayerId: `p-${me}`,
    players: [
      makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: me === 'Alice' }),
      makePlayer({ playerId: 'p-Bob', name: 'Bob', controlled: me === 'Bob', isHuman: opts.bobIsHuman ?? false }),
    ],
  })
}

function update(v: GameView, id: number) {
  handleMessage({ type: 'event', method: 'GAME_UPDATE', messageId: id, objectId: GAME, data: v })
}

function chat(message: string, id: number) {
  handleMessage({
    type: 'event',
    method: 'CHATMESSAGE',
    messageId: id,
    objectId: 'c-undo',
    data: { chatId: 'c-undo', username: '', message, messageType: 'GAME' },
  } as never)
}

function incomingUndoRequest(from: string, id = 20) {
  handleMessage({
    type: 'event',
    method: 'USER_REQUEST_DIALOG',
    messageId: id,
    objectId: GAME,
    data: {
      title: `Request by ${from}`,
      message: `Allow ${from} to go back to their last priority?`,
      gameId: GAME,
      relatedUserId: `u-${from}`,
      relatedUserName: from,
      button1Text: 'Accept',
      button1Action: 'ADD_PERMISSION_TO_ROLLBACK_TURN',
      button2Text: 'Deny',
      button2Action: 'DENY_PERMISSION_TO_ROLLBACK_TURN',
    },
  } as never)
}

const announce = (name: string, turn: number, step: string) =>
  `<font color='#ff0000'>Player request: ${name} goes back to their last priority (turn ${turn}, ${step})</font>`

describe('KEY-13 go back to my last priority', () => {
  beforeEach(() => {
    reset()
    vi.clearAllMocks()
  })

  it('KEY-13: against a computer player: sends the request at once, with no vote dialog', async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN' }), 1)
    await requestUndoPriority(GAME)
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('UNDO_LAST_PRIORITY', GAME)
    expect(getState().rollbackVote).toBeNull()
    expect(getState().rollbackPendingFor).toBe(GAME)
  })

  it('KEY-13: the board from the previous turn is shown, not dropped as stale', async () => {
    update(view({ turn: 4, step: 'UPKEEP' }), 1)
    setState({ playableIds: ['c-1'] })
    await requestUndoPriority(GAME)
    chat(announce('Alice', 3, 'End'), 2)
    update(view({ turn: 3, step: 'END_TURN' }), 3)
    const s = getState()
    expect(s.game?.turn).toBe(3)
    expect(s.game?.step).toBe('END_TURN')
    expect(s.playableIds).toEqual([])
    expect(s.rollbackPendingFor).toBeNull()
  })

  it('KEY-13: going back within the same step keeps the newer board', async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN' }), 1)
    await requestUndoPriority(GAME)
    chat(announce('Alice', 4, 'Precombat Main'), 2)
    const restored = view({ turn: 4, step: 'PRECOMBAT_MAIN', canUndo: false })
    update(restored, 3)
    expect(getState().game?.canUndoPriority).toBe(false)
  })

  it('KEY-13: a send failure clears the flag and shows the error', async () => {
    vi.mocked(cmds.sendPlayerAction).mockResolvedValueOnce({ ok: false, error: 'boom' } as never)
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true }), 1)
    await requestUndoPriority(GAME)
    const s = getState()
    expect(s.rollbackVote).toBeNull()
    expect(s.rollbackPendingFor).toBeNull()
    expect(s.error).toBe('boom')
  })

  it('KEY-13: against a human: opens my undo vote, and the announce marks it done', async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true }), 1)
    await requestUndoPriority(GAME)
    const vote = getState().rollbackVote
    expect(vote?.kind).toBe('undo')
    expect(vote?.requestedByMe).toBe(true)
    expect(vote?.voters.map((v) => [v.name, v.status])).toEqual([['Alice', 'requested'], ['Bob', 'pending']])
    chat(announce('Alice', 4, 'Precombat Main'), 2)
    expect(getState().rollbackVote?.outcome).toBe('applied')
    expect(getState().rollbackVote?.appliedTurn).toBe(4)
  })

  it('KEY-13: a denial closes my undo vote with the name of who denied it', async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true }), 1)
    await requestUndoPriority(GAME)
    chat('Undo request denied by Bob', 2)
    const vote = getState().rollbackVote
    expect(vote?.outcome).toBe('denied')
    expect(vote?.deniedBy).toBe('Bob')
    expect(getState().rollbackPendingFor).toBeNull()
  })

  it('KEY-13: nothing to go back to after the vote: the vote fails instead of hanging', async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true }), 1)
    await requestUndoPriority(GAME)
    chat("Can't go back: there's no earlier priority to go back to.", 2)
    expect(getState().rollbackVote?.outcome).toBe('failed')
    expect(getState().rollbackPendingFor).toBeNull()
  })

  it("KEY-13: the other player's request opens an undo vote, and accepting it arms the flag", async () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true, me: 'Alice' }), 1)
    incomingUndoRequest('Bob')
    const vote = getState().rollbackVote
    expect(vote?.kind).toBe('undo')
    expect(vote?.requester).toBe('Bob')
    expect(vote?.myVote).toBe('pending')
    await voteRollback(GAME, true, 'u-Bob')
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('ADD_PERMISSION_TO_ROLLBACK_TURN', GAME, 'u-Bob')
    expect(getState().rollbackPendingFor).toBe(GAME)
  })

  it('KEY-13: a turn rollback request is still a turn rollback vote', () => {
    update(view({ turn: 4, step: 'PRECOMBAT_MAIN', bobIsHuman: true }), 1)
    handleMessage({
      type: 'event',
      method: 'USER_REQUEST_DIALOG',
      messageId: 21,
      objectId: GAME,
      data: {
        title: 'Request by Bob',
        message: 'Allow rollback to the start of the previous turn?',
        gameId: GAME,
        relatedUserId: 'u-Bob',
        relatedUserName: 'Bob',
        button1Text: 'Accept',
        button1Action: 'ADD_PERMISSION_TO_ROLLBACK_TURN',
        button2Text: 'Deny',
        button2Action: 'DENY_PERMISSION_TO_ROLLBACK_TURN',
      },
    } as never)
    expect(getState().rollbackVote?.kind).toBe('turns')
    expect(getState().rollbackVote?.turns).toBe(1)
  })
})

describe('KEY-13 smart stops and going back', () => {
  const cast = { basicManaAbilities: [], basicPlayAbilities: [], basicCastAbilities: [{ id: 'c', value: 'Cast Shock' }], other: [] }

  /** A window where I have priority. With nothing to do smart stops pass it; castable gives a spell to
   * cast, so they show it. */
  function myWindow(step: string, canUndo = true, castable = false): GameView {
    const game = makeGameView({
      turn: 4,
      phase: step === 'UPKEEP' ? 'BEGINNING' : 'PRECOMBAT_MAIN',
      step,
      rollbackTurnsAllowed: true,
      canUndoPriority: canUndo,
      myPlayerId: 'p-Alice',
      ...(castable ? { canPlayObjects: { objects: { shock: cast } } } : {}),
      players: [
        makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: true, hasPriority: true, isActive: true }),
        makePlayer({ playerId: 'p-Bob', name: 'Bob', isHuman: false }),
      ],
    })
    setState({ game, priorityRequest: game })
    return game
  }

  const passes = () => vi.mocked(cmds.sendPlayerAction).mock.calls.filter((c) => c[0] === 'PASS_PRIORITY_AUTO').length
  const settle = () => new Promise((r) => setTimeout(r, 0))

  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    setSetting('smartStops', true)
    handleMessage({ type: 'event', method: 'START_GAME', messageId: 1, objectId: GAME, data: { gameId: GAME } })
    setState({ phaseStops: { yourTurn: {}, opponentTurn: {} } })
  })

  it('KEY-13: where going back works, the smart-stops pass is marked as automatic', () => {
    maybeAutoPass(myWindow('UPKEEP'))
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('PASS_PRIORITY_AUTO', GAME)
    expect(cmds.sendPlayerBoolean).not.toHaveBeenCalled()
  })

  it('KEY-13: on a server without going back, smart stops send a plain pass', () => {
    maybeAutoPass(myWindow('UPKEEP', false))
    expect(passes()).toBe(0)
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledWith(false, GAME)
  })

  it('KEY-13: if the server refuses the marked pass, it passes plainly from then on', async () => {
    vi.mocked(cmds.sendPlayerAction).mockResolvedValueOnce({ ok: false, error: 'unknown action' } as never)
    maybeAutoPass(myWindow('UPKEEP'))
    await settle()
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledWith(false, GAME)
    maybeAutoPass(myWindow('DRAW'))
    await settle()
    expect(passes()).toBe(1)
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledTimes(2)
  })

  it('KEY-13: the window Ctrl+Z goes back to is shown, not passed again', async () => {
    myWindow('PRECOMBAT_MAIN')
    await requestUndoPriority(GAME)
    // the restored window, with nothing to do: shown, even on later renders
    const restored = myWindow('UPKEEP')
    maybeAutoPass(restored)
    maybeAutoPass(restored)
    expect(passes()).toBe(0)
    expect(cmds.sendPlayerBoolean).not.toHaveBeenCalled()
    // the window after it is passed as usual
    maybeAutoPass(myWindow('DRAW'))
    expect(passes()).toBe(1)
  })

  it('KEY-13: the window on screen when pressing Ctrl+Z does not use up the hold', async () => {
    const onScreen = myWindow('PRECOMBAT_MAIN', true, true)
    await requestUndoPriority(GAME)
    maybeAutoPass(onScreen)
    maybeAutoPass(myWindow('UPKEEP'))
    expect(passes()).toBe(0)
  })

  it('KEY-13: a refused undo does not hold the next window', async () => {
    myWindow('PRECOMBAT_MAIN')
    await requestUndoPriority(GAME)
    chat("There's no earlier priority to go back to.", 2)
    maybeAutoPass(myWindow('BEGIN_COMBAT'))
    expect(passes()).toBe(1)
  })
})
