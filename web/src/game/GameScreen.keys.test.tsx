import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as cmds from '../net/commands'
import GameScreen from './GameScreen'
import ConfirmHost from '../ui/ConfirmHost'
import { initialState, setState } from '../state/state'
import { makeCard, makeGameView, makePlayer, playerGameView } from '../__fixtures__/gameViews'

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

function press(key: string, code: string, repeat: boolean) {
  document.body.dispatchEvent(new KeyboardEvent('keydown', { key, code, repeat, bubbles: true, cancelable: true }))
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 404, json: async () => ({}), text: async () => '' })))
  vi.mocked(cmds.sendPlayerBoolean).mockClear()
  vi.mocked(cmds.sendPlayerAction).mockClear()
  setState({
    ...initialState,
    settings: { ...initialState.settings, gameUi: 'v1' },
    conn: { serverHost: 'localhost', port: 17171, username: 'tester', wsHost: 'localhost', proxyPort: 8787 },
    gameId: 'game-1',
    game: playerGameView,
    myPlayerId: playerGameView.myPlayerId,
  } as never)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  setState({ ...initialState } as never)
})

describe('GameScreen held keys', () => {
  it('KEY-1, KEY-5: a held Space sends one pass, not one per auto-repeat keydown', async () => {
    render(<GameScreen />)
    await act(async () => { press(' ', 'Space', false) })
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledTimes(1)
    await act(async () => { press(' ', 'Space', true) })
    await act(async () => { press(' ', 'Space', true) })
    expect(cmds.sendPlayerBoolean).toHaveBeenCalledTimes(1)
  })

  it('KEY-5, KEY-6: a held skip key (F4) sends one skip, not one per auto-repeat keydown', async () => {
    render(<GameScreen />)
    await act(async () => { press('F4', 'F4', true) })
    expect(cmds.sendPlayerAction).not.toHaveBeenCalled()
    await act(async () => { press('F4', 'F4', false) })
    expect(cmds.sendPlayerAction).toHaveBeenCalledTimes(1)
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('PASS_PRIORITY_UNTIL_NEXT_TURN', 'game-1')
    await act(async () => { press('F4', 'F4', true) })
    expect(cmds.sendPlayerAction).toHaveBeenCalledTimes(1)
  })
})

/** My own upkeep with a land in hand: skipping to the end step from here passes my main phases. */
const myUpkeep = makeGameView({
  phase: 'BEGINNING',
  step: 'UPKEEP',
  players: [
    makePlayer({ playerId: 'p1', name: 'Alice', controlled: true, isActive: true, hasPriority: true }),
    makePlayer({ playerId: 'p2', name: 'Bob' }),
  ],
  myPlayerId: 'p1',
  activePlayerId: 'p1',
  activePlayerName: 'Alice',
  priorityPlayerName: 'Alice',
  myHand: { 'h-1': makeCard({ name: "Rogue's Passage", parentId: 'h-1' }) },
})

describe('GameScreen skip that passes my own main phase', () => {
  it('KEY-6: F5 in my own upkeep with a card in hand asks first, and only "Skip anyway" sends it', async () => {
    setState({ game: myUpkeep } as never)
    render(<><GameScreen /><ConfirmHost /></>)
    await act(async () => { press('F5', 'F5', false) })
    expect(screen.getByTestId('confirm-modal')).toBeTruthy()
    expect(cmds.sendPlayerAction).not.toHaveBeenCalled()
    // pressed again while it asks: still one question
    await act(async () => { press('F5', 'F5', false) })
    expect(screen.getAllByTestId('confirm-modal')).toHaveLength(1)
    await act(async () => { fireEvent.click(screen.getByTestId('confirm-modal-cancel')) })
    expect(screen.queryByTestId('confirm-modal')).toBeNull()
    expect(cmds.sendPlayerAction).not.toHaveBeenCalled()

    await act(async () => { press('F5', 'F5', false) })
    await act(async () => { fireEvent.click(screen.getByTestId('confirm-modal-ok')) })
    expect(cmds.sendPlayerAction).toHaveBeenCalledTimes(1)
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('PASS_PRIORITY_UNTIL_TURN_END_STEP', 'game-1')
  })

  it('KEY-6: the ▾ menu asks the same before "Next turn" passes my own main phase', async () => {
    setState({ game: myUpkeep } as never)
    render(<><GameScreen /><ConfirmHost /></>)
    await act(async () => { fireEvent.click(screen.getByTestId('pass-split-toggle')) })
    await act(async () => { fireEvent.click(screen.getByTestId('skip-turn')) })
    expect(screen.getByTestId('confirm-modal')).toBeTruthy()
    expect(cmds.sendPlayerAction).not.toHaveBeenCalled()
    await act(async () => { fireEvent.click(screen.getByTestId('confirm-modal-ok')) })
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('PASS_PRIORITY_UNTIL_NEXT_TURN', 'game-1')
  })

  it('KEY-6: F7 (next main phase) in my own upkeep sends at once, since it stops at my main phase', async () => {
    setState({ game: myUpkeep } as never)
    render(<><GameScreen /><ConfirmHost /></>)
    await act(async () => { press('F7', 'F7', false) })
    expect(screen.queryByTestId('confirm-modal')).toBeNull()
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE', 'game-1')
  })
})
