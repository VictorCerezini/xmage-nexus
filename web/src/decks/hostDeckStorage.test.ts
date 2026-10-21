import { describe, expect, it, vi } from 'vitest'
import type { ResultEnvelope } from '../net/types'
import { HostDeckStorage, type SendCommand } from './hostDeckStorage'
import { createMemoryStorage, isQuotaError } from './storage'
import type { DeckV2 } from './types'

const deck = (id: string, name: string, updatedAt = 1): DeckV2 => ({
  id, name, format: 'Modern', colors: [], favorite: false, cards: [{ cardName: 'Lightning Bolt', setCode: 'M10', cardNumber: '146', amount: 4 }],
  sideboard: [], createdAt: 1, updatedAt, source: 'custom',
} as DeckV2)

/** The friends proxy's three commands over one player's decks, as SavedDecks keeps them. */
function fakeHost() {
  const kept = new Map<string, DeckV2>()
  let down = false
  const send = vi.fn(async (action: string, args: Record<string, unknown>) => {
    const result = (ok: boolean, data?: unknown, error?: string): ResultEnvelope => ({ type: 'result', action, ok, data, error })
    if (down) return { ...result(false, undefined, 'not connected'), errorCode: 'NOT_CONNECTED' }
    if (action === 'listSavedDecks') return result(true, [...kept.values()])
    if (action === 'saveDeck') { const d = args.deck as DeckV2; kept.set(d.id, d); return result(true) }
    if (action === 'deleteSavedDeck') { kept.delete(args.id as string); return result(true) }
    return { ...result(false, undefined, `Unknown action: ${action}`), errorCode: 'UNKNOWN_ACTION' }
  })
  return { kept, send: send as unknown as SendCommand, calls: send, goDown: () => { down = true } }
}

describe('HostDeckStorage', () => {
  it("keeps a friend's decks on the host, not in the browser", async () => {
    const host = fakeHost()
    const browser = createMemoryStorage()
    const storage = new HostDeckStorage(host.send, () => browser)

    await storage.put(deck('d1', 'Burn'))
    await storage.put(deck('d2', 'Humans'))
    await storage.put({ ...deck('d1', 'Burn v2') })

    expect([...host.kept.keys()].sort()).toEqual(['d1', 'd2'])
    expect(await browser.count()).toBe(0)
    expect((await storage.get('d1'))?.name).toBe('Burn v2')
    expect(await storage.count()).toBe(2)

    await storage.del('d2')
    expect((await storage.list()).map((d) => d.id)).toEqual(['d1'])
  })

  it('a deck saved from another browser shows up here', async () => {
    const host = fakeHost()
    host.kept.set('old', deck('old', 'Made last week', 5))
    host.kept.set('new', deck('new', 'Made today', 9))
    const storage = new HostDeckStorage(host.send, () => createMemoryStorage())
    expect((await storage.list()).map((d) => d.name)).toEqual(['Made today', 'Made last week'])
  })

  it('a save the host did not take is an error the deck builder shows, never a silent loss', async () => {
    const host = fakeHost()
    const storage = new HostDeckStorage(host.send, () => createMemoryStorage())
    await storage.put(deck('d1', 'Burn'))
    host.goDown()

    const failed = await storage.put(deck('d1', 'Burn v2')).catch((e: unknown) => e)
    expect(failed).toBeInstanceOf(Error)
    expect(isQuotaError(failed)).toBe(false)
    await expect(storage.del('d1')).rejects.toBeInstanceOf(Error)
    // the list seen last, rather than an empty one that would read as "no decks"
    expect((await storage.list()).map((d) => d.name)).toEqual(['Burn'])
  })

  it('a host that keeps no decks leaves them in the browser', async () => {
    const send = vi.fn(async (action: string) => ({ type: 'result', action, ok: false, errorCode: 'UNKNOWN_ACTION' }) as ResultEnvelope)
    const browser = createMemoryStorage()
    const storage = new HostDeckStorage(send as unknown as SendCommand, () => browser)

    await storage.put(deck('d1', 'Burn'))
    expect(await browser.count()).toBe(1)
    expect((await storage.list()).map((d) => d.id)).toEqual(['d1'])
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('ignores anything the host sends back that is not a deck', async () => {
    const send = vi.fn(async (action: string) => ({ type: 'result', action, ok: true, data: [deck('d1', 'Burn'), { id: 'x' }, null, 'junk'] }) as ResultEnvelope)
    const storage = new HostDeckStorage(send as unknown as SendCommand, () => createMemoryStorage())
    expect((await storage.list()).map((d) => d.id)).toEqual(['d1'])
  })

  it("is the storage of a friend's page, and only of one", async () => {
    vi.resetModules()
    const join = await import('../state/friendsJoin')
    const host = await import('./hostDeckStorage')
    join.setFriendsJoin({ wsHost: '26.10.20.30', wsPort: 8777 })
    expect((await import('./storage')).getDeckStorage()).toBeInstanceOf(host.HostDeckStorage)

    vi.resetModules()
    const again = await import('./hostDeckStorage')
    expect((await import('./storage')).getDeckStorage()).not.toBeInstanceOf(again.HostDeckStorage)
    join.setFriendsJoin(null)
  })
})
