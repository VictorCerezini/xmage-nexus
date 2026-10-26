import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, renderHook, waitFor } from '@testing-library/react'
import type { Deck } from '../decks'
import { useDeckChoices } from './useDeckChoices'

const stored: { list: Deck[] } = { list: [] }
vi.mock('../../decks/storage', () => ({
  getDeckStorage: () => ({ list: async () => stored.list }),
}))

const ls = new Map<string, string>()

function deck(id: string, name: string, card: string): Deck {
  return { id, name, cards: [{ cardName: card, setCode: 'M21', cardNumber: '1', amount: 1 }], sideboard: [] } as Deck
}

beforeEach(() => {
  ls.clear()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => ls.get(k) ?? null,
    setItem: (k: string, v: string) => { ls.set(k, v) },
    removeItem: (k: string) => { ls.delete(k) },
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('useDeckChoices', () => {
  it('swaps the old copy of the chosen deck for the stored one once it loads', async () => {
    ls.set('mage_decks_v2', JSON.stringify([deck('d1', 'Vampires', 'Old Card')]))
    stored.list = [deck('d1', 'Vampires', 'New Card')]
    const { result } = renderHook(() => useDeckChoices(deck('d1', 'Vampires', 'Old Card'), () => {}))
    await waitFor(() => expect(result.current.decksLoaded).toBe(true))
    expect(result.current.myDeck?.cards[0].cardName).toBe('New Card')
  })

  it("finds a deck deleted and created again by its name when the last match's id is gone", async () => {
    stored.list = [deck('d2', 'Vampires', 'New Card')]
    const { result } = renderHook(() => useDeckChoices(deck('d1', 'Vampires', 'Old Card'), () => {}))
    await waitFor(() => expect(result.current.decksLoaded).toBe(true))
    expect(result.current.myDeck?.id).toBe('d2')
    expect(result.current.myDeck?.cards[0].cardName).toBe('New Card')
  })

  it('keeps a deck the storage does not know', async () => {
    stored.list = [deck('d3', 'Elves', 'Llanowar Elves')]
    const { result } = renderHook(() => useDeckChoices(deck('d1', 'Vampires', 'Old Card'), () => {}))
    await waitFor(() => expect(result.current.decksLoaded).toBe(true))
    expect(result.current.myDeck?.id).toBe('d1')
  })
})
