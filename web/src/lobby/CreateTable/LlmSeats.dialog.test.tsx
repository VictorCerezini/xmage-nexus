import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react'
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
  await waitFor(() => expect(screen.getByTestId('format-game-type')).toBeDefined(), { timeout: 5000, interval: 20 })
  fireEvent.change(screen.getByPlaceholderText(/Ej. Modern Casual Bo3|Modern Casual Bo3/), { target: { value: 'Model Duel' } })
  for (let i = 0; i < 6; i++) {
    if (screen.queryByTestId('seat-node-0')) break
    const next = screen.queryByRole('button', { name: /Siguiente|Next/ })
    if (!next) break
    fireEvent.click(next)
  }
  fireEvent.click(await screen.findByTestId('seat-node-1'))
  return screen.findByTestId('seat-type-0')
}

const seatTypes = (group: HTMLElement) => within(group).queryAllByRole('radio').map((r) => r.textContent)
const pickSeatType = (group: HTMLElement, name: RegExp) => fireEvent.click(within(group).getByRole('radio', { name }))

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
    expect(seatTypes(seatType)).not.toContain('ChatGPT')
    expect(screen.queryByText(/Modelos de lenguaje|Language models/)).toBeNull()
    expect(screen.queryByTestId('create-llm-refresh')).toBeNull()
  })

  it('creates the table with the chosen model, reasoning and deck', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValue(catalog)
    const seatType = await openSeats()
    await waitFor(() => expect(seatTypes(seatType)).toContain('ChatGPT'))
    expect(screen.getByTestId('create-llm-unavailable').textContent).toContain('claude_subscription (not signed in)')

    pickSeatType(seatType, /^ChatGPT$/)
    expect(within(seatType).getByRole('radio', { name: /^ChatGPT$/ }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByTestId('seat-node-1').textContent).toContain('ChatGPT · GPT B')
    const model = screen.getByTestId('seat-llm-model-0') as HTMLSelectElement
    const effort = () => screen.getByTestId('seat-llm-effort-0') as HTMLSelectElement
    expect(model.value).toBe('gpt-b')
    expect(effort().value).toBe('low')
    fireEvent.change(model, { target: { value: 'gpt-a' } })
    expect(effort().value).toBe('low')
    fireEvent.change(effort(), { target: { value: 'high' } })
    expect(screen.getByTestId('seat-node-1').textContent).toContain('ChatGPT · GPT A')

    for (let i = 0; i < 3 && !document.querySelector('.create-review'); i++) {
      const next = screen.queryByRole('button', { name: /Siguiente|Next/ })
      if (next) fireEvent.click(next)
    }
    expect(screen.getByText(/ChatGPT · GPT A · .* high/)).toBeTruthy()
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
    await waitFor(() => expect(seatTypes(seatType)).toContain('ChatGPT'))
    pickSeatType(seatType, /^ChatGPT$/)
    expect(screen.getByTestId('seat-llm-model-0')).toBeTruthy()
    pickSeatType(seatType, /Mad/)
    expect(screen.queryByTestId('seat-llm-model-0')).toBeNull()
    expect(within(seatType).getByRole('radio', { name: /^ChatGPT$/ }).getAttribute('aria-checked')).toBe('false')

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
    await waitFor(() => expect(seatTypes(seatType)).toContain('ChatGPT'))
    expect(cmds.getLlmProviders).toHaveBeenLastCalledWith(true)
    expect(screen.queryByTestId('create-llm-error')).toBeNull()
  })
})
