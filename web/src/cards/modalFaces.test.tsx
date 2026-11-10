import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { activeCardFace } from '../board/cardFaces'
import type { CardView } from '../net/types'
import { cardKey, resetCardImageCache } from './cardImages'
import { resetCardArtPreferences } from './artPreferences'
import { isModalBackFace, learnModalFaces, modalFrontFaceName } from './modalFaces'
import { useCardImageUrl } from './useCardImageUrl'

const FRONT = 'https://img.test/emeria_call.jpg'
const BACK = 'https://img.test/emeria_skyclave.jpg'

/** Scryfall's ZNR 12 (cut down): a sorcery // a land. */
const emeriaJson = {
  name: "Emeria's Call // Emeria, Shattered Skyclave",
  layout: 'modal_dfc',
  card_faces: [
    { name: "Emeria's Call", image_uris: { normal: FRONT } },
    { name: 'Emeria, Shattered Skyclave', image_uris: { normal: BACK } },
  ],
}

/** The server's view of the card played as the land: the land face's name, the main card's set and
 *  number, not transformed (its front face is not a permanent). */
const land = { id: 'c1', name: 'Emeria, Shattered Skyclave', expansionSetCode: 'ZNR', cardNumber: '12', cardTypes: ['LAND'] } as unknown as CardView
/** In hand, in a graveyard, in exile, or cast: the main card, named for its front face. */
const spell = { id: 'c1', name: "Emeria's Call", expansionSetCode: 'ZNR', cardNumber: '12', isDoubleFacedCard: true } as CardView

describe('a spell // land card', () => {
  beforeEach(() => {
    resetCardImageCache()
    resetCardArtPreferences()
    vi.restoreAllMocks()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => emeriaJson, headers: { get: () => null } }))
  })

  it('CRD-12: played as the land, the board shows the land face, even the first time the card is seen; never the spell face', async () => {
    const seen: (string | null)[] = []
    const { result } = renderHook(() => {
      const url = useCardImageUrl(land)
      seen.push(url)
      return url
    })
    await waitFor(() => expect(result.current).toBe(BACK))
    expect(seen).not.toContain(FRONT)
  })

  it('CRD-12: in hand, or anywhere off the battlefield, the board shows the spell face', async () => {
    const { result } = renderHook(() => useCardImageUrl(spell))
    await waitFor(() => expect(result.current).toBe(FRONT))
  })

  it('CRD-12: the card preview and the inspector show the land face of the land', () => {
    learnModalFaces("Emeria's Call", 'Emeria, Shattered Skyclave')
    expect(cardKey(activeCardFace(land, false) as CardView)).toBe('ZNR/12#back')
    expect(cardKey(activeCardFace(spell, false) as CardView)).toBe('ZNR/12')
  })

  it('CRD-12: knows the faces by name only, and never takes a transformed card for one', () => {
    expect(isModalBackFace(land)).toBe(false)
    learnModalFaces("Emeria's Call", 'Emeria, Shattered Skyclave')
    expect(isModalBackFace(land)).toBe(true)
    expect(isModalBackFace({ name: '  emeria, shattered skyclave ' } as CardView)).toBe(true)
    expect(modalFrontFaceName(land)).toBe("Emeria's Call")
    expect(isModalBackFace(spell)).toBe(false)
    expect(modalFrontFaceName(spell)).toBeNull()
    expect(isModalBackFace({ ...land, transformed: true })).toBe(false)
  })
})
