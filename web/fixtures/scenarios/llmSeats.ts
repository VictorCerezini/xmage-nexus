import { makeBaseScenario, makeTable, type FakeConn } from '../fake'
import { TABLE } from '../table-names'
import type { LlmCatalog, LlmSeatInfo, LlmSeatStatus, SeatView } from '../../src/net/types'

/**
 * FixtureServer scenario for llm-seats.spec.ts: a proxy started with `--llm`.
 * `getLlmProviders` answers a fixed catalog, and each model seat sent with
 * `createTable` reports `starting`, then takes its seat and reports `joined`.
 * With `fail`, every model seat reports `failed` with a reason instead.
 */

export const LLM_TABLE_ID = 'table-llm-seats-1'
export const LLM_FAIL_REASON = 'Codex is not signed in on this computer'

export const LLM_CATALOG: LlmCatalog = {
  enabled: true,
  providers: [
    {
      id: 'chatgpt_subscription',
      playerType: 'ChatGPT subscription',
      playerName: 'ChatGPT',
      auth: 'existing Codex ChatGPT login',
      models: [
        { id: 'gpt-6-luna', name: 'GPT-6 Luna', efforts: ['low', 'medium', 'high'], defaultEffort: 'medium', isDefault: true },
        { id: 'gpt-6-astra', name: 'GPT-6 Astra', efforts: ['low', 'high', 'ultra'], defaultEffort: 'high', isDefault: false },
      ],
    },
    {
      id: 'openrouter_free',
      playerType: 'OpenRouter free',
      playerName: 'OpenRouter',
      auth: 'OPENROUTER_API_KEY',
      freeRequestsToday: { used: 8, limit: 50, remaining: 42 },
      models: [{ id: 'free/model-a', name: 'Free Model A', efforts: ['default'], defaultEffort: 'default', isDefault: true }],
    },
  ],
  unavailable: [{ id: 'claude_subscription', reason: 'Claude Code is not signed in' }],
}

export interface LlmSeatsScenarioOptions {
  fail?: boolean
  joinDelayMs?: number
}

interface RequestedSeat {
  seat?: number
  provider?: string
  model?: string
  effort?: string
}

export function llmSeatsScenario(opts: LlmSeatsScenarioOptions = {}): ReturnType<typeof makeBaseScenario> {
  const seats: SeatView[] = [
    { playerName: '', seatIndex: 0, playerType: 'HUMAN' },
    { playerName: '', seatIndex: 1, playerType: 'HUMAN' },
  ]
  const states: LlmSeatInfo[] = []
  const timers: ReturnType<typeof setTimeout>[] = []
  let seq = 0
  const table = makeTable({ tableId: LLM_TABLE_ID, tableName: TABLE.llmSeats, gameId: 'game-llm-seats-1', seats })

  const report = (conn: FakeConn, info: LlmSeatInfo, status: LlmSeatStatus, reason?: string) => {
    const next: LlmSeatInfo = { ...info, status, ...(reason ? { reason } : {}) }
    const i = states.findIndex((s) => s.tableId === next.tableId && s.seat === next.seat)
    if (i < 0) states.push(next)
    else states[i] = next
    if (conn.isOpen()) conn.raw({ type: 'llmSeat', seq: ++seq, ...next })
  }

  const startSeats = (conn: FakeConn, requested: RequestedSeat[]) => {
    for (const r of requested) {
      const provider = LLM_CATALOG.providers?.find((p) => p.id === r.provider)
      const seat = Number(r.seat)
      if (!provider || !Number.isInteger(seat) || seat < 1 || seat > seats.length) continue
      const model = provider.models.find((m) => m.id === r.model) ?? provider.models[0]
      const info: LlmSeatInfo = {
        tableId: LLM_TABLE_ID,
        seat,
        username: `${provider.playerName} ${seat}`,
        provider: provider.id,
        model: model.id,
        effort: r.effort || model.defaultEffort,
        status: 'starting',
      }
      timers.push(setTimeout(() => report(conn, info, 'starting'), 50))
      timers.push(setTimeout(() => {
        if (opts.fail) {
          report(conn, info, 'failed', LLM_FAIL_REASON)
          return
        }
        seats[seat - 1].playerName = info.username
        if (conn.isOpen()) conn.lobby([table])
        report(conn, info, 'joined')
      }, opts.joinDelayMs ?? 600))
    }
  }

  const base = makeBaseScenario({
    tableId: LLM_TABLE_ID,
    tableName: TABLE.llmSeats,
    gameId: 'game-llm-seats-1',
    seats,
    onRequest(conn, action, args, requestId) {
      switch (action) {
        case 'getLlmProviders':
          conn.ok(requestId, action, LLM_CATALOG)
          return true
        case 'getLlmSeats': {
          const tableId = typeof args.tableId === 'string' ? args.tableId : undefined
          conn.ok(requestId, action, states.filter((s) => !tableId || s.tableId === tableId))
          return true
        }
        case 'createTable': {
          const requested = Array.isArray(args.llmSeats) ? (args.llmSeats as RequestedSeat[]) : []
          startSeats(conn, requested)
          return false
        }
        case 'joinTable':
          if (args.playerType === 'HUMAN' || !args.playerType) {
            const free = seats.find((s) => !s.playerName && s.playerType === 'HUMAN')
            if (free) free.playerName = conn.username || 'e2e'
          }
          return false
        default:
          return false
      }
    },
  })

  return {
    ...base,
    onStart(conn) {
      const cleanup = base.onStart?.(conn)
      return () => {
        for (const t of timers) clearTimeout(t)
        if (typeof cleanup === 'function') cleanup()
      }
    },
  }
}
