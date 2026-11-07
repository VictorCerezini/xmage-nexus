import { beforeEach, describe, expect, it, vi } from 'vitest'
import { makeCard, makeGameView, makePermanent, makePlayer } from '../__fixtures__/gameViews'
import type { CardView, PermanentView } from '../net/types'

vi.mock('../cards/scryfallCards', () => ({ fetchCardJson: vi.fn() }))

import { fetchCardJson } from '../cards/scryfallCards'
import { rememberDeckArt } from './deckArt'
import type { DeckArtSwap } from './deckIssues'
import { cardKey, resetCardImageCache } from '../cards/cardImages'
import { resetCardArtPreferences, setCardArtPreference } from '../cards/artPreferences'
import { decodeSharedArt, linesToAnnounce, noteGameView, resetSharedArt, setDeckArt } from '../cards/sharedArt'

const TROSTANI = "Trostani, Selesnya's Voice"
const GAME = 'g-deck-art'

/** Trostani from the deck as SLD #2429; the server got C19 #204 in its place. */
const trostaniSwap: DeckArtSwap = {
  art: { cardName: TROSTANI, setCode: 'SLD', cardNumber: '2429' },
  sent: { cardName: TROSTANI, setCode: 'C19', cardNumber: '204' },
  only: true,
}

type Perm = Partial<PermanentView> & { id: string; name: string }

/** Alice (me) and Bob, each with what is given on the battlefield. */
const view = (alice: Perm[] = [], bob: Perm[] = []) =>
  makeGameView({
    myPlayerId: 'p-Alice',
    players: [
      makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: true, battlefield: Object.fromEntries(alice.map((c) => [c.id, makePermanent(c)])) }),
      makePlayer({ playerId: 'p-Bob', name: 'Bob', battlefield: Object.fromEntries(bob.map((c) => [c.id, makePermanent(c)])) }),
    ],
  })
const trostani = (id: string, controllerId: string, extra: Partial<PermanentView> = {}): Perm => ({
  id, name: TROSTANI, controllerId, expansionSetCode: 'C19', cardNumber: '204', ...extra,
})
const defaultView = () => view([trostani('a1', 'p-Alice')], [trostani('b1', 'p-Bob')])
const mine = trostani('a1', 'p-Alice') as CardView
const bobs = trostani('b1', 'p-Bob') as CardView

beforeEach(() => {
  resetSharedArt()
  setDeckArt([])
  resetCardArtPreferences()
  resetCardImageCache()
  vi.mocked(fetchCardJson).mockReset()
})

describe('the art of a deck printing the server did not have', () => {
  it('DCK-17: is kept after checking on Scryfall that the printing is that card', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    expect(fetchCardJson).toHaveBeenCalledWith({ cardName: TROSTANI, setCode: 'SLD', cardNumber: '2429' }, { fallbackToName: false })
    noteGameView(GAME, defaultView())
    expect(cardKey(mine)).toBe('SLD/2429')
    // not someone else's copy of the same card
    expect(cardKey(bobs)).toBe('C19/204')
  })

  it('DCK-17: is announced to the other players, for the copies the server sent as that printing', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    const { lines } = linesToAnnounce(noteGameView(GAME, defaultView()))
    expect(decodeSharedArt(lines[0])?.a).toEqual([[TROSTANI, 'SLD', '2429', 'C19', '204']])
  })

  it('DCK-17: a later art pick for the card wins over the deck printing', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    setCardArtPreference(TROSTANI, 'RNA', '1')
    noteGameView(GAME, defaultView())
    expect(cardKey(mine)).toBe('RNA/1')
  })

  it('DCK-17: a printing Scryfall does not have, or that is another card, is left out', async () => {
    vi.mocked(fetchCardJson).mockImplementation(async (card) => (card.setCode === 'C20' ? ({ name: 'Fiend Hunter' } as never) : null))
    await rememberDeckArt([
      { ...trostaniSwap, art: { cardName: TROSTANI, setCode: 'CY', cardNumber: '77' } },
      { art: { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77' }, sent: { cardName: 'Banisher Priest', setCode: 'M14', cardNumber: '7' }, only: true },
    ])
    noteGameView(GAME, defaultView())
    expect(cardKey(mine)).toBe('C19/204')
    expect(noteGameView(GAME, defaultView())).toEqual([])
  })

  it('DCK-17: sending another deck forgets the last one, and a slow answer for an older deck is dropped', async () => {
    let answer: (v: unknown) => void = () => {}
    vi.mocked(fetchCardJson).mockReturnValueOnce(new Promise((r) => { answer = r }) as never)
    const first = rememberDeckArt([trostaniSwap])
    await rememberDeckArt([])
    answer({ name: TROSTANI })
    await first
    noteGameView(GAME, defaultView())
    expect(cardKey(mine)).toBe('C19/204')
  })

  it('DCK-17: several arts of one card each stay on their own copies; a printing sent as it is keeps its own', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: 'Island' } as never)
    await rememberDeckArt([
      { art: { cardName: 'Island', setCode: 'SLD', cardNumber: '1' }, sent: { cardName: 'Island', setCode: 'M21', cardNumber: '261' }, only: false },
      { art: { cardName: 'Island', setCode: 'SLD', cardNumber: '2' }, sent: { cardName: 'Island', setCode: 'M21', cardNumber: '262' }, only: false },
    ])
    const island = (id: string, num: string): Perm => ({ id, name: 'Island', controllerId: 'p-Alice', expansionSetCode: 'M21', cardNumber: num })
    noteGameView(GAME, view([island('i1', '261'), island('i2', '262'), island('i3', '260')]))
    expect(cardKey(island('i1', '261') as CardView)).toBe('SLD/1')
    expect(cardKey(island('i2', '262') as CardView)).toBe('SLD/2')
    expect(cardKey(island('i3', '260') as CardView)).toBe('M21/260')
    // and if the server showed yet another printing, no art is guessed: there were two
    expect(cardKey(island('i4', '999') as CardView)).toBe('M21/999')
  })

  it('DCK-17: the only art of a card still applies when the server shows yet another printing of it', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    noteGameView(GAME, defaultView())
    expect(cardKey({ ...mine, expansionSetCode: 'GPT', cardNumber: '128' } as CardView)).toBe('SLD/2429')
  })

  it('DCK-17: stays on a double-faced card after it transforms, and on an adventure cast from hand', async () => {
    vi.mocked(fetchCardJson).mockImplementation(async (card) =>
      card.cardName === 'Delver of Secrets'
        ? ({ name: 'Delver of Secrets // Insectile Aberration', card_faces: [{ name: 'Delver of Secrets' }, { name: 'Insectile Aberration' }] } as never)
        : ({ name: 'Brazen Borrower // Petty Theft', card_faces: [{ name: 'Brazen Borrower' }, { name: 'Petty Theft' }] } as never),
    )
    await rememberDeckArt([
      { art: { cardName: 'Delver of Secrets', setCode: 'ISD', cardNumber: '51' }, sent: { cardName: 'Delver of Secrets', setCode: 'MID', cardNumber: '47' }, only: true },
      { art: { cardName: 'Brazen Borrower', setCode: 'PELD', cardNumber: '39p' }, sent: { cardName: 'Brazen Borrower', setCode: 'ELD', cardNumber: '39' }, only: true },
    ])
    const flipped: Perm = {
      id: 'a1', name: 'Insectile Aberration', controllerId: 'p-Alice', expansionSetCode: 'MID', cardNumber: '47',
      transformed: true, secondCardFace: makeCard({ name: 'Delver of Secrets' }),
    }
    const adventure = makeCard({ id: 's1', name: 'Petty Theft', controllerId: 'p-Alice', expansionSetCode: 'ELD', cardNumber: '39' })
    const game = view([flipped])
    game.stack = { s1: adventure }
    const entries = noteGameView(GAME, game)
    expect(cardKey(flipped as CardView)).toBe('ISD/51#back')
    expect(cardKey(adventure)).toBe('PELD/39p')
    expect(entries.map((e) => e[0])).toEqual(['Insectile Aberration', 'Delver of Secrets', 'Petty Theft'])
  })

  it('DCK-17: follows the card when another player takes it, and is not put on a card this player took', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    // Bob controls Alice's Trostani; Alice controls Bob's
    const taken = trostani('a1', 'p-Bob', { nameOwner: 'Alice', controlledByOwner: false })
    const took = trostani('b1', 'p-Alice', { nameOwner: 'Bob', controlledByOwner: false })
    const entries = noteGameView(GAME, view([took], [taken]))
    expect(cardKey(taken as CardView)).toBe('SLD/2429')
    expect(cardKey(took as CardView)).toBe('C19/204')
    // Alice still announces her own card while Bob holds it
    expect(entries).toEqual([[TROSTANI, 'SLD', '2429', 'C19', '204']])
  })

  it('DCK-17: shows on this player\'s cards in a library search, where the view says nothing about whose they are', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    noteGameView(GAME, defaultView())
    expect(cardKey({ id: 'lib-1', name: TROSTANI, expansionSetCode: 'C19', cardNumber: '204' } as CardView)).toBe('SLD/2429')
  })

  it('DCK-17: survives a page reload in the same tab', async () => {
    vi.mocked(fetchCardJson).mockResolvedValue({ name: TROSTANI } as never)
    await rememberDeckArt([trostaniSwap])
    vi.resetModules()
    const fresh = await import('../cards/sharedArt')
    const images = await import('../cards/cardImages')
    fresh.noteGameView(GAME, defaultView())
    expect(images.cardKey(mine)).toBe('SLD/2429')
    fresh.setDeckArt([])
  })
})
