import { act, cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SpectatorStagingScreen from './SpectatorStagingScreen'
import { setState } from '../state/store'
import * as cmds from '../net/commands'
import type { LlmCatalog, LlmSeatInfo, TableView } from '../net/types'

vi.mock('../net/commands', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../net/commands')>()),
  getLlmSeats: vi.fn(async () => []),
}))

vi.mock('./ChatBox', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./ChatBox')>()),
  default: () => <div data-testid="chat-box-stub">Chat Stub</div>,
}))

const table = (secondSeat: string): TableView => ({
  tableId: 'table-llm-1',
  tableName: 'Model Duel',
  controllerName: 'Alice',
  gameType: 'Two Player Duel',
  deckType: 'Constructed - Modern',
  additionalInfoShort: '',
  additionalInfoFull: '',
  createTime: Date.now(),
  tableState: secondSeat ? 'READY_TO_START' : 'WAITING',
  skillLevel: 'CASUAL',
  tableStateText: '',
  seatsInfo: secondSeat ? '2/2' : '1/2',
  isTournament: false,
  seats: [
    { playerName: 'Alice', seatIndex: 0, playerType: 'HUMAN' },
    { playerName: secondSeat, seatIndex: 1, playerType: 'HUMAN' },
  ],
  games: [],
  quitRatio: '0%',
  minimumRating: '0',
  limited: false,
  rated: false,
  passworded: false,
  spectatorsAllowed: true,
})

const catalog: LlmCatalog = {
  enabled: true,
  providers: [{
    id: 'chatgpt_subscription', playerType: 'ChatGPT subscription', playerName: 'ChatGPT', auth: 'login',
    models: [{ id: 'gpt-6-luna', name: 'GPT-6 Luna', efforts: ['low', 'high'], defaultEffort: 'low', isDefault: true }],
  }],
  unavailable: [],
}

const info = (over: Partial<LlmSeatInfo> = {}): LlmSeatInfo => ({
  tableId: 'table-llm-1', seat: 2, username: 'ChatGPT 2', provider: 'chatgpt_subscription',
  model: 'gpt-6-luna', effort: 'high', status: 'starting', ...over,
})

function showAsOwner(secondSeat: string, seats: LlmSeatInfo[]) {
  setState({
    conn: { username: 'Alice' } as never,
    stagingTableId: 'table-llm-1',
    lobby: { type: 'lobby', tables: [table(secondSeat)] } as never,
    llmCatalog: catalog,
    llmSeats: seats,
  })
  return render(<SpectatorStagingScreen mode="player" />)
}

afterEach(() => {
  cleanup()
  vi.mocked(cmds.getLlmSeats).mockClear()
  setState({ conn: null, stagingTableId: null, lobby: null, phase: 'idle', chatMessages: [], llmSeats: [], llmCatalog: null })
})

describe('model seats on the staging screen', () => {
  it('shows the joining model on its empty seat and asks the proxy for the table seats', () => {
    const { getByTestId, getAllByText } = showAsOwner('', [info()])
    const badge = getByTestId('staging-llm-1')
    expect(badge.getAttribute('data-status')).toBe('starting')
    expect(badge.textContent).toContain('ChatGPT 2')
    expect(getAllByText('ChatGPT 2').length).toBeGreaterThan(0)
    expect(cmds.getLlmSeats).toHaveBeenCalledWith('table-llm-1')
  })

  it('shows the model and reasoning once the model is seated', () => {
    const { getByTestId, queryByTestId } = showAsOwner('ChatGPT 2', [info({ status: 'joined' })])
    const badge = getByTestId('staging-llm-1')
    expect(badge.getAttribute('data-status')).toBe('joined')
    expect(badge.textContent).toBe('GPT-6 Luna · high')
    expect(queryByTestId('staging-llm-0')).toBeNull()
  })

  it('updates the badge when a status event arrives, and keeps the failure reason', () => {
    const { getByTestId } = showAsOwner('', [info()])
    act(() => setState({ llmSeats: [info({ status: 'failed', reason: 'Codex is not signed in' })] }))
    const badge = getByTestId('staging-llm-1')
    expect(badge.getAttribute('data-status')).toBe('failed')
    expect(badge.textContent).toContain('ChatGPT 2')
    expect(badge.getAttribute('title')).toContain('Codex is not signed in')
  })

  it('ignores model seats of other tables', () => {
    const { queryByTestId } = showAsOwner('', [info({ tableId: 'other' })])
    expect(queryByTestId('staging-llm-1')).toBeNull()
  })
})
