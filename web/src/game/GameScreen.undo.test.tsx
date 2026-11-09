import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as cmds from '../net/commands'
import GameScreen, { isUndoPriorityShortcut } from './GameScreen'
import GameMenu from './GameMenu'
import { getState, initialState, setState } from '../state/state'
import { handleMessage } from '../state/store'
import { playerGameView } from '../__fixtures__/gameViews'
import type { GameView } from '../net/types'

vi.mock('../net/commands', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../net/commands')>()),
  sendPlayerBoolean: vi.fn(async () => ({ ok: true })),
  sendPlayerAction: vi.fn(async () => ({ ok: true })),
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

function withGame(patch: Partial<GameView>) {
  setState({ game: { ...playerGameView, rollbackTurnsAllowed: true, canUndoPriority: true, ...patch } } as never)
}

const undoCalls = () => vi.mocked(cmds.sendPlayerAction).mock.calls.filter((c) => c[0] === 'UNDO_LAST_PRIORITY')

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 404, json: async () => ({}), text: async () => '' })))
  vi.mocked(cmds.sendPlayerAction).mockClear()
  setState({
    ...initialState,
    settings: { ...initialState.settings, gameUi: 'v1' },
    conn: { serverHost: 'localhost', port: 17171, username: 'tester', wsHost: 'localhost', proxyPort: 8787 },
    gameId: 'game-1',
    game: { ...playerGameView, rollbackTurnsAllowed: true, canUndoPriority: true },
    myPlayerId: playerGameView.myPlayerId,
  } as never)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  setState({ ...initialState } as never)
})

describe('KEY-13 Ctrl+Z goes back to my last priority', () => {
  it('KEY-13: Ctrl+Z sends one request; a held key does not send more', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1']])
    await act(async () => { pressZ({ ctrl: true, repeat: true }) })
    expect(undoCalls()).toHaveLength(1)
  })

  it('KEY-13: Cmd+Z works too (macOS)', async () => {
    render(<GameScreen />)
    await act(async () => { pressZ({ meta: true }) })
    expect(undoCalls()).toHaveLength(1)
  })

  it('KEY-13: works while a prompt is open (e.g. choosing targets)', async () => {
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
    expect(undoCalls()).toHaveLength(1)
  })

  it('KEY-13: sends nothing when the server says there is nothing to go back to', async () => {
    withGame({ canUndoPriority: false })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: sends nothing for a spectator', async () => {
    withGame({ players: (playerGameView.players ?? []).map((p) => ({ ...p, controlled: false })) })
    render(<GameScreen />)
    await act(async () => { pressZ({ ctrl: true }) })
    expect(undoCalls()).toHaveLength(0)
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
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: leaves Ctrl+Z alone in a text field, and ignores Ctrl+Shift+Z and Ctrl+Alt+Z', async () => {
    render(<GameScreen />)
    const input = document.createElement('input')
    document.body.appendChild(input)
    await act(async () => { pressZ({ ctrl: true }, input) })
    await act(async () => { pressZ({ ctrl: true, shift: true }) })
    await act(async () => { pressZ({ ctrl: true, alt: true }) })
    await act(async () => { pressZ({}) })
    input.remove()
    expect(undoCalls()).toHaveLength(0)
  })

  it('KEY-13: matches the Z key on layouts without Latin letters, but not another letter', () => {
    const base = { ctrlKey: true, metaKey: false, shiftKey: false, altKey: false, target: document.body }
    expect(isUndoPriorityShortcut({ ...base, key: 'я', code: 'KeyZ' })).toBe(true)
    expect(isUndoPriorityShortcut({ ...base, key: 'Z', code: 'KeyZ' })).toBe(true)
    // AZERTY: the key labelled Z sits where QWERTY has W
    expect(isUndoPriorityShortcut({ ...base, key: 'z', code: 'KeyW' })).toBe(true)
    expect(isUndoPriorityShortcut({ ...base, key: 'w', code: 'KeyZ' })).toBe(false)
  })
})

describe('KEY-13 menu item', () => {
  const openMenu = () => fireEvent.click(screen.getByTestId('game-menu-btn'))

  it('KEY-13: sends the request from the ⋯ menu', async () => {
    render(<GameMenu />)
    openMenu()
    const item = screen.getByTestId('game-menu-undo-priority')
    expect((item as HTMLButtonElement).disabled).toBe(false)
    await act(async () => { fireEvent.click(item) })
    expect(undoCalls()).toEqual([['UNDO_LAST_PRIORITY', 'game-1']])
    expect(getState().rollbackPendingFor).toBe('game-1')
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
