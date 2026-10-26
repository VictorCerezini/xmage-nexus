import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { getAllAvailableDecks, deckRef, sameDeck, type Deck } from '../decks'
import { isHumanSeatType, type SeatConfig } from './constants'

/** The stored version of `deck`: same id, else same name (a deck deleted and created again). */
function freshDeck(decks: Deck[], deck: Deck): Deck {
  return decks.find((d) => deckRef(d) === deckRef(deck)) ?? decks.find((d) => d.name === deck.name) ?? deck
}

/**
 * The decks the Create Table wizard can seat: the list (bundled + legacy right away, the stored
 * decks once the async storage answers), my deck and the default deck for bot seats.
 */
export function useDeckChoices(storeDeck: Deck | null, setSeatConfigs: Dispatch<SetStateAction<SeatConfig[]>>) {
  const [availableDecks, setAvailableDecks] = useState<Deck[]>(() => getAllAvailableDecks())
  const [decksLoaded, setDecksLoaded] = useState(false)
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const mod = await import('../../decks/storage')
        const st = mod.getDeckStorage()
        const v2 = await st.list()
        if (cancelled) return
        const maps = new Map<string, Deck>()
        for (const d of v2) {
          const deck: Deck = { ...d, id: d.id || `v2:${d.name}` }
          maps.set(deckRef(deck), deck)
        }
        for (const d of getAllAvailableDecks()) {
          if (!maps.has(deckRef(d))) maps.set(deckRef(d), d)
        }
        const loaded = [...maps.values()]
        setAvailableDecks(loaded)
        // The first pick came from the synchronous list (an older localStorage copy) or from the
        // last match's snapshot, so it can hold cards edited since: swap in the stored deck.
        const current = (d: Deck | null) => (d ? freshDeck(loaded, d) : d)
        setMyDeckState(current)
        setSimDeck(current)
      } catch {}
      if (!cancelled) setDecksLoaded(true)
    })()
    return () => { cancelled = true }
  }, [])
  const [myDeck, setMyDeckState] = useState<Deck | null>(() => {
    const avail = getAllAvailableDecks()
    if (storeDeck) {
      const match = avail.find((d) => sameDeck(d, storeDeck))
      if (match) return match
      return storeDeck
    }
    return avail[0] ?? null
  })
  const [simDeck, setSimDeck] = useState<Deck | null>(() => getAllAvailableDecks()[0] ?? null)

  // Si la lista de mazos llega tarde (storage async) y no hay selección, coger el primero.
  useEffect(() => {
    if (availableDecks.length === 0) return
    if (!myDeck) setMyDeckState(availableDecks[0])
    if (!simDeck) setSimDeck(availableDecks[0])
  }, [availableDecks])

  /** Resuelve un mazo por referencia estable (id) con fallback por nombre (form persistido antiguo). */
  const findDeck = (ref?: string): Deck | undefined => {
    if (!ref) return undefined
    return availableDecks.find((d) => deckRef(d) === ref) ?? availableDecks.find((d) => d.name === ref)
  }
  const selectMyDeck = (ref: string) => {
    setMyDeckState(findDeck(ref) ?? null)
  }
  const selectGlobalSimDeck = (ref: string) => {
    const d = findDeck(ref) ?? null
    setSimDeck(d)
    if (d) setSeatConfigs((prev) => prev.map((s) => !isHumanSeatType(s.type) ? { ...s, deckName: deckRef(d) } : s))
  }

  const adoptStarterDecks = (decks: Deck[], mine: Deck, botDeck: Deck) => {
    setAvailableDecks((prev) => [...prev, ...decks])
    setMyDeckState(mine)
    setSimDeck((current) => current ?? botDeck)
    setSeatConfigs((prev) => prev.map((s) => !isHumanSeatType(s.type) && !s.deckName ? { ...s, deckName: deckRef(botDeck) } : s))
  }

  return { availableDecks, decksLoaded, myDeck, simDeck, findDeck, selectMyDeck, selectGlobalSimDeck, adoptStarterDecks }
}
