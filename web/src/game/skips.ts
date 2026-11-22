import type { GameView } from '../net/types'
import { meaningfulPlayables } from './smartStops'

export interface SkipDef {
  key: 'turn' | 'endStep' | 'nextMain' | 'myTurn' | 'stack' | 'beforeMine'
  action: string
  shortcut: 'F4' | 'F5' | 'F7' | 'F9' | 'F10' | 'F11'
  flag: 'passedTurn' | 'passedUntilEndOfTurn' | 'passedUntilNextMain' | 'passedAllTurns' | 'passedUntilStackResolved' | 'passedUntilEndStepBeforeMyTurn'
  labelKey: 'skip_turn' | 'skip_end_step' | 'skip_next_main' | 'skip_my_turn' | 'skip_stack' | 'skip_before_mine'
}

export const CANCEL_SKIP_ACTION = 'PASS_PRIORITY_CANCEL_ALL_ACTIONS'
export const CANCEL_SKIP_SHORTCUT = 'F3'

export const SKIPS: SkipDef[] = [
  { key: 'turn', action: 'PASS_PRIORITY_UNTIL_NEXT_TURN', shortcut: 'F4', flag: 'passedTurn', labelKey: 'skip_turn' },
  { key: 'endStep', action: 'PASS_PRIORITY_UNTIL_TURN_END_STEP', shortcut: 'F5', flag: 'passedUntilEndOfTurn', labelKey: 'skip_end_step' },
  { key: 'nextMain', action: 'PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE', shortcut: 'F7', flag: 'passedUntilNextMain', labelKey: 'skip_next_main' },
  { key: 'myTurn', action: 'PASS_PRIORITY_UNTIL_MY_NEXT_TURN', shortcut: 'F9', flag: 'passedAllTurns', labelKey: 'skip_my_turn' },
  { key: 'stack', action: 'PASS_PRIORITY_UNTIL_STACK_RESOLVED', shortcut: 'F10', flag: 'passedUntilStackResolved', labelKey: 'skip_stack' },
  { key: 'beforeMine', action: 'PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN', shortcut: 'F11', flag: 'passedUntilEndStepBeforeMyTurn', labelKey: 'skip_before_mine' },
]

export function skipForShortcut(shortcut: string): SkipDef | undefined {
  return SKIPS.find((s) => s.shortcut === shortcut)
}

export function activeSkipOf(me: Partial<Record<SkipDef['flag'], unknown>> | null | undefined): SkipDef | null {
  if (!me) return null
  return SKIPS.find((s) => me[s.flag] === true) ?? null
}

/** The skips the server keeps until this turn is over, so on my own turn they pass my main phases too. */
const PAST_MY_TURN: ReadonlySet<SkipDef['key']> = new Set(['turn', 'endStep', 'myTurn', 'beforeMine'])
const MAIN_STEPS: ReadonlySet<string> = new Set(['PRECOMBAT_MAIN', 'POSTCOMBAT_MAIN'])
/** The steps of my turn that still have a main phase ahead of them (or are one). */
const BEFORE_MY_LAST_MAIN: ReadonlySet<string> = new Set([
  'UPKEEP', 'DRAW', 'PRECOMBAT_MAIN', 'BEGIN_COMBAT', 'DECLARE_ATTACKERS', 'DECLARE_BLOCKERS',
  'FIRST_COMBAT_DAMAGE', 'COMBAT_DAMAGE', 'END_COMBAT', 'POSTCOMBAT_MAIN',
])

/**
 * Whether this skip would pass a main phase of my own turn while I may still have a play (KEY-6),
 * so the page asks first. In a main phase with an empty stack the server's list of playable cards
 * says it exactly; anywhere else it lists only what I can do right now (no land, no sorcery), so a
 * card in hand counts as a possible play.
 */
export function skipPassesMyMainPhase(game: GameView | null | undefined, skip: SkipDef): boolean {
  if (!game || !PAST_MY_TURN.has(skip.key)) return false
  const me = game.players?.find((p) => p.controlled)
  if (!me?.isActive || !game.step || !BEFORE_MY_LAST_MAIN.has(game.step)) return false
  if (meaningfulPlayables(game).length > 0) return true
  const exact = MAIN_STEPS.has(game.step) && Object.keys(game.stack ?? {}).length === 0
  return !exact && Object.keys(game.myHand ?? {}).length > 0
}
