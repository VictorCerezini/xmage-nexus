import type { BoardLayoutPref } from '../state/persistence'
import { MAX_BOARD_PLAYERS } from './boardShared'

export function resolveBoardLayout(
  layout: BoardLayoutPref,
  manual: boolean,
  opponentCount: number,
): BoardLayoutPref {
  const multiplayer = opponentCount >= 2
  if (layout === 'arena') return multiplayer ? 'arena' : 'standard'
  if (layout === 'pod') return 'pod'
  if (multiplayer && !manual) return 'pod'
  return 'standard'
}

/**
 * FFA de 5+ jugadores solo es posible en standard: pod/arena recortan a
 * MAX_BOARD_PLAYERS sin switcher (pod) y el standard muestra a todos los
 * rivales de uno en uno con el OpponentSwitcherBar (GameBoard no recorta).
 */
export function effectiveBoardLayout(
  layout: BoardLayoutPref,
  manual: boolean,
  opponentCount: number,
  totalPlayers: number,
): BoardLayoutPref {
  if (totalPlayers > MAX_BOARD_PLAYERS) return 'standard'
  return resolveBoardLayout(layout, manual, opponentCount)
}

/** The all-boards view applies only where the other layouts cannot show everyone. */
export function showsAllBoards(allBoardsView: boolean, totalPlayers: number): boolean {
  return allBoardsView && totalPlayers > MAX_BOARD_PLAYERS
}

/**
 * Seats for the all-boards view, clockwise in turn order from the bottom-left corner, which is
 * mine: the next players fill the top row left to right and the rest come back along the bottom.
 */
export function allBoardsRows<P extends { playerId: string }>(players: readonly P[], myId?: string): { top: P[]; bottom: P[] } {
  const start = Math.max(0, players.findIndex((p) => p.playerId === myId))
  const seats = [...players.slice(start), ...players.slice(0, start)]
  const [first, ...rest] = seats
  if (!first) return { top: [], bottom: [] }
  const columns = Math.ceil(seats.length / 2)
  return { top: rest.slice(0, columns), bottom: [first, ...rest.slice(columns).reverse()] }
}
