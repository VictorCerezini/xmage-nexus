import { beforeEach, describe, expect, it } from 'vitest'
import { allBoardsRows, effectiveBoardLayout, resolveBoardLayout, showsAllBoards } from './boardLayout'
import { getState, reset, setSetting } from '../state/store'
import { loadAppearanceSettings } from '../state/persistence'

describe('resolveBoardLayout', () => {
  it('mantiene standard en 1v1 sin elección manual', () => {
    expect(resolveBoardLayout('standard', false, 1)).toBe('standard')
  })

  it('auto-pod con 3+ jugadores salvo override manual', () => {
    expect(resolveBoardLayout('standard', false, 2)).toBe('pod')
    expect(resolveBoardLayout('standard', false, 3)).toBe('pod')
  })

  it('respeta el standard manual en multiplayer', () => {
    expect(resolveBoardLayout('standard', true, 2)).toBe('standard')
    expect(resolveBoardLayout('standard', true, 3)).toBe('standard')
  })

  it('pod explícito siempre es pod', () => {
    expect(resolveBoardLayout('pod', false, 1)).toBe('pod')
    expect(resolveBoardLayout('pod', false, 2)).toBe('pod')
    expect(resolveBoardLayout('pod', true, 3)).toBe('pod')
  })

  it('arena solo en multiplayer, cae a standard en 1v1', () => {
    expect(resolveBoardLayout('arena', true, 2)).toBe('arena')
    expect(resolveBoardLayout('arena', true, 1)).toBe('standard')
  })
})

describe('effectiveBoardLayout (FFA solo en standard)', () => {
  it('fuerza standard con 5+ jugadores aunque el layout sea pod o arena', () => {
    expect(effectiveBoardLayout('pod', true, 4, 5)).toBe('standard')
    expect(effectiveBoardLayout('arena', true, 4, 5)).toBe('standard')
    expect(effectiveBoardLayout('standard', false, 4, 5)).toBe('standard')
  })

  it('respeta el layout con 4 o menos jugadores', () => {
    expect(effectiveBoardLayout('standard', false, 3, 4)).toBe('pod')
    expect(effectiveBoardLayout('pod', true, 3, 4)).toBe('pod')
    expect(effectiveBoardLayout('arena', true, 3, 4)).toBe('arena')
    expect(effectiveBoardLayout('standard', false, 1, 2)).toBe('standard')
  })
})

describe('boardLayout manual override (Ajustes)', () => {
  beforeEach(() => {
    reset()
  })

  it('elegir layout marca manual, persiste y respeta standard en multi', () => {
    setSetting('boardLayout', 'standard')
    expect(getState().settings.boardLayoutManual).toBe(true)
    expect(loadAppearanceSettings()).toMatchObject({ boardLayout: 'standard', boardLayoutManual: true })
    expect(resolveBoardLayout('standard', getState().settings.boardLayoutManual, 2)).toBe('standard')
  })

  it('otros ajustes no marcan manual', () => {
    setSetting('sleeveId', 'classic')
    expect(getState().settings.boardLayoutManual).toBe(false)
  })
})

describe('the all-boards view', () => {
  it('applies only with the setting on and more than four players', () => {
    expect(showsAllBoards(true, 5)).toBe(true)
    expect(showsAllBoards(true, 4)).toBe(false)
    expect(showsAllBoards(false, 10)).toBe(false)
  })

  const seats = (n: number) => Array.from({ length: n }, (_, i) => ({ playerId: `p${i}` }))
  const ids = (list: { playerId: string }[]) => list.map((p) => p.playerId)

  it('seats me bottom-left and the others clockwise in turn order', () => {
    const rows = allBoardsRows(seats(6), 'p2')
    expect(ids(rows.top)).toEqual(['p3', 'p4', 'p5'])
    expect(ids(rows.bottom)).toEqual(['p2', 'p1', 'p0'])
  })

  it('shows every player, up to ten', () => {
    for (let n = 5; n <= 10; n++) {
      const rows = allBoardsRows(seats(n), 'p0')
      expect([...ids(rows.top), ...ids(rows.bottom)].sort()).toEqual(ids(seats(n)).sort())
      expect(rows.top.length).toBe(Math.ceil(n / 2))
    }
  })

  it('a spectator starts from the first player', () => {
    expect(ids(allBoardsRows(seats(5)).bottom)[0]).toBe('p0')
  })

  it('an empty game has no seats', () => {
    expect(allBoardsRows([])).toEqual({ top: [], bottom: [] })
  })
})
