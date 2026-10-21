import type { ResultEnvelope } from '../net/types'
import { DeckStorageError, type DeckStorage } from './storage'
import type { DeckV2 } from './types'

export type SendCommand = <T>(action: string, args: Record<string, unknown>) => Promise<ResultEnvelope & { data?: T }>

/**
 * A friend's decks, kept by the host they joined (the friends proxy's listSavedDecks, saveDeck and
 * deleteSavedDeck) under the name they logged in with. In this browser they would be tied to the
 * host's address: gone with another browser or computer, a private window or cleared site data.
 * A proxy that keeps no decks answers UNKNOWN_ACTION, and the browser's storage takes over.
 */
export class HostDeckStorage implements DeckStorage {
  private last: DeckV2[] = []
  private local: DeckStorage | null = null

  constructor(
    private readonly send: SendCommand,
    private readonly browserStorage: () => DeckStorage,
  ) {}

  /** The host has no deck commands: from now on this browser keeps the decks. */
  private fallBack(res: ResultEnvelope): DeckStorage | null {
    if (res.errorCode !== 'UNKNOWN_ACTION') return null
    this.local = this.browserStorage()
    return this.local
  }

  async list(): Promise<DeckV2[]> {
    if (this.local) return this.local.list()
    const res = await this.send<unknown[]>('listSavedDecks', {})
    if (res.ok && Array.isArray(res.data)) {
      this.last = res.data.filter(isDeck).sort((a, b) => b.updatedAt - a.updatedAt)
      return [...this.last]
    }
    const local = this.fallBack(res)
    if (local) return local.list()
    // a dropped connection: the decks seen last rather than none, the next list is fresh
    return [...this.last]
  }

  async get(id: string): Promise<DeckV2 | null> {
    if (this.local) return this.local.get(id)
    return (await this.list()).find((d) => d.id === id) ?? null
  }

  async put(deck: DeckV2): Promise<void> {
    if (this.local) return this.local.put(deck)
    const toSave = { ...deck, updatedAt: Date.now() }
    if (!toSave.createdAt) toSave.createdAt = toSave.updatedAt
    const res = await this.send('saveDeck', { deck: toSave })
    if (!res.ok) {
      const local = this.fallBack(res)
      if (local) return local.put(deck)
      throw new DeckStorageError('io', res.error)
    }
    this.last = [toSave, ...this.last.filter((d) => d.id !== toSave.id)]
  }

  async del(id: string): Promise<void> {
    if (this.local) return this.local.del(id)
    const res = await this.send('deleteSavedDeck', { id })
    if (!res.ok) {
      const local = this.fallBack(res)
      if (local) return local.del(id)
      throw new DeckStorageError('io', res.error)
    }
    this.last = this.last.filter((d) => d.id !== id)
  }

  async count(): Promise<number> {
    return (await this.list()).length
  }
}

function isDeck(value: unknown): value is DeckV2 {
  const deck = value as Partial<DeckV2> | null
  return !!deck && typeof deck.id === 'string' && typeof deck.name === 'string' && Array.isArray(deck.cards)
}
