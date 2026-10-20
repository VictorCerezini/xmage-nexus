import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as cmds from '../net/commands'
import type { LlmSeatInfo } from '../net/types'
import { attachGateway, detachGateway } from './gateway'
import { llmSeatForSeat, llmSeatsForRecreate, loadLlmCatalog, loadLlmSeats, upsertLlmSeats } from './llmSeats'
import { getState, setState } from './state'

vi.mock('../net/commands', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../net/commands')>()
  return { ...actual, getLlmProviders: vi.fn(), getLlmSeats: vi.fn() }
})

const seat = (over: Partial<LlmSeatInfo> = {}): LlmSeatInfo => ({
  tableId: 't1', seat: 2, username: 'ChatGPT 2', provider: 'chatgpt_subscription',
  model: 'gpt-6-luna', effort: 'low', status: 'starting', ...over,
})

const catalog = {
  enabled: true,
  providers: [{ id: 'chatgpt_subscription', playerType: 'ChatGPT subscription', playerName: 'ChatGPT', auth: 'login', models: [] }],
  unavailable: [],
}

beforeEach(() => {
  setState({ llmSeats: [], llmCatalog: null, llmCatalogLoading: false, llmCatalogError: null, error: null })
  vi.mocked(cmds.getLlmProviders).mockReset()
  vi.mocked(cmds.getLlmSeats).mockReset()
})

afterEach(() => {
  detachGateway()
})

describe('model seat status', () => {
  it('keeps one entry per table and seat, the latest one', () => {
    let seats = upsertLlmSeats([], [seat()])
    seats = upsertLlmSeats(seats, [seat({ status: 'joined' }), seat({ seat: 3, username: 'Claude 3' }), seat({ tableId: 't2' })])
    expect(seats.map((s) => [s.tableId, s.seat, s.status])).toEqual([['t1', 2, 'joined'], ['t1', 3, 'starting'], ['t2', 2, 'starting']])
  })

  it('drops the envelope fields from a stored event', () => {
    const [stored] = upsertLlmSeats([], [{ ...seat(), type: 'llmSeat', seq: 7 } as LlmSeatInfo])
    expect(stored).toEqual(seat())
  })

  it('applies llmSeat frames that reach the gateway', () => {
    const g = { events: {} as { onMessage?: (m: unknown) => void }, close: vi.fn(), resumeToken: () => null, isOpen: true }
    attachGateway(g as never)
    g.events.onMessage?.({ type: 'llmSeat', seq: 3, ...seat() })
    g.events.onMessage?.({ type: 'llmSeat', seq: 4, ...seat({ status: 'failed', reason: 'gateway closed' }) })
    expect(getState().llmSeats).toEqual([seat({ status: 'failed', reason: 'gateway closed' })])
  })

  it('shows no error banner when an older proxy does not know the model seat actions', () => {
    const g = { events: {} as { onMessage?: (m: unknown) => void }, close: vi.fn(), resumeToken: () => null, isOpen: true }
    attachGateway(g as never)
    g.events.onMessage?.({ type: 'result', action: 'getLlmProviders', ok: false, error: 'Unknown action: getLlmProviders', errorCode: 'UNKNOWN_ACTION' })
    g.events.onMessage?.({ type: 'result', action: 'getLlmSeats', ok: false, error: 'Unknown action: getLlmSeats', errorCode: 'UNKNOWN_ACTION' })
    expect(getState().error).toBeNull()
  })

  it('seeds a table from getLlmSeats and survives a failed call', async () => {
    vi.mocked(cmds.getLlmSeats).mockResolvedValueOnce([seat({ status: 'joined' })])
    vi.mocked(cmds.getLlmProviders).mockResolvedValue(catalog)
    await loadLlmSeats('t1')
    expect(getState().llmSeats).toEqual([seat({ status: 'joined' })])
    expect(getState().llmCatalog).toEqual(catalog)
    vi.mocked(cmds.getLlmSeats).mockRejectedValueOnce(new Error('gateway not initialized'))
    await expect(loadLlmSeats('t1')).resolves.toBeUndefined()
    expect(getState().llmSeats).toEqual([seat({ status: 'joined' })])
  })

  it('asks for the catalog only when the table has model seats', async () => {
    vi.mocked(cmds.getLlmSeats).mockResolvedValueOnce([])
    await loadLlmSeats('t1')
    expect(cmds.getLlmProviders).not.toHaveBeenCalled()
  })
})

describe('model catalog', () => {
  it('keeps an enabled catalog until a refresh', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValue(catalog)
    await loadLlmCatalog()
    await loadLlmCatalog()
    expect(cmds.getLlmProviders).toHaveBeenCalledTimes(1)
    expect(getState().llmCatalog).toEqual(catalog)
    await loadLlmCatalog({ refresh: true })
    expect(cmds.getLlmProviders).toHaveBeenLastCalledWith(true)
    expect(getState().llmCatalogLoading).toBe(false)
  })

  it('asks again while the proxy reports model seats off', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValueOnce({ enabled: false }).mockResolvedValueOnce(catalog)
    await loadLlmCatalog()
    expect(getState().llmCatalog).toEqual({ enabled: false })
    await loadLlmCatalog()
    expect(getState().llmCatalog).toEqual(catalog)
  })

  it('keeps the previous catalog when a refresh fails, and shows why', async () => {
    vi.mocked(cmds.getLlmProviders).mockResolvedValueOnce(catalog).mockResolvedValueOnce({ enabled: false, error: 'llm.catalog exited 1' })
    await loadLlmCatalog()
    await loadLlmCatalog({ refresh: true })
    expect(getState().llmCatalog).toEqual(catalog)
    expect(getState().llmCatalogError).toBe('llm.catalog exited 1')
  })

  it('treats a missing connection as model seats off', async () => {
    vi.mocked(cmds.getLlmProviders).mockRejectedValueOnce(new Error('gateway not initialized'))
    await loadLlmCatalog()
    expect(getState().llmCatalog).toEqual({ enabled: false })
    expect(getState().llmCatalogLoading).toBe(false)
  })
})

describe('llmSeatsForRecreate', () => {
  const deck = { name: 'd', cards: [], sideboard: [] } as never
  const req = (seat: number) => ({ seat, provider: 'chatgpt_subscription', model: 'gpt-6-luna', effort: 'low', deck })
  const infos = [seat({ seat: 2, username: 'ChatGPT 2' }), seat({ seat: 3, username: 'Claude 3' })]

  it('keeps the seats the models sit in now', () => {
    const seats = [{ playerName: 'owner' }, { playerName: 'Claude 3' }, { playerName: 'ChatGPT 2' }]
    expect(llmSeatsForRecreate([req(2), req(3)], infos, seats, ['HUMAN', 'HUMAN', 'HUMAN']).map((r) => r.seat)).toEqual([3, 2])
  })

  it('never gives a model the seat the owner rejoins', () => {
    const seats = [{ playerName: 'ChatGPT 2' }, { playerName: 'owner' }, { playerName: '' }]
    expect(llmSeatsForRecreate([req(2)], infos, seats, ['HUMAN', 'HUMAN', 'HUMAN']).map((r) => r.seat)).toEqual([2])
  })

  it('moves a model off a seat that is no longer HUMAN', () => {
    const seats = [{ playerName: 'owner' }, { playerName: 'Computer' }, { playerName: '' }]
    expect(llmSeatsForRecreate([req(2)], infos, seats, ['HUMAN', 'COMPUTER_MAD', 'HUMAN']).map((r) => r.seat)).toEqual([3])
    expect(llmSeatsForRecreate([req(2)], infos, seats, ['HUMAN', 'COMPUTER_MAD'])).toEqual([])
  })
})

describe('llmSeatForSeat', () => {
  const infos = [seat(), seat({ seat: 3, username: 'Claude 3', status: 'joined' }), seat({ tableId: 't2' })]

  it('matches an empty seat by its seat number', () => {
    const seats = [{ playerName: 'owner' }, { playerName: '' }, { playerName: '' }]
    expect(llmSeatForSeat(infos, 't1', seats, 1)?.username).toBe('ChatGPT 2')
    expect(llmSeatForSeat(infos, 't1', seats, 0)).toBeUndefined()
    expect(llmSeatForSeat(infos, undefined, seats, 1)).toBeUndefined()
  })

  it('follows a seated model by name after a seat swap', () => {
    const seats = [{ playerName: 'owner' }, { playerName: 'Claude 3' }, { playerName: '' }]
    expect(llmSeatForSeat(infos, 't1', seats, 1)?.username).toBe('Claude 3')
    expect(llmSeatForSeat(infos, 't1', seats, 2)).toBeUndefined()
  })
})
