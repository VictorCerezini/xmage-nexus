import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as cmds from '../net/commands'
import GameScreen, { isUndoPriorityShortcut, undoPriorityShortcut } from './GameScreen'
import GameMenu from './GameMenu'
import { getState, initialState, setState } from '../state/state'
import { handleMessage } from '../state/store'
import { playerGameView } from '../__fixtures__/gameViews'
import type { GameView } from '../net/types'
import { setLanguage } from '../i18n'

vi.mock('../net/commands', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../net/commands')>()),
  sendPlayerBoolean: vi.fn(async () => ({ ok: true })),
  sendPlayerAction: vi.fn(async () => ({ ok: true })),
  sendChatMessage: vi.fn(async () => ({ ok: true })),
}))

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size },
    clear: () => m.clear(),
    getItem: (k) => (m.has(k) ? m.get(k)! : null),
    key: (i) => Array.from(m.keys())[i] ?? null,
    removeItem: (k) => { m.delete(k) },
    setItem: (k, v) => { m.set(k, String(v)) },
  }
}

type Mods = { ctrl?: boolean; meta?: boolean; shift?: boolean; alt?: boolean; repeat?: boolean }

function pressZ(mods: Mods = { ctrl: true }, target: EventTarget = document.body, key = 'z', code = 'KeyZ') {
  target.dispatchEvent(new KeyboardEvent('keydown', {
    key,
    code,
    ctrlKey: !!mods.ctrl,
    metaKey: !!mods.meta,
    shiftKey: !!mods.shift,
    altKey: !!mods.alt,
    repeat: !!mods.repeat,
    bubbles: true,
    cancelable: true,
  }))
}

/** The live game: I (Alice) have priority; I can go back 3 of my priorities. I don't control Bob. */
const live: GameView = { ...playerGameView, opponentHands: {}, rollbackTurnsAllowed: true, canUndoPriority: true, undoPriorityCount: 3 }

function withGame(patch: Partial<GameView>) {
  setState({ game: { ...live, ...patch } } as never)
}

/** Bob is a computer player: going back needs nobody's answer. */
const bobIsComputer = () => withGame({ players: (live.players ?? []).map((p) => (p.name === 'Bob' ? { ...p, isHuman: false } : p)) })

/** The game as it was some of my priorities back. */
const boardBack = (turn: number, step = 'UPKEEP'): GameView => ({ ...live, turn, step, phase: 'BEGINNING' })

let messageId = 100
function serverPreview(data: Record<string, unknown>) {
  handleMessage({ type: 'event', method: 'GAME_UNDO_PREVIEW', messageId: ++messageId, objectId: 'game-1', data } as never)
}
const answer = (steps: number, board: GameView | null = boardBack(2), extra: Record<string, unknown> = {}) =>
  serverPreview({ board, steps, available: 3, vote: false, requesterName: 'Alice', ...extra })

const calls = (action: string) => vi.mocked(cmds.sendPlayerAction).mock.calls.filter((c) => c[0] === action)
const undoCalls = () => calls('UNDO_LAST_PRIORITY')
const previewCalls = () => calls('UNDO_PREVIEW')
const root = () => document.querySelector('.game') as HTMLElement

beforeEach(() => {
  setLanguage('en')
  vi.stubGlobal('localStorage', memoryStorage())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 404, json: async () => ({}), text: async () => '' })))
  vi.mocked(cmds.sendPlayerAction).mockClear()
  setState({
    ...initialState,
    settings: { ...initialState.settings, gameUi: 'v1' },
    conn: { serverHost: 'localhost', port: 17171, username: 'tester', wsHost: 'localhost', proxyPort: 8787 },
    gameId: 'game-1',
    game: live,
    myPlayerId: playerGameView.myPlayerId,
  } as never)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  setState({ ...initialState } as never)
})

describe('KEY-13 Ctrl+Z looks at the game some of my priorities back', () => {
  it('KEY-13: Ctrl+Z asks to see one priority back, only for me; nothing goes back yet', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toEqual([['UNDO_PREVIEW', 'game-1', 1]])
    expect(undoCalls()).toHaveLength(0)
    expect(getState().undoPreview?.mode).toBe('browse')
    // a held key asks nothing more
    await act(async () => { pressZ({ ctrl: true, repeat: true }) })
    expect(previewCalls()).toHaveLength(1)
  })

  it('KEY-13: the board back there is shown with how far back it is; the live game is kept', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    expect(screen.getByTestId('undo-preview-steps').textContent).toBe('1 priority back')
    expect(screen.getByTestId('undo-preview-where').textContent).toContain('Turn 2')
    // the step as plain text, not the phase bar's pill (which narrow screens hide)
    const step = within(screen.getByTestId('undo-preview-where')).getByTestId('step-name')
    expect(step.textContent).toBe('Upkeep')
    expect(step.classList.contains('phase-name')).toBe(false)
    expect(root().dataset.undoPreview).toBe('browse')
    expect(root().dataset.rewind).toBe('true')
    expect(getState().game).toBe(live)
  })

  it('KEY-13: Ctrl+Z again goes further back, Ctrl+Shift+Z forward, and forward from 1 is the game as it is', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls().at(-1)).toEqual(['UNDO_PREVIEW', 'game-1', 2])
    await act(async () => { answer(2, boardBack(1)) })
    expect(screen.getByTestId('undo-preview-steps').textContent).toBe('2 priorities back')

    await act(async () => { pressZ({ ctrl: true, shift: true }) })
    expect(previewCalls().at(-1)).toEqual(['UNDO_PREVIEW', 'game-1', 1])
    await act(async () => { answer(1) })
    expect(screen.getByTestId('undo-preview-steps').textContent).toBe('1 priority back')

    const asked = previewCalls().length
    await act(async () => { pressZ({ ctrl: true, shift: true }) })
    expect(previewCalls()).toHaveLength(asked)
    expect(getState().undoPreview).toBeNull()
    expect(screen.queryByTestId('undo-preview-bar')).toBeNull()
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: no further back than the server keeps', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(2) })
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(3) })
    expect((screen.getByTestId('undo-preview-back') as HTMLButtonElement).disabled).toBe(true)
    const asked = previewCalls().length
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toHaveLength(asked)
  })

  it('KEY-13: a late answer to an older question does not replace the one asked last', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    expect(getState().undoPreview?.pendingSteps).toBe(2)
    expect((screen.getByTestId('undo-preview-confirm') as HTMLButtonElement).disabled).toBe(true)
    await act(async () => { answer(2, boardBack(1)) })
    expect(getState().undoPreview?.steps).toBe(2)
    expect((screen.getByTestId('undo-preview-confirm') as HTMLButtonElement).disabled).toBe(false)
  })

  it('KEY-13: Cancel or Esc ends the preview and sends nothing', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { fireEvent.click(screen.getByTestId('undo-preview-cancel')) })
    expect(getState().undoPreview).toBeNull()

    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })) })
    expect(getState().undoPreview).toBeNull()
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: against a computer player, Confirm rollback goes back that many priorities at once', async () => {
    bobIsComputer()
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(2, boardBack(1)) })
    await act(async () => { fireEvent.click(screen.getByTestId('undo-preview-confirm')) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1', 2]])
    // the confirmed board stays until the server sends the game as it was there
    expect(getState().undoPreview?.mode).toBe('applying')
    expect(screen.queryByTestId('undo-preview-bar')).toBeNull()
    expect(root().dataset.undoPreview).toBe('applying')
  })

  it('KEY-13: one priority back is sent as before (no count), so an older server understands it', async () => {
    bobIsComputer()
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { fireEvent.click(screen.getByTestId('undo-preview-confirm')) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1']])
  })

  it('KEY-13: against a human, Confirm rollback asks them, with the board grayed out on that point', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(2, boardBack(1)) })
    await act(async () => { fireEvent.click(screen.getByTestId('undo-preview-confirm')) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1', 2]])
    expect(getState().undoPreview?.mode).toBe('vote')
    expect(getState().rollbackVote?.steps).toBe(2)
    expect(root().classList.contains('game--undo-vote')).toBe(true)
    expect(screen.getByText('You asked to go back 2 priorities')).toBeTruthy()
    expect(screen.getByTestId('undo-vote-now').textContent).toContain(`Now: turn ${live.turn}`)
    expect(screen.getByTestId('undo-vote-where').textContent).toContain('Back to: turn 1')
    expect(screen.getByTestId('undo-vote-withdraw')).toBeTruthy()
  })

  it('KEY-13: the game going on (a new priority request) ends looking back', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(1) })
    await act(async () => {
      handleMessage({
        type: 'event',
        method: 'GAME_SELECT',
        messageId: ++messageId,
        objectId: 'game-1',
        data: { gameView: { ...live, turn: (live.turn ?? 1) }, message: 'Play spells and abilities', options: {} },
      } as never)
    })
    expect(getState().undoPreview).toBeNull()
  })

  it('KEY-13: when the server has nothing to show (the game no longer waits on me), the preview ends', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    await act(async () => { answer(0, null, { available: 0 }) })
    expect(getState().undoPreview).toBeNull()
  })

  it('KEY-13: while the game waits on someone else, Ctrl+Z stops it first, and the bar says so', async () => {
    withGame({ players: (live.players ?? []).map((p) => ({ ...p, hasPriority: p.name === 'Bob' })), priorityPlayerName: 'Bob' })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(calls('UNDO_HOLD')).toEqual([['UNDO_HOLD', 'game-1']])
    expect(previewCalls()).toHaveLength(0)
    expect(screen.getByTestId('undo-preview-loading').textContent).toBe('Stopping the game at the next priority…')
    // once it's stopped, the server shows the board one priority back
    await act(async () => { answer(1) })
    expect(screen.getByTestId('undo-preview-steps').textContent).toBe('1 priority back')
    // Cancel: the game goes on, nothing went back
    fireEvent.click(screen.getByTestId('undo-preview-cancel'))
    expect(calls('UNDO_HOLD_RELEASE')).toEqual([['UNDO_HOLD_RELEASE', 'game-1']])
    expect(getState().undoPreview).toBeNull()
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: works while a prompt is open (e.g. choosing targets)', async () => {
    withGame({ players: (live.players ?? []).map((p) => ({ ...p, hasPriority: false })) })
    handleMessage({
      type: 'event',
      method: 'GAME_TARGET',
      messageId: 8,
      objectId: 'game-1',
      data: { message: 'Select a target', min: 1, max: 1, options: { possibleTargets: ['p-untapped'] } },
    } as never)
    expect(getState().feedback?.method).toBe('GAME_TARGET')
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toHaveLength(1)
  })

  it('KEY-13: on a server without the preview, Ctrl+Z goes back to the last priority at once', async () => {
    withGame({ undoPriorityCount: undefined })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1']])
    expect(previewCalls()).toHaveLength(0)
  })

  it('KEY-13: Cmd+Z works too (macOS)', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ meta: true }) })
    expect(previewCalls()).toHaveLength(1)
  })

  it('KEY-13: sends nothing when the server says there is nothing to go back to', async () => {
    withGame({ canUndoPriority: false })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toHaveLength(0)
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: sends nothing for a spectator', async () => {
    withGame({ players: (live.players ?? []).map((p) => ({ ...p, controlled: false })) })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toHaveLength(0)
  })

  it('KEY-13: sends nothing while a rollback vote is on screen', async () => {
    setState({
      rollbackVote: {
        gameId: 'game-1', requester: 'Bob', requestedByMe: false, kind: 'undo', turns: 0, requestedAtTurn: 1,
        voters: [], myVote: 'pending', outcome: 'voting', hidden: false, startedAt: Date.now(),
      },
    } as never)
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(previewCalls()).toHaveLength(0)
  })

  it('KEY-13: leaves Ctrl+Z alone in a text field; Ctrl+Shift+Z and Ctrl+Alt+Z start nothing', async () => {
    render(<GameScreen />)
    const input = document.createElement('input')
    document.body.appendChild(input)
    await act(async () => { pressZ({ ctrl: true }, input) })
    await act(async () => { pressZ({ ctrl: true, shift: true }) })
    await act(async () => { pressZ({ ctrl: true, alt: true }) })
    await act(async () => { pressZ({}) })
    input.remove()
    expect(previewCalls()).toHaveLength(0)
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: matches the Z key on layouts without Latin letters, but not another letter', () => {
    const base = { ctrlKey: true, metaKey: false, shiftKey: false, altKey: false, target: document.body }
    expect(isUndoPriorityShortcut({ ...base, key: 'я', code: 'KeyZ' })).toBe(true)
    expect(isUndoPriorityShortcut({ ...base, key: 'Z', code: 'KeyZ' })).toBe(true)
    // AZERTY: the key labelled Z sits where QWERTY has W
    expect(isUndoPriorityShortcut({ ...base, key: 'z', code: 'KeyW' })).toBe(true)
    expect(isUndoPriorityShortcut({ ...base, key: 'w', code: 'KeyZ' })).toBe(false)
    expect(undoPriorityShortcut({ ...base, shiftKey: true, key: 'Z', code: 'KeyZ' })).toBe('forward')
  })
})

describe('KEY-13 the other player asks to go back', () => {
  function incomingRequest(steps: number) {
    const what = steps > 1 ? `${steps} priorities` : 'to their last priority'
    handleMessage({
      type: 'event',
      method: 'USER_REQUEST_DIALOG',
      messageId: ++messageId,
      objectId: 'game-1',
      data: {
        title: 'Request by Bob',
        message: `Allow Bob to go back ${what} (turn 1, Upkeep)?`,
        gameId: 'game-1',
        relatedUserId: 'u-Bob',
        relatedUserName: 'Bob',
        button1Text: 'Accept',
        button1Action: 'ADD_PERMISSION_TO_ROLLBACK_TURN',
        button2Text: 'Deny',
        button2Action: 'DENY_PERMISSION_TO_ROLLBACK_TURN',
      },
    } as never)
  }

  it('KEY-13: my screen grays out on the point Bob would go back to, and the dialog says how far', async () => {
    withGame({ turn: 3, phase: 'COMBAT', step: 'DECLARE_BLOCKERS' })
    render(<GameScreen />)
    await act(async () => {
      serverPreview({ board: boardBack(1), steps: 3, available: 3, vote: true, requesterName: 'Bob' })
      incomingRequest(3)
    })
    expect(getState().undoPreview?.mode).toBe('vote')
    expect(getState().undoPreview?.requestedByMe).toBe(false)
    expect(root().classList.contains('game--undo-vote')).toBe(true)
    expect(screen.getByText('Bob asks to go back 3 priorities')).toBeTruthy()
    // where the game is now, then where it would go back to, each with its step
    const now = screen.getByTestId('undo-vote-now')
    const where = screen.getByTestId('undo-vote-where')
    expect(now.textContent).toContain('Now: turn 3')
    expect(within(now).getByTestId('step-name').textContent).toBe('Blockers')
    expect(where.textContent).toContain('Back to: turn 1')
    expect(within(where).getByTestId('step-name').textContent).toBe('Upkeep')
    expect(now.compareDocumentPosition(where) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // the live game is not touched
    expect(getState().game?.turn).toBe(3)
  })

  it('KEY-13: if the request is denied, the board goes back to the game as it is', async () => {
    render(<GameScreen />)
    await act(async () => {
      serverPreview({ board: boardBack(1), steps: 2, available: 3, vote: true, requesterName: 'Bob' })
      incomingRequest(2)
    })
    await act(async () => { serverPreview({ board: null, steps: 0, available: 0, vote: false, requesterName: '' }) })
    expect(getState().undoPreview).toBeNull()
    expect(root().classList.contains('game--undo-vote')).toBe(false)
  })
})

describe('KEY-13 another player stopped the game to look back', () => {
  const pressKey = (key: string, code = key) =>
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key, code, bubbles: true, cancelable: true }))

  it('KEY-13: I see who stopped it; Space, the skip keys and Ctrl+Z send nothing until it goes on', async () => {
    vi.mocked(cmds.sendPlayerBoolean).mockClear()
    withGame({ undoHeldBy: 'Bob' })
    render(<GameScreen />)
    expect(screen.getByTestId('undo-hold-notice').textContent).toContain('Bob is looking back at earlier priorities')
    await act(async () => { pressKey(' ', 'Space') })
    await act(async () => { pressKey('F4') })
    await act(async () => { pressZ({ ctrl: true }) })
    expect(cmds.sendPlayerBoolean).not.toHaveBeenCalled()
    expect(cmds.sendPlayerAction).not.toHaveBeenCalled()
    expect(getState().undoPreview).toBeNull()
    // the game goes on: the notice goes, and Space passes again
    await act(async () => { withGame({ undoHeldBy: null }) })
    expect(screen.queryByTestId('undo-hold-notice')).toBeNull()
    await act(async () => { pressKey(' ', 'Space') })
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledTimes(1)
  })
})

describe('KEY-13 menu item', () => {
  const openMenu = () => fireEvent.click(screen.getByTestId('game-menu-btn'))

  it('KEY-13: starts looking back from the ⋯ menu', async () => {
    render(<GameMenu />)
    openMenu()
    const item = screen.getByTestId('game-menu-undo-priority')
    expect((item as HTMLButtonElement).disabled).toBe(false)
    await act(async () => { fireEvent.click(item) })
    expect(previewCalls()).toEqual([['UNDO_PREVIEW', 'game-1', 1]])
    expect(getState().undoPreview?.mode).toBe('browse')
  })

  it('KEY-13: while the game waits on someone else, it stops the game first', async () => {
    withGame({ players: (live.players ?? []).map((p) => ({ ...p, hasPriority: p.name === 'Bob' })), priorityPlayerName: 'Bob' })
    render(<GameMenu />)
    openMenu()
    const item = screen.getByTestId('game-menu-undo-priority') as HTMLButtonElement
    expect(item.disabled).toBe(false)
    expect(item.title).toContain('it stops for everyone at the next priority')
    await act(async () => { fireEvent.click(item) })
    expect(calls('UNDO_HOLD')).toEqual([['UNDO_HOLD', 'game-1']])
    expect(previewCalls()).toHaveLength(0)
  })

  it('KEY-13: is disabled while another player looks back', () => {
    withGame({ undoHeldBy: 'Bob' })
    render(<GameMenu />)
    openMenu()
    const item = screen.getByTestId('game-menu-undo-priority') as HTMLButtonElement
    expect(item.disabled).toBe(true)
    expect(item.title).toContain('Bob is looking back at earlier priorities')
  })

  it('KEY-13: is disabled when there is nothing to go back to', () => {
    withGame({ canUndoPriority: false })
    render(<GameMenu />)
    openMenu()
    expect((screen.getByTestId('game-menu-undo-priority') as HTMLButtonElement).disabled).toBe(true)
  })

  it('KEY-13: is hidden when the server does not send the flag, or rollbacks are off', () => {
    withGame({ canUndoPriority: undefined })
    const { unmount } = render(<GameMenu />)
    openMenu()
    expect(screen.queryByTestId('game-menu-undo-priority')).toBeNull()
    unmount()
    withGame({ rollbackTurnsAllowed: false })
    render(<GameMenu />)
    openMenu()
    expect(screen.queryByTestId('game-menu-undo-priority')).toBeNull()
  })
})
