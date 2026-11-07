import { cleanup, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DeckIssuesDialog, { requestDeckValidation } from './DeckIssuesDialog'
import type { Deck } from './decks'
import type { DeckValidationResult } from '../net/types'

vi.mock('../decks/deckIssues', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../decks/deckIssues')>()
  return { ...actual, fetchDeckIssues: vi.fn() }
})

vi.mock('../decks/deckArt', () => ({ rememberDeckArt: vi.fn().mockResolvedValue(undefined) }))

import { fetchDeckIssues } from '../decks/deckIssues'
import { rememberDeckArt } from '../decks/deckArt'

afterEach(() => {
  cleanup()
  vi.mocked(fetchDeckIssues).mockReset()
  vi.mocked(rememberDeckArt).mockClear()
})

const MOXFIELD = {
  name: 'Selesnya',
  cards: [
    { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1 },
    { cardName: 'Forest', setCode: 'ZEN', cardNumber: '246', amount: 30 },
  ],
  sideboard: [],
} as unknown as Deck

const trostaniOnlyInAnotherPrinting: DeckValidationResult = {
  ready: true,
  missing: [{
    cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1, reason: 'OUTDATED_PRINTING',
    suggestions: [{ cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204' }],
  }],
  mismatches: [],
  fixedDeck: { name: 'Selesnya', cards: [{ cardName: 'Forest', setCode: 'ZEN', cardNumber: '246', amount: 30 }], sideboard: [] },
} as unknown as DeckValidationResult

const DECK = {
  name: 'Test',
  cards: [{ cardName: 'Andúril', setCode: 'LTR', cardNumber: '1', amount: 1 }],
  sideboard: [],
} as unknown as Deck

const mismatchOnly: DeckValidationResult = {
  ready: true,
  missing: [],
  mismatches: [
    { cardName: 'Andúril', setCode: 'LTR', cardNumber: '1', amount: 1, resolvedName: 'Anduril', suggestions: [] },
  ],
} as unknown as DeckValidationResult

describe('DeckIssuesDialog', () => {
  it('lets the player accept a deck that only has name mismatches', async () => {
    vi.mocked(fetchDeckIssues).mockResolvedValue(mismatchOnly)
    const { findByTestId, queryByTestId } = render(<DeckIssuesDialog />)
    const result = requestDeckValidation(DECK)

    fireEvent.click(await findByTestId('deck-issues-accept-and-play'))

    await expect(result).resolves.toBe(DECK)
    await waitFor(() => expect(queryByTestId('deck-issues-dialog')).toBeNull())
  })

  it('does not offer to accept when the server would reject a card', async () => {
    vi.mocked(fetchDeckIssues).mockResolvedValue({
      ...mismatchOnly,
      missing: [{ cardName: 'Nope', setCode: 'ZZZ', cardNumber: '1', amount: 1, reason: 'UNIMPLEMENTED' }],
      fixedDeck: { name: 'Test', cards: [], sideboard: [] },
    } as unknown as DeckValidationResult)
    const { findByTestId, queryByTestId } = render(<DeckIssuesDialog />)
    void requestDeckValidation(DECK)

    await findByTestId('deck-issues-remove-and-play')
    expect(queryByTestId('deck-issues-accept-and-play')).toBeNull()
    fireEvent.click(await findByTestId('deck-issues-cancel'))
  })

  it("DCK-17: a printing the server lacks is swapped without a dialog, and the deck's printing is kept as art", async () => {
    vi.mocked(fetchDeckIssues).mockResolvedValue(trostaniOnlyInAnotherPrinting)
    const { queryByTestId } = render(<DeckIssuesDialog />)
    const sent = await requestDeckValidation(MOXFIELD)
    expect(queryByTestId('deck-issues-dialog')).toBeNull()
    expect(sent?.cards).toEqual([
      { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204', amount: 1 },
      { cardName: 'Forest', setCode: 'ZEN', cardNumber: '246', amount: 30 },
    ])
    expect(rememberDeckArt).toHaveBeenCalledWith([
      {
        art: { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429' },
        sent: { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204' },
        only: true,
      },
    ])
    // one check is enough when nothing else is wrong
    expect(fetchDeckIssues).toHaveBeenCalledTimes(1)
  })

  it('DCK-17: with a card the server cannot play too, only that card is shown, and removing it keeps the swap', async () => {
    const withNope = { ...MOXFIELD, cards: [...MOXFIELD.cards, { cardName: 'Nope', setCode: 'ZZZ', cardNumber: '1', amount: 1 }] } as unknown as Deck
    const nope = { cardName: 'Nope', setCode: 'ZZZ', cardNumber: '1', amount: 1, reason: 'UNIMPLEMENTED' }
    vi.mocked(fetchDeckIssues)
      .mockResolvedValueOnce({ ...trostaniOnlyInAnotherPrinting, missing: [...trostaniOnlyInAnotherPrinting.missing, nope] } as unknown as DeckValidationResult)
      .mockResolvedValueOnce({
        ready: true,
        missing: [nope],
        mismatches: [],
        fixedDeck: {
          name: 'Selesnya',
          cards: [
            { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204', amount: 1 },
            { cardName: 'Forest', setCode: 'ZEN', cardNumber: '246', amount: 30 },
          ],
          sideboard: [],
        },
      } as unknown as DeckValidationResult)
    const { findByTestId, getAllByTestId } = render(<DeckIssuesDialog />)
    const result = requestDeckValidation(withNope)

    await findByTestId('deck-issues-dialog')
    const rows = getAllByTestId('deck-issue-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('Nope')
    // the second check ran on the deck with the printing already swapped
    expect(vi.mocked(fetchDeckIssues).mock.calls[1][0].cards[0]).toMatchObject({ setCode: 'C19', cardNumber: '204' })

    // nothing is kept while the player has not decided
    expect(rememberDeckArt).not.toHaveBeenCalled()
    fireEvent.click(await findByTestId('deck-issues-remove-and-play'))
    const sent = await result
    expect(sent?.cards.map((c) => c.cardName)).toEqual(["Trostani, Selesnya's Voice", 'Forest'])
    expect(sent?.cards[0].setCode).toBe('C19')
    expect(rememberDeckArt).toHaveBeenCalledTimes(1)
  })

  it('DCK-17: a cancelled join leaves the art of the last deck sent as it was', async () => {
    const nope = { cardName: 'Nope', setCode: 'ZZZ', cardNumber: '1', amount: 1, reason: 'UNIMPLEMENTED' }
    vi.mocked(fetchDeckIssues)
      .mockResolvedValueOnce({ ...trostaniOnlyInAnotherPrinting, missing: [...trostaniOnlyInAnotherPrinting.missing, nope] } as unknown as DeckValidationResult)
      .mockResolvedValueOnce({ ready: true, missing: [nope], mismatches: [] } as unknown as DeckValidationResult)
    const { findByTestId } = render(<DeckIssuesDialog />)
    const result = requestDeckValidation(MOXFIELD)
    fireEvent.click(await findByTestId('deck-issues-cancel'))
    await expect(result).resolves.toBeNull()
    expect(rememberDeckArt).not.toHaveBeenCalled()
  })
})
