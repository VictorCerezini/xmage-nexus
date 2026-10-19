import * as cmds from '../net/commands'
import type { LlmSeatEnvelope, LlmSeatInfo } from '../net/types'
import { getState, setState } from './state'

const requestsByTable = new Map<string, cmds.LlmSeatRequest[]>()

/** The model seats a table was created with, kept so the owner can recreate the table. */
export function rememberLlmSeatRequests(tableId: string, requests: cmds.LlmSeatRequest[]): void {
  if (requests.length > 0) requestsByTable.set(tableId, requests)
  else requestsByTable.delete(tableId)
}

export function llmSeatRequestsFor(tableId: string): cmds.LlmSeatRequest[] | undefined {
  return requestsByTable.get(tableId)
}

function toInfo(raw: LlmSeatInfo): LlmSeatInfo {
  const { tableId, seat, username, provider, model, effort, status, reason } = raw
  return { tableId, seat, username, provider, model, effort, status, ...(reason ? { reason } : {}) }
}

/** Keeps the latest status per table and seat. */
export function upsertLlmSeats(current: LlmSeatInfo[], incoming: LlmSeatInfo[]): LlmSeatInfo[] {
  let next = current
  for (const raw of incoming) {
    const info = toInfo(raw)
    const idx = next.findIndex((s) => s.tableId === info.tableId && s.seat === info.seat)
    next = idx < 0 ? [...next, info] : next.map((s, i) => (i === idx ? info : s))
  }
  return next
}

export function handleLlmSeat(msg: LlmSeatEnvelope): void {
  setState({ llmSeats: upsertLlmSeats(getState().llmSeats, [msg]) })
}

/** Reads the table's model seats from the proxy, for a staging screen opened after a reload. */
export async function loadLlmSeats(tableId: string): Promise<void> {
  let seats: LlmSeatInfo[] = []
  try {
    seats = await cmds.getLlmSeats(tableId)
  } catch {
    return
  }
  if (seats.length > 0) setState({ llmSeats: upsertLlmSeats(getState().llmSeats, seats) })
}

/**
 * Asks the proxy for its model catalog. A catalog that is enabled is kept until a
 * refresh; a disabled one is asked again, because the proxy may have been
 * restarted with `--llm` since.
 */
export async function loadLlmCatalog(opts: { refresh?: boolean } = {}): Promise<void> {
  const before = getState()
  if (before.llmCatalogLoading) return
  if (!opts.refresh && before.llmCatalog?.enabled) return
  setState({ llmCatalogLoading: true })
  try {
    const { error, ...catalog } = await cmds.getLlmProviders(!!opts.refresh)
    const previous = getState().llmCatalog
    if (error) setState({ llmCatalogError: error, llmCatalog: previous?.enabled ? previous : catalog })
    else setState({ llmCatalogError: null, llmCatalog: catalog })
  } catch {
    if (!getState().llmCatalog?.enabled) setState({ llmCatalog: { enabled: false } })
  } finally {
    setState({ llmCatalogLoading: false })
  }
}

/**
 * Seat numbers for the model seats of a table recreated with `playerTypes`. The
 * owner rejoins first and takes the first HUMAN seat, so a model keeps the seat
 * it sits in now, else the one it first asked for, else the last HUMAN seat left.
 */
export function llmSeatsForRecreate(
  requests: cmds.LlmSeatRequest[],
  infos: LlmSeatInfo[],
  seats: { playerName?: string | null }[],
  playerTypes: string[],
): cmds.LlmSeatRequest[] {
  const free = new Set(playerTypes.flatMap((type, i) => (type === 'HUMAN' ? [i + 1] : [])).slice(1))
  const take = (seat: number) => (free.delete(seat) ? seat : undefined)
  const sittingAt = (r: cmds.LlmSeatRequest) => {
    const name = infos.find((s) => s.seat === r.seat)?.username.toLowerCase()
    return name ? seats.findIndex((s) => s.playerName?.toLowerCase() === name) + 1 : 0
  }
  const kept = requests.map((r) => take(sittingAt(r)))
  return requests.flatMap((r, i) => {
    const seat = kept[i] ?? take(r.seat) ?? take(Math.max(0, ...free))
    return seat ? [{ ...r, seat }] : []
  })
}

/**
 * The model seat shown at seat `idx` (0-based) of a table. An occupied seat
 * matches by login name, since the owner may have swapped seats; an empty seat
 * matches the seat number the model was asked to take.
 */
export function llmSeatForSeat(
  infos: LlmSeatInfo[],
  tableId: string | undefined,
  seats: { playerName?: string | null }[],
  idx: number,
): LlmSeatInfo | undefined {
  if (!tableId) return undefined
  const mine = infos.filter((s) => s.tableId === tableId)
  if (mine.length === 0) return undefined
  const name = seats[idx]?.playerName?.toLowerCase()
  if (name) return mine.find((s) => s.username.toLowerCase() === name)
  const seated = new Set(seats.map((s) => s.playerName?.toLowerCase()).filter(Boolean))
  return mine.find((s) => s.seat === idx + 1 && !seated.has(s.username.toLowerCase()))
}
