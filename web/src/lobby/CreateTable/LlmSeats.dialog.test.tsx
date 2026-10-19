import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import CreateTableDialog from '../CreateTableDialog'
import { STORAGE_KEY } from './constants'
import * as cmds from '../../net/commands'
import type { LlmCatalog } from '../../net/types'
import { setState } from '../../state/state'
import { llmSeatRequestsFor } from '../../state/llmSeats'

vi.mock('../../net/commands', () => ({
  getGameTypes: vi.fn().mockResolvedValue([{ name: 'Two Player Duel', minPlayers: 2, maxPlayers: 2 }]),
  getDeckTypes: vi.fn().mockResolvedValue(['Constructed - Modern', 'Limited']),
  getPlayerTypes: vi.fn().mockResolvedValue(['COMPUTER_MAD', 'COMPUTER_DRAFT']),
  getTournamentTypes: vi.fn().mockResolvedValue([]),
  getDraftCubes: vi.fn().mockResolvedValue([]),
  getExpansionsWithBoosters: vi.fn().mockResolvedValue([]),
  getLlmProviders: vi.fn(),
  getLlmSeats: vi.fn().mockResolvedValue([]),
  createTable: vi.fn().mockResolvedValue({ ok: true, data: { tableId: 'table-llm' } }),
  joinTable: vi.fn().mockResolvedValue({ ok: true }),
  removeTable: vi.fn().mockResolvedValue({ ok: true }),
  createTournamentTable: vi.fn().mockResolvedValue({ ok: true, data: { tableId: 'table-t1' } }),
  joinTournamentTable: vi.fn().mockResolvedValue({ ok: true }),
  validateDeck: vi.fn().mockResolvedValue({ ok: true }),
}))

const catalog: LlmCatalog = {
  enabled: true,
  providers: [{
    id: 'chatgpt_subscription',
    playerType: 'ChatGPT subscription',
    playerName: 'ChatGPT',
    auth: 'existing Codex ChatGPT login',
    models: [
      { id: 'gpt-a', name: 'GPT A', efforts: ['low', 'medium', 'high'], defaultEffort: 'medium', isDefault: false },
      { id: 'gpt-b', name: 'GPT B', efforts: ['low', 'xhigh'], defaultEffort: 'low', isDefault: true },
    ],
  }],
  unavailable: [{ id: 'claude_subscription', reason: 'not signed in' }],
}

async function openSeats() {
  render(<CreateTableDialog onClose={vi.fn()} />)
  await waitFor(() => {
    const gameType = screen.getAllByRole('combobox')[0] as HTMLSelectElement
    expect(gameType.options.length).toBeLessThan(10)
  }, { timeout: 5000, interval: 20 })
  fireEvent.change(screen.getByPlaceholderText(/Ej. Modern Casual Bo3|Modern Casual Bo3/), { target: { value: 'Model Duel' } })
  for (let i = 0; i < 3; i++) {
    const next = screen.queryByRole('button', { name: /Siguiente|Next/ })
    if (next) fireEvent.click(next)
  }
  return (await screen.findByTestId('seat-type-0')) as HTMLSelectElement
}

const llmOptions = (select: HTMLSelectElement) =>
  Array.from(select.options).filter((o) => o.value.startsWith('llm:')).map((o) => [o.value, o.textContent])

describe('model seats in the create table dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setState({ llmCatalog: null, llmCatalogLoading: false, llmCatalogError: null, llmSeats: [] })
  })

  afterEach(() => {
    cleanup()
    localStorage?.removeItem(STORAGE_KEY)
  })

  it('hides model seats when the proxy runs without them', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValue({ enabled: false })
    const seatType = await openSeats()
    await waitFor(() => expect(cmds.getLlmProviders).toHaveBeenCalled())
    expect(llmOptions(seatType)).toEqual([])
    expect(screen.queryByTestId('create-llm-refresh')).toBeNull()
  })

  it('creates the table with the chosen model, reasoning and deck', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValue(catalog)
    const seatType = await openSeats()
    await waitFor(() => expect(llmOptions(seatType)).toEqual([['llm:chatgpt_subscription', 'ChatGPT']]))
    expect(screen.getByTestId('create-llm-unavailable').textContent).toContain('claude_subscription (not signed in)')

    fireEvent.change(seatType, { target: { value: 'llm:chatgpt_subscription' } })
    const model = screen.getByTestId('seat-llm-model-0') as HTMLSelectElement
    const effort = () => screen.getByTestId('seat-llm-effort-0') as HTMLSelectElement
    expect(model.value).toBe('gpt-b')
    expect(effort().value).toBe('low')
    fireEvent.change(model, { target: { value: 'gpt-a' } })
    expect(effort().value).toBe('low')
    fireEvent.change(effort(), { target: { value: 'high' } })

    const next = screen.queryByRole('button', { name: /Siguiente|Next/ })
    if (next) fireEvent.click(next)
    fireEvent.click(await screen.findByRole('button', { name: /Crear Mesa|Create Table/ }))

    await waitFor(() => expect(cmds.createTable).toHaveBeenCalled())
    const args = vi.mocked(cmds.createTable).mock.calls[0][0]
    expect(args.playerTypes).toEqual(['HUMAN', 'HUMAN'])
    expect(args.llmSeats).toEqual([
      { seat: 2, provider: 'chatgpt_subscription', model: 'gpt-a', effort: 'high', deck: expect.objectContaining({ cards: expect.any(Array) }) },
    ])
    await waitFor(() => expect(cmds.joinTable).toHaveBeenCalledTimes(1))
    expect(cmds.joinTable).toHaveBeenCalledWith(expect.objectContaining({ tableId: 'table-llm', playerType: 'HUMAN' }))
    expect(llmSeatRequestsFor('table-llm')).toEqual(args.llmSeats)
  })

  it('switching a model seat back to a bot drops it from the request', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValue(catalog)
    const seatType = await openSeats()
    await waitFor(() => expect(llmOptions(seatType).length).toBe(1))
    fireEvent.change(seatType, { target: { value: 'llm:chatgpt_subscription' } })
    fireEvent.change(seatType, { target: { value: 'COMPUTER_MAD' } })
    expect(screen.queryByTestId('seat-llm-model-0')).toBeNull()

    const next = screen.queryByRole('button', { name: /Siguiente|Next/ })
    if (next) fireEvent.click(next)
    fireEvent.click(await screen.findByRole('button', { name: /Crear Mesa|Create Table/ }))
    await waitFor(() => expect(cmds.createTable).toHaveBeenCalled())
    const args = vi.mocked(cmds.createTable).mock.calls[0][0]
    expect(args.playerTypes).toEqual(['HUMAN', 'COMPUTER_MAD'])
    expect(args.llmSeats).toBeUndefined()
  })

  it('shows why the catalog failed and refreshes on request', async () => {
    vi.mocked(cmds.getLlmProviders)
      .mockResolvedValueOnce({ enabled: false, error: 'llm.catalog timed out' })
      .mockResolvedValueOnce(catalog)
    const seatType = await openSeats()
    expect((await screen.findByTestId('create-llm-error')).textContent).toContain('llm.catalog timed out')
    fireEvent.click(screen.getByTestId('create-llm-refresh'))
    await waitFor(() => expect(llmOptions(seatType).length).toBe(1))
    expect(cmds.getLlmProviders).toHaveBeenLastCalledWith(true)
    expect(screen.queryByTestId('create-llm-error')).toBeNull()
  })
})
