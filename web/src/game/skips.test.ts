import { describe, expect, it } from 'vitest'
import { SKIPS, skipPassesMyMainPhase, type SkipDef } from './skips'
import { makeCard, makeGameView, makePlayer } from '../__fixtures__/gameViews'
import type { GameView } from '../net/types'

const land = { basicManaAbilities: [], basicPlayAbilities: [{ id: 'p', value: 'Play Forest' }], basicCastAbilities: [], other: [] }
const mana = { basicManaAbilities: [{ id: 'm', value: '{T}: Add {G}.' }], basicPlayAbilities: [], basicCastAbilities: [], other: [] }

const skip = (key: SkipDef['key']): SkipDef => SKIPS.find((s) => s.key === key)!

/** My game at `step`: my turn unless `mine` is false, a card in hand unless `hand` is false. */
function gameAt(step: string, opts: { mine?: boolean; hand?: boolean; playable?: boolean; stack?: boolean } = {}): GameView {
  const { mine = true, hand = true, playable = false, stack = false } = opts
  return makeGameView({
    step,
    players: [
      makePlayer({ playerId: 'me', name: 'Me', controlled: true, isActive: mine, hasPriority: true }),
      makePlayer({ playerId: 'opp', name: 'Opp', isActive: !mine }),
    ],
    myPlayerId: 'me',
    activePlayerId: mine ? 'me' : 'opp',
    myHand: hand ? { h1: makeCard({ name: 'Double Vision', parentId: 'h1' }) } : {},
    stack: stack ? { s1: makeCard({ name: 'Shock', parentId: 's1' }) } : {},
    canPlayObjects: { objects: playable ? { forest: land } : { elves: mana } },
  })
}

describe('skipPassesMyMainPhase', () => {
  it('KEY-6: "Next turn" or "End step" in my upkeep or draw step, with a card in hand, asks first', () => {
    for (const step of ['UPKEEP', 'DRAW']) {
      for (const key of ['turn', 'endStep', 'myTurn', 'beforeMine'] as const) {
        expect(skipPassesMyMainPhase(gameAt(step), skip(key)), `${key} at ${step}`).toBe(true)
      }
    }
  })

  it('KEY-6: the skips that stop before my main phase or only resolve the stack never ask', () => {
    expect(skipPassesMyMainPhase(gameAt('UPKEEP'), skip('nextMain'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('UPKEEP', { stack: true }), skip('stack'))).toBe(false)
  })

  it('KEY-6: a skip on someone else\'s turn, or after my last main phase, never asks', () => {
    expect(skipPassesMyMainPhase(gameAt('UPKEEP', { mine: false }), skip('turn'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('PRECOMBAT_MAIN', { mine: false, playable: true }), skip('endStep'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('END_TURN'), skip('turn'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('CLEANUP'), skip('endStep'))).toBe(false)
  })

  it('KEY-6: before or between my main phases an empty hand with nothing to do now never asks', () => {
    expect(skipPassesMyMainPhase(gameAt('DRAW', { hand: false }), skip('turn'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('END_COMBAT', { hand: false }), skip('endStep'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('DRAW', { hand: false, playable: true }), skip('turn'))).toBe(true)
  })

  it('KEY-6: in my main phase with an empty stack it trusts the server\'s playable list, not the hand', () => {
    expect(skipPassesMyMainPhase(gameAt('PRECOMBAT_MAIN'), skip('turn'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('POSTCOMBAT_MAIN'), skip('endStep'))).toBe(false)
    expect(skipPassesMyMainPhase(gameAt('PRECOMBAT_MAIN', { playable: true }), skip('turn'))).toBe(true)
    // something on the stack: the list holds instant-speed plays only, so the hand counts again
    expect(skipPassesMyMainPhase(gameAt('PRECOMBAT_MAIN', { stack: true }), skip('turn'))).toBe(true)
  })

  it('KEY-6: combat steps still have my second main phase ahead', () => {
    for (const step of ['BEGIN_COMBAT', 'DECLARE_ATTACKERS', 'DECLARE_BLOCKERS', 'COMBAT_DAMAGE', 'END_COMBAT']) {
      expect(skipPassesMyMainPhase(gameAt(step), skip('endStep')), step).toBe(true)
    }
  })

  it('KEY-6: with no game or no player of mine it never asks', () => {
    expect(skipPassesMyMainPhase(null, skip('turn'))).toBe(false)
    const spectating = { ...gameAt('UPKEEP'), players: [makePlayer({ playerId: 'opp', name: 'Opp', isActive: true })] }
    expect(skipPassesMyMainPhase(spectating, skip('turn'))).toBe(false)
  })
})
