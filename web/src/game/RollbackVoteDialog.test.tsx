import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react'
import RollbackVoteDialog, { ROLLBACK_AUTO_DISMISS_MS, ROLLBACK_HIDE_AFTER_MS } from './RollbackVoteDialog'
import { setState, getState } from '../state/state'
import { reset, isBlockingModal } from '../state/store'
import type { RollbackVote } from '../state/store'
import { makeGameView, makePlayer } from '../__fixtures__/gameViews'
import * as cmds from '../net/commands'

vi.mock('../net/commands', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../net/commands')>()
  return {
    ...actual,
    sendPlayerAction: vi.fn().mockResolvedValue({ ok: true }),
    sendChatMessage: vi.fn().mockResolvedValue({ ok: true }),
  }
})

function vote(partial: Partial<RollbackVote> = {}): RollbackVote {
  return {
    gameId: 'g-1',
    requester: 'Alice',
    requesterUserId: 'u-alice',
    requestedByMe: false,
    turns: 0,
    requestedAtTurn: 3,
    voters: [
      { name: 'Alice', status: 'requested', me: false },
      { name: 'Bob', status: 'pending', me: true },
      { name: 'Carol', status: 'accepted', me: false },
      { name: 'Dave', status: 'pending', me: false },
    ],
    myVote: 'pending',
    outcome: 'voting',
    hidden: false,
    startedAt: Date.now(),
    ...partial,
  }
}

const statusOf = () => screen.getAllByTestId('rollback-voter').map((el) => el.getAttribute('data-status'))

describe('RollbackVoteDialog', () => {
  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    setState({
      gameId: 'g-1',
      gameChatId: 'c-1',
      game: makeGameView({ turn: 3, players: [makePlayer({ playerId: 'p-bob', name: 'Bob', controlled: true })] }),
    })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('renders nothing without a vote and blocks the board while one is shown', () => {
    const { container } = render(<RollbackVoteDialog />)
    expect(container.innerHTML).toBe('')
    expect(isBlockingModal(getState())).toBe(false)
    act(() => setState({ rollbackVote: vote() }))
    expect(screen.getByTestId('rollback-vote')).toBeTruthy()
    expect(isBlockingModal(getState())).toBe(true)
  })

  it('lists every player with their vote status', () => {
    setState({ rollbackVote: vote() })
    render(<RollbackVoteDialog />)
    expect(screen.getAllByTestId('rollback-voter').map((el) => el.textContent)).toEqual([
      expect.stringContaining('Alice'),
      expect.stringContaining('Bob'),
      expect.stringContaining('Carol'),
      expect.stringContaining('Dave'),
    ])
    expect(statusOf()).toEqual(['requested', 'pending', 'accepted', 'pending'])
    expect(screen.getByTestId('rollback-vote-outcome').getAttribute('data-outcome')).toBe('voting')
  })

  it('Accept sends the vote, announces it and switches to waiting', async () => {
    setState({ rollbackVote: vote() })
    render(<RollbackVoteDialog />)
    await act(async () => {
      fireEvent.click(screen.getByTestId('rollback-vote-accept'))
    })
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('ADD_PERMISSION_TO_ROLLBACK_TURN', 'g-1', 'u-alice')
    expect(cmds.sendChatMessage).toHaveBeenCalled()
    expect(statusOf()).toEqual(['requested', 'accepted', 'accepted', 'pending'])
    expect(screen.queryByTestId('rollback-vote-accept')).toBeNull()
    expect(screen.queryByTestId('rollback-vote-close')).toBeNull()
  })

  it('Deny sends the deny action and shows the denied outcome', async () => {
    setState({ rollbackVote: vote() })
    render(<RollbackVoteDialog />)
    await act(async () => {
      fireEvent.click(screen.getByTestId('rollback-vote-deny'))
    })
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('DENY_PERMISSION_TO_ROLLBACK_TURN', 'g-1', 'u-alice')
    expect(screen.getByTestId('rollback-vote-outcome').getAttribute('data-outcome')).toBe('denied')
    expect(screen.getByTestId('rollback-vote-close')).toBeTruthy()
  })

  it('after the resolution Close dismisses it, and it auto-dismisses otherwise', () => {
    vi.useFakeTimers()
    setState({ rollbackVote: vote({ outcome: 'applied', appliedTurn: 3, myVote: 'accepted' }) })
    render(<RollbackVoteDialog />)
    expect(screen.getByTestId('rollback-vote-outcome').getAttribute('data-outcome')).toBe('applied')
    act(() => { vi.advanceTimersByTime(ROLLBACK_AUTO_DISMISS_MS + 10) })
    expect(getState().rollbackVote).toBeNull()

    act(() => setState({ rollbackVote: vote({ outcome: 'denied', deniedBy: 'Dave', myVote: 'accepted' }) }))
    fireEvent.click(screen.getByTestId('rollback-vote-close'))
    expect(getState().rollbackVote).toBeNull()
  })

  it('while waiting on others, the hide escape appears only after a while', () => {
    vi.useFakeTimers()
    setState({ rollbackVote: vote({ myVote: 'accepted' }) })
    render(<RollbackVoteDialog />)
    expect(screen.queryByTestId('rollback-vote-hide')).toBeNull()
    act(() => { vi.advanceTimersByTime(ROLLBACK_HIDE_AFTER_MS + 10) })
    fireEvent.click(screen.getByTestId('rollback-vote-hide'))
    expect(getState().rollbackVote?.hidden).toBe(true)
    expect(screen.queryByTestId('rollback-vote')).toBeNull()
    expect(isBlockingModal(getState())).toBe(false)
  })

  it('warns when the game moved on past the turn of the request', () => {
    setState({
      rollbackVote: vote({ myVote: 'accepted' }),
      game: makeGameView({ turn: 4, players: [makePlayer({ playerId: 'p-bob', name: 'Bob', controlled: true })] }),
    })
    render(<RollbackVoteDialog />)
    expect(screen.getByRole('alert').textContent).toMatch(/4/)
  })
})

describe('KEY-13 the vote dialog can be dragged aside', () => {
  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    setState({
      gameId: 'g-1',
      gameChatId: 'c-1',
      game: makeGameView({ turn: 3, players: [makePlayer({ playerId: 'p-bob', name: 'Bob', controlled: true })] }),
    })
  })

  afterEach(() => cleanup())

  function pointer(type: string, x: number, y: number) {
    return new window.MouseEvent(type, { bubbles: true, cancelable: true, button: 0, clientX: x, clientY: y })
  }

  // jsdom has no layout: the panel is drawn 460×300 at (282, 20) on its 1024×768 screen;
  // at an interface scale of 2 that is 230 of the panel's own pixels wide
  function placePanel(scale = 1) {
    const panel = screen.getByTestId('rollback-vote')
    Object.defineProperty(panel, 'offsetWidth', { configurable: true, value: 460 / scale })
    panel.getBoundingClientRect = () => ({ left: 282, top: 20, width: 460, height: 300, right: 742, bottom: 320, x: 282, y: 20, toJSON: () => ({}) }) as DOMRect
    return panel
  }

  const title = () => document.getElementById('rollback-vote-title') as HTMLElement

  it('KEY-13: dragging the dialog moves it, never off the screen, and stops on release', () => {
    expect(window.innerWidth).toBe(1024)
    expect(window.innerHeight).toBe(768)
    setState({ rollbackVote: vote({ kind: 'undo', steps: 3 }) })
    render(<RollbackVoteDialog />)
    const panel = placePanel()
    expect(panel.style.left).toBe('')

    fireEvent(title(), pointer('pointerdown', 500, 40))
    expect(panel.className).toContain('is-moving')
    fireEvent(panel, pointer('pointermove', 400, 140))
    expect([panel.style.left, panel.style.top]).toEqual(['-100px', '100px'])

    // far past the corner: it stops 8px from the edges (left 8, top 768 - 300 - 8)
    fireEvent(panel, pointer('pointermove', -2000, 5000))
    expect([panel.style.left, panel.style.top]).toEqual(['-274px', '440px'])

    fireEvent(panel, pointer('pointerup', -2000, 5000))
    expect(panel.className).not.toContain('is-moving')
    fireEvent(panel, pointer('pointermove', 600, 40))
    expect([panel.style.left, panel.style.top]).toEqual(['-274px', '440px'])
  })

  it('KEY-13: pressing a button does not drag the dialog, and the button still works', async () => {
    setState({ rollbackVote: vote({ kind: 'undo', steps: 3 }) })
    render(<RollbackVoteDialog />)
    const panel = placePanel()
    const accept = screen.getByTestId('rollback-vote-accept')

    fireEvent(accept, pointer('pointerdown', 500, 300))
    fireEvent(panel, pointer('pointermove', 100, 100))
    expect(panel.style.left).toBe('')
    expect(panel.className).not.toContain('is-moving')

    await act(async () => {
      fireEvent.click(accept)
    })
    expect(cmds.sendPlayerAction).toHaveBeenCalledWith('ADD_PERMISSION_TO_ROLLBACK_TURN', 'g-1', 'u-alice')
  })

  it('KEY-13: it follows the pointer at any interface scale', () => {
    setState({ rollbackVote: vote() })
    render(<RollbackVoteDialog />)
    const panel = placePanel(2)

    // 100 × 50 screen pixels at scale 2 are 50 × 25 of the panel's own pixels
    fireEvent(title(), pointer('pointerdown', 500, 40))
    fireEvent(panel, pointer('pointermove', 600, 90))
    expect([panel.style.left, panel.style.top]).toEqual(['50px', '25px'])
  })

  it('KEY-13: a smaller window pulls a moved dialog back inside the screen', () => {
    setState({ rollbackVote: vote({ kind: 'undo', steps: 3 }) })
    render(<RollbackVoteDialog />)
    const panel = placePanel()
    fireEvent(title(), pointer('pointerdown', 500, 40))
    fireEvent(panel, pointer('pointermove', 400, 140))
    fireEvent(panel, pointer('pointerup', 400, 140))
    expect([panel.style.left, panel.style.top]).toEqual(['-100px', '100px'])

    // now drawn at (182, 120); the window shrinks to 500 wide: at most 500 - 460 - 8 = 32 from the left
    panel.getBoundingClientRect = () => ({ left: 182, top: 120, width: 460, height: 300, right: 642, bottom: 420, x: 182, y: 120, toJSON: () => ({}) }) as DOMRect
    const width = window.innerWidth
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 500 })
    try {
      act(() => { window.dispatchEvent(new Event('resize')) })
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
    }
    expect([panel.style.left, panel.style.top]).toEqual(['-250px', '100px'])
  })

  it('KEY-13: a new vote opens in the usual place again', () => {
    setState({ rollbackVote: vote({ kind: 'undo', steps: 2, startedAt: 1000 }) })
    render(<RollbackVoteDialog />)
    const panel = placePanel()
    fireEvent(title(), pointer('pointerdown', 500, 40))
    fireEvent(panel, pointer('pointermove', 400, 140))
    fireEvent(panel, pointer('pointerup', 400, 140))
    expect(panel.style.left).toBe('-100px')

    act(() => setState({ rollbackVote: vote({ kind: 'undo', steps: 4, startedAt: 2000 }) }))
    const next = screen.getByTestId('rollback-vote')
    expect([next.style.left, next.style.top]).toEqual(['', ''])
  })
})
