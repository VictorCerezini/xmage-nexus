import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DeckV2 } from './types'

const idb = new Map<string, unknown>()
vi.mock('idb-keyval', () => ({
  createStore: () => ({}),
  get: async (k: string) => idb.get(k),
  set: async (k: string, v: unknown) => { idb.set(k, v) },
  del: async (k: string) => { idb.delete(k) },
  keys: async () => [...idb.keys()],
}))

const ls = new Map<string, string>()

function make(id: string, card: string): DeckV2 {
  return {
    id,
    name: 'Vampires',
    cards: [{ cardName: card, setCode: 'M21', cardNumber: '1', amount: 1 }],
    sideboard: [],
    format: 'Commander',
    colors: [],
    createdAt: 1,
    updatedAt: 1,
    source: 'custom',
  }
}

beforeEach(() => {
  idb.clear()
  ls.clear()
  ls.set('mage_decks_v2_migrated', '1')
  vi.stubGlobal('indexedDB', {})
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => ls.get(k) ?? null,
    setItem: (k: string, v: string) => { ls.set(k, v) },
    removeItem: (k: string) => { ls.delete(k) },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('IndexedDB deck storage', () => {
  it("keeps the localStorage copy of a saved deck current, so the quick deck list never shows the old cards", async () => {
    ls.set('mage_decks_v2', JSON.stringify([make('d1', 'Old Card'), make('d2', 'Other')]))
    const { getDeckStorage } = await import('./storage')
    await getDeckStorage().put(make('d1', 'New Card'))

    const mirror = JSON.parse(ls.get('mage_decks_v2')!) as DeckV2[]
    expect(mirror.map((d) => [d.id, d.cards[0].cardName])).toEqual([['d1', 'New Card'], ['d2', 'Other']])
    expect((idb.get('deck:d1') as DeckV2).cards[0].cardName).toBe('New Card')
  })

  it('does not copy a new deck into localStorage', async () => {
    const { getDeckStorage } = await import('./storage')
    await getDeckStorage().put(make('d3', 'New Card'))
    expect(ls.get('mage_decks_v2')).toBeUndefined()
    expect(idb.has('deck:d3')).toBe(true)
  })
})
