import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CardInspector from './CardInspector'
import { setState } from '../state/store'
import { makeGameView, makePermanent, makePlayer } from '../__fixtures__/gameViews'
import { cardArtPreference, resetCardArtPreferences, setCardArtPreference } from '../cards/artPreferences'
import { resetCardImageCache } from '../cards/cardImages'
import { artFor, encodeSharedArt, ingestSharedArt, noteGameView, resetSharedArt, setDeckArt } from '../cards/sharedArt'
import type { CardView, GameView } from '../net/types'

vi.mock('../cards/cardImages', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../cards/cardImages')>()),
  awaitImageUrl: vi.fn().mockResolvedValue('https://img.test/card.jpg'),
}))

vi.mock('../cards/cardLocalization', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../cards/cardLocalization')>()),
  getEffectiveCardLang: () => 'en',
  useLocalizedCardText: () => ({ text: null, pending: false }),
}))

const TROSTANI = "Trostani, Selesnya's Voice"
const GAME = 'g-inspect'
const CUSTOMIZE = 'Imagen personalizada…'
const RESTORE = 'Restaurar arte por defecto'

const bobs = makePermanent({ id: 'b1', name: TROSTANI, controllerId: 'p-Bob', expansionSetCode: 'GPT', cardNumber: '128' })
const bobs2 = makePermanent({ id: 'b2', name: TROSTANI, controllerId: 'p-Bob', expansionSetCode: 'GPT', cardNumber: '128' })
const mine = makePermanent({ id: 'a1', name: TROSTANI, controllerId: 'p-Alice', expansionSetCode: 'GPT', cardNumber: '128' })
const mine2 = makePermanent({ id: 'a3', name: TROSTANI, controllerId: 'p-Alice', expansionSetCode: 'GPT', cardNumber: '128' })

function table(): GameView {
  return makeGameView({
    myPlayerId: 'p-Alice',
    players: [
      makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: true, battlefield: { a1: mine, a3: mine2 } }),
      makePlayer({ playerId: 'p-Bob', name: 'Bob', battlefield: { b1: bobs, b2: bobs2 } }),
    ],
  })
}

const prints = {
  data: [
    { id: 'p-rvr', set: 'rvr', set_name: 'Ravnica Remastered', collector_number: '216', released_at: '2024-01-12', rarity: 'rare', image_uris: { normal: 'https://cards.scryfall.io/rvr-216.jpg' } },
    { id: 'p-sld', set: 'sld', set_name: 'Secret Lair Drop', collector_number: '999', released_at: '2023-01-01', rarity: 'rare', image_uris: { normal: 'https://cards.scryfall.io/sld-999.jpg' } },
  ],
}

/** Opens the inspector on this card (if it is not open on it already) and its art menu. */
async function openArtMenu(card: CardView) {
  act(() => setState({ inspectedCard: card }))
  fireEvent.click(screen.getByText(CUSTOMIZE))
  await waitFor(() => expect(screen.getByText('Ravnica Remastered')).toBeDefined())
}

const menuOpen = () => document.querySelector('.printings-modal') !== null
const inspectorOpen = () => document.querySelector('.card-inspector') !== null
const RVR = { setCode: 'RVR', cardNumber: '216' }

describe('the art menu during a match', () => {
  beforeEach(() => {
    resetSharedArt()
    resetCardArtPreferences()
    resetCardImageCache()
    setDeckArt([])
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) =>
      String(url).includes('/cards/search')
        ? { ok: true, status: 200, json: async () => prints }
        : { ok: false, status: 404, json: async () => ({}) },
    ) as unknown as typeof fetch
  })

  afterEach(() => {
    cleanup()
    act(() => setState({ inspectedCard: null, game: null }))
  })

  it("CRD-8: another player's card goes back to the art they chose, from the same menu", async () => {
    const game = table()
    noteGameView(GAME, game)
    const [line] = encodeSharedArt([[TROSTANI, 'SLD', '999']], false, () => 1)
    ingestSharedArt(GAME, game, 'Bob', line)
    act(() => setState({ game }))
    render(<CardInspector />)

    await openArtMenu(bobs)
    // nothing was changed yet: no way back to offer
    expect(screen.queryByText(RESTORE)).toBeNull()
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(artFor(bobs)).toEqual(RVR)
    // the menu closes; the inspector stays open on the card, now with that art
    expect(menuOpen()).toBe(false)
    expect(inspectorOpen()).toBe(true)

    await openArtMenu(bobs)
    expect(screen.getByText('Vuelve al arte que eligió su dueño. Solo cambia en tu pantalla.')).toBeDefined()
    fireEvent.click(screen.getByText(RESTORE))
    expect(artFor(bobs)).toEqual({ setCode: 'SLD', cardNumber: '999' })
    expect(menuOpen()).toBe(false)
    expect(inspectorOpen()).toBe(true)
    // opened again, there is nothing left to restore
    await openArtMenu(bobs)
    expect(screen.queryByText(RESTORE)).toBeNull()
    // my own copy was never changed: nothing to restore there either
    await openArtMenu(mine)
    expect(screen.queryByText(RESTORE)).toBeNull()
  })

  it('CRD-8: my own card goes back to my deck\'s art, from the same menu', async () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    const game = table()
    noteGameView(GAME, game)
    act(() => setState({ game }))
    render(<CardInspector />)

    await openArtMenu(mine)
    expect(screen.queryByText(RESTORE)).toBeNull()
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(artFor(mine)).toEqual(RVR)

    await openArtMenu(mine)
    expect(screen.getByText('Vuelve al arte que tenía tu mazo al empezar la partida. Los demás jugadores también lo vuelven a ver.')).toBeDefined()
    fireEvent.click(screen.getByText(RESTORE))
    expect(artFor(mine)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    await openArtMenu(mine)
    expect(screen.queryByText(RESTORE)).toBeNull()
  })

  it('CRD-10: the art picked for one card changes that card only, not the others with its name or the deck', async () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    const game = table()
    noteGameView(GAME, game)
    act(() => setState({ game }))
    render(<CardInspector />)

    await openArtMenu(mine)
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(artFor(mine)).toEqual(RVR)
    expect(artFor(mine2)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'PRM', cardNumber: '50000' })

    await openArtMenu(bobs2)
    fireEvent.click(screen.getByText('Secret Lair Drop'))
    expect(artFor(bobs2)).toEqual({ setCode: 'SLD', cardNumber: '999' })
    expect(artFor(bobs)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
  })

  it('CRD-10: changing the art from an ability changes the card the ability comes from', async () => {
    const game = table()
    noteGameView(GAME, game)
    act(() => setState({ game }))
    render(<CardInspector />)

    const ability = { id: 'ab1', name: 'Ability', mageObjectType: 'ABILITY_STACK', controllerId: 'p-Alice', sourceCard: mine } as CardView
    await openArtMenu(ability)
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(artFor(mine)).toEqual(RVR)
    expect(artFor(mine2)).toBeNull()
    await openArtMenu(ability)
    expect(screen.getByText(RESTORE)).toBeDefined()
  })

  it('CRD-9: a click inside the art menu keeps it and the inspector open; outside the menu only closes the menu', async () => {
    const game = table()
    noteGameView(GAME, game)
    act(() => setState({ game }))
    render(<CardInspector />)

    await openArtMenu(mine)
    // "Upload image" opens the file picker: the menu must still be there when the file arrives
    fireEvent.click(screen.getByText('Subir imagen'))
    expect(menuOpen()).toBe(true)
    expect(inspectorOpen()).toBe(true)
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(menuOpen()).toBe(false)
    expect(inspectorOpen()).toBe(true)

    await openArtMenu(mine)
    fireEvent.click(document.querySelector('.printings-backdrop')!)
    expect(menuOpen()).toBe(false)
    expect(inspectorOpen()).toBe(true)
    // the inspector's own backdrop still closes it
    fireEvent.click(document.querySelector('.card-inspector-backdrop')!)
    expect(inspectorOpen()).toBe(false)
  })

  it("CRD-10: a token's art can be changed: the menu lists token printings, and only that token changes", async () => {
    const zombie = (id: string) =>
      makePermanent({ id, name: 'Zombie Token', controllerId: 'p-Alice', expansionSetCode: 'M21', cardNumber: '0', isToken: true, mageObjectType: 'TOKEN' })
    const z1 = zombie('z1')
    const z2 = zombie('z2')
    const game = makeGameView({
      myPlayerId: 'p-Alice',
      players: [
        makePlayer({ playerId: 'p-Alice', name: 'Alice', controlled: true, battlefield: { z1, z2 } }),
        makePlayer({ playerId: 'p-Bob', name: 'Bob' }),
      ],
    })
    noteGameView(GAME, game)
    act(() => setState({ game }))
    render(<CardInspector />)

    await openArtMenu(z1)
    const searches = vi.mocked(globalThis.fetch).mock.calls.map(([url]) => String(url)).filter((url) => url.includes('/cards/search'))
    expect(searches).toHaveLength(1)
    expect(searches[0]).toContain('q=!%22Zombie%22+include:extras&')
    fireEvent.click(screen.getByText('Ravnica Remastered'))
    expect(artFor(z1)).toEqual(RVR)
    expect(artFor(z2)).toBeNull()

    await openArtMenu(z1)
    expect(screen.getByText('Vuelve a la imagen habitual de la ficha. Los demás jugadores también la vuelven a ver.')).toBeDefined()
    fireEvent.click(screen.getByText(RESTORE))
    expect(artFor(z1)).toBeNull()
  })
})
