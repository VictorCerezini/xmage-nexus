import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setGateway } from '../net/commands'
import type { Gateway } from '../net/Gateway'
import { fetchDeckIssues, fetchFormatIssues, xmageDeckTypeFor, applySuggestion, applySuggestions, deckIssueKey, fixPrintingsQuietly, isPrintingOnlyIssue, withoutPrintingOnlyIssues, fixesForIssue, issuePrintings, findFlaggedSameName, issueKeysFromReport, isSamePrinting, syncCommandersForValidation } from './deckIssues'
import type { DeckValidationResult } from '../net/types'

const report: DeckValidationResult = {
  ready: true,
  missing: [
    { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77', amount: 1, reason: 'OUTDATED_PRINTING', suggestions: [{ cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' }] },
  ],
  mismatches: [
    { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77', amount: 2, resolvedName: 'Banisher Priest' },
  ],
}

describe('deckIssues helpers', () => {
  it('deckIssueKey compone name|set|num', () => {
    expect(deckIssueKey('A', 'B', 'C')).toBe('A|B|C')
  })

  it('issueKeysFromReport marca missing y mismatches', () => {
    const keys = issueKeysFromReport(report)
    expect(keys.has('Rhystic Tutor|CY|77')).toBe(true)
    expect(keys.has('Banisher Priest|C20|77')).toBe(true)
    expect(keys.size).toBe(2)
  })

  it('applySuggestion reemplaza en main y sideboard conservando el resto', () => {
    const deck = {
      name: 't',
      cards: [
        { cardName: 'Island', setCode: 'M21', cardNumber: '265' },
        { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' },
      ],
      sideboard: [{ cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }],
    }
    const next = applySuggestion(deck, { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }, { cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' })
    expect(next.cards[1]).toEqual({ cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' })
    expect(next.sideboard[0]).toEqual({ cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' })
    expect(next.cards[0]).toEqual({ cardName: 'Island', setCode: 'M21', cardNumber: '265' })
  })

  it('applySuggestion no toca otras impresiones del mismo nombre', () => {
    const deck = {
      name: 't',
      cards: [{ cardName: 'Rhystic Tutor', setCode: 'PRO', cardNumber: '77' }],
      sideboard: [],
    }
    const next = applySuggestion(deck, { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }, { cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' })
    expect(next.cards[0].setCode).toBe('PRO')
  })

  it('applySuggestion arrastra comandante/pareja/portada a la nueva impresión (AUDIT)', () => {
    const deck = {
      name: 'CMD',
      cards: [{ cardName: 'Sidar Kondo of Jamuraa', setCode: 'PC2', cardNumber: '1', amount: 1 }],
      sideboard: [],
      commanderCard: { cardName: 'Sidar Kondo of Jamuraa', setCode: 'PC2', cardNumber: '1', amount: 1 },
      coverCard: { cardName: 'Sidar Kondo of Jamuraa', setCode: 'PC2', cardNumber: '1', amount: 1 },
    }
    const next = applySuggestion(deck, { cardName: 'Sidar Kondo of Jamuraa', setCode: 'PC2', cardNumber: '1' }, { cardName: 'Sidar Kondo of Jamuraa', setCode: 'CMR', cardNumber: '535' })
    expect(next.commanderCard).toMatchObject({ setCode: 'CMR', cardNumber: '535' })
    expect(next.coverCard).toMatchObject({ setCode: 'CMR', cardNumber: '535' })
    expect(next.cards[0]).toMatchObject({ setCode: 'CMR', cardNumber: '535' })
  })

  it('applySuggestion actualiza el array commanders si existe en mazos importados', () => {
    const deck = {
      name: 'CMD',
      cards: [{ cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1 }],
      sideboard: [],
      commanders: [{ cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1 }],
      commanderCard: { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1 },
    }
    const next = applySuggestion(
      deck,
      { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429' },
      { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204' },
    )
    expect(next.commanders[0]).toMatchObject({ setCode: 'C19', cardNumber: '204' })
    expect(next.commanderCard).toMatchObject({ setCode: 'C19', cardNumber: '204' })
    expect(next.cards[0]).toMatchObject({ setCode: 'C19', cardNumber: '204' })
  })

  it('isSamePrinting y applySuggestion toleran diferencias de mayúsculas y apóstrofes tipográficos', () => {
    const from = { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429' }
    const inDeck = { cardName: 'Trostani, Selesnya’s Voice', setCode: 'sld', cardNumber: '2429' }
    expect(isSamePrinting(inDeck, from)).toBe(true)

    const deck = {
      name: 'CMD',
      cards: [{ cardName: 'Trostani, Selesnya’s Voice', setCode: 'sld', cardNumber: '2429', amount: 1 }],
      sideboard: [],
      commanderCard: { cardName: 'Trostani, Selesnya’s Voice', setCode: 'sld', cardNumber: '2429', amount: 1 },
    }
    const next = applySuggestion(deck, from, { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204' })
    expect(next.cards[0]).toMatchObject({ setCode: 'C19', cardNumber: '204' })
    expect(next.commanderCard).toMatchObject({ setCode: 'C19', cardNumber: '204' })
  })

  it('syncCommandersForValidation sincroniza commanders con commanderCard y partnerCard', () => {
    const cmd = { cardName: "Trostani, Selesnya's Voice", setCode: 'C19', cardNumber: '204', amount: 1 }
    const staleCmd = { cardName: "Trostani, Selesnya's Voice", setCode: 'SLD', cardNumber: '2429', amount: 1 }
    const deck = {
      name: 'CMD',
      cards: [cmd],
      sideboard: [],
      commanders: [staleCmd],
      commanderCard: cmd,
    }
    const synced = syncCommandersForValidation(deck)
    expect(synced.commanders).toEqual([cmd])
  })

  it("DCK-17: a card the server has in another printing is the app's to fix; one it cannot play is the player's", () => {
    const unimplemented = { cardName: 'Nope', setCode: 'ZZZ', cardNumber: '1', amount: 1, reason: 'UNIMPLEMENTED' as const }
    const swappedWithFix = { ...report.mismatches[0], suggestions: [{ cardName: 'Banisher Priest', setCode: 'M14', cardNumber: '7' }] }
    expect(isPrintingOnlyIssue(report.missing[0])).toBe(true)
    expect(isPrintingOnlyIssue(swappedWithFix)).toBe(true)
    // loaded as another card and no printing of the right one: a real problem
    expect(isPrintingOnlyIssue(report.mismatches[0])).toBe(false)
    expect(isPrintingOnlyIssue(unimplemented)).toBe(false)
    const left = withoutPrintingOnlyIssues({ ...report, missing: [...report.missing, unimplemented], mismatches: [swappedWithFix] })
    expect(left.missing).toEqual([unimplemented])
    expect(left.mismatches).toEqual([])
  })

  it("DCK-17: the quiet fix moves the card to a printing the server has and keeps the deck's printing as its art", () => {
    const deck = {
      name: 't',
      cards: [
        { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77', amount: 1 },
        { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77', amount: 2 },
        { cardName: 'Island', setCode: 'LEA', cardNumber: '288', amount: 20 },
      ],
      sideboard: [{ cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77', amount: 1 }],
    }
    const quiet = fixPrintingsQuietly(deck, report)
    expect(quiet.fixed).toBe(1)
    expect(quiet.deck.cards.map((c) => `${c.setCode}#${c.cardNumber}`)).toEqual(['PCY#77', 'C20#77', 'LEA#288'])
    expect(quiet.deck.sideboard[0].setCode).toBe('PCY')
    expect(quiet.arts).toEqual([
      { art: { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }, sent: { cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' }, only: true },
    ])
    // nothing to fix: the very same deck comes back
    expect(fixPrintingsQuietly(deck, { ready: true, missing: [], mismatches: report.mismatches })).toEqual({ deck, arts: [], fixed: 0 })
  })

  it('DCK-17: a card imported with no printing is fixed but has no art of its own to keep', () => {
    const noPrinting: DeckValidationResult = {
      ready: true,
      missing: [{ cardName: 'Forest', setCode: '', cardNumber: '', amount: 17, reason: 'OUTDATED_PRINTING', suggestions: [{ cardName: 'Forest', setCode: 'M21', cardNumber: '274' }] }],
      mismatches: [],
    }
    const deck = { name: 't', cards: [{ cardName: 'Forest', setCode: '', cardNumber: '', amount: 17 }], sideboard: [] }
    const quiet = fixPrintingsQuietly(deck, noPrinting)
    expect(quiet.deck.cards[0]).toMatchObject({ setCode: 'M21', cardNumber: '274' })
    expect(quiet.arts).toEqual([])
  })

  it('applySuggestions applies every fix across main and sideboard', () => {
    const deck = {
      name: 't',
      cards: [
        { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' },
        { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77' },
      ],
      sideboard: [{ cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }],
    }
    const next = applySuggestions(deck, [
      { from: { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }, to: { cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' } },
      { from: { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77' }, to: { cardName: 'Banisher Priest', setCode: 'M14', cardNumber: '7' } },
    ])
    expect(next.cards.map((c) => c.setCode)).toEqual(['PCY', 'M14'])
    expect(next.sideboard[0].setCode).toBe('PCY')
    expect(applySuggestions(deck, [])).toBe(deck)
  })

  it('a normalized promo printing still reaches the raw entry stored in the deck', () => {
    const promo: DeckValidationResult = {
      ready: true,
      missing: [{
        cardName: "Agatha's Soul Cauldron", setCode: 'WOE', cardNumber: '242s', amount: 1, reason: 'OUTDATED_PRINTING',
        suggestions: [{ cardName: "Agatha's Soul Cauldron", setCode: 'WOE', cardNumber: '242' }],
        sources: [{ setCode: 'PWOE', cardNumber: '242s' }],
      }],
      mismatches: [],
    }
    expect(issueKeysFromReport(promo).has("Agatha's Soul Cauldron|PWOE|242s")).toBe(true)
    expect(issuePrintings(promo.missing[0]).map((p) => p.setCode)).toEqual(['WOE', 'PWOE'])
    const deck = { name: 't', cards: [{ cardName: "Agatha's Soul Cauldron", setCode: 'PWOE', cardNumber: '242s', amount: 1 }], sideboard: [] }
    const viaRow = applySuggestions(deck, fixesForIssue(promo.missing[0], promo.missing[0].suggestions![0]))
    expect(viaRow.cards[0]).toEqual({ cardName: "Agatha's Soul Cauldron", setCode: 'WOE', cardNumber: '242', amount: 1 })
    const quiet = fixPrintingsQuietly(deck, promo)
    expect(quiet.deck.cards[0].setCode).toBe('WOE')
    // the art kept is the printing the deck stored, not the one the proxy normalized it to
    expect(quiet.arts).toEqual([
      {
        art: { cardName: "Agatha's Soul Cauldron", setCode: 'PWOE', cardNumber: '242s' },
        sent: { cardName: "Agatha's Soul Cauldron", setCode: 'WOE', cardNumber: '242' },
        only: true,
      },
    ])
  })

  it('DCK-17: several arts of one card each get a printing of their own, so each keeps its art', () => {
    const lands = ['M21|260', 'M21|261', 'M21|262'].map((p) => ({ cardName: 'Island', setCode: p.split('|')[0], cardNumber: p.split('|')[1] }))
    const twoArts: DeckValidationResult = {
      ready: true,
      missing: [
        { cardName: 'Island', setCode: 'SLD', cardNumber: '1', amount: 2, reason: 'OUTDATED_PRINTING', suggestions: lands },
        { cardName: 'Island', setCode: 'SLD', cardNumber: '2', amount: 3, reason: 'OUTDATED_PRINTING', suggestions: lands },
      ],
      mismatches: [],
    }
    const deck = {
      name: 't',
      cards: [
        { cardName: 'Island', setCode: 'SLD', cardNumber: '1', amount: 2 },
        { cardName: 'Island', setCode: 'SLD', cardNumber: '2', amount: 3 },
        // a printing the server has: sent as it is, and never given to a swapped one
        { cardName: 'Island', setCode: 'M21', cardNumber: '260', amount: 4 },
      ],
      sideboard: [],
    }
    const quiet = fixPrintingsQuietly(deck, twoArts)
    expect(quiet.deck.cards.map((c) => `${c.setCode}#${c.cardNumber}`)).toEqual(['M21#261', 'M21#262', 'M21#260'])
    expect(quiet.arts.map((a) => [`${a.art.setCode}#${a.art.cardNumber}`, `${a.sent.setCode}#${a.sent.cardNumber}`, a.only])).toEqual([
      ['SLD#1', 'M21#261', false],
      ['SLD#2', 'M21#262', false],
    ])
  })

  it('DCK-17: when the server has too few printings, a swapped one shares the first', () => {
    const one = [{ cardName: 'Island', setCode: 'M21', cardNumber: '260' }]
    const report2: DeckValidationResult = {
      ready: true,
      missing: [
        { cardName: 'Island', setCode: 'SLD', cardNumber: '1', amount: 2, reason: 'OUTDATED_PRINTING', suggestions: one },
        { cardName: 'Island', setCode: 'SLD', cardNumber: '2', amount: 3, reason: 'OUTDATED_PRINTING', suggestions: one },
      ],
      mismatches: [],
    }
    const deck = {
      name: 't',
      cards: [
        { cardName: 'Island', setCode: 'SLD', cardNumber: '1', amount: 2 },
        { cardName: 'Island', setCode: 'SLD', cardNumber: '2', amount: 3 },
      ],
      sideboard: [],
    }
    const quiet = fixPrintingsQuietly(deck, report2)
    expect(quiet.deck.cards.map((c) => c.cardNumber)).toEqual(['260', '260'])
    expect(quiet.arts.map((a) => a.only)).toEqual([false, false])
  })

  it('findFlaggedSameName localiza solo entradas marcadas', () => {
    const cards = [
      { cardName: 'Island', setCode: 'M21', cardNumber: '265' },
      { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' },
    ]
    const flagged = issueKeysFromReport(report)
    expect(findFlaggedSameName(cards, flagged, 'Rhystic Tutor')).toBe(1)
    expect(findFlaggedSameName(cards, flagged, 'Island')).toBe(-1)
    const okCards = [{ cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' }]
    expect(findFlaggedSameName(okCards, flagged, 'Rhystic Tutor')).toBe(-1)
  })
})

describe('fetchFormatIssues (official XMage DeckValidator)', () => {
  const send = vi.fn()
  const deck = { name: 'Test', cards: [], sideboard: [] }
  const formatReport = { ready: true, supported: true, valid: false, validator: 'Commander', errors: [{ type: 'DECK_SIZE', message: 'Must contain 100 cards' }] }

  beforeEach(() => {
    send.mockReset()
    setGateway({ send } as unknown as Gateway)
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    setGateway(null)
    vi.restoreAllMocks()
  })

  it('validates with the config.xml deck type matching the format', async () => {
    send.mockResolvedValue({ ok: true, data: formatReport })
    expect(await fetchFormatIssues(deck, 'Commander')).toBe(formatReport)
    expect(send).toHaveBeenCalledWith('validateDeckFormat', { deck, deckType: 'Variant Magic - Commander', gameType: undefined })
  })

  it('sends a commander deck the way a table would receive it (commander in commanders)', async () => {
    send.mockResolvedValue({ ok: true, data: formatReport })
    const commander = { cardName: 'Thrasios, Triton Hero', setCode: 'THB', cardNumber: '199', amount: 1 }
    await fetchFormatIssues({
      name: 'CMD',
      cards: [commander, { cardName: 'Island', setCode: 'M21', cardNumber: '265', amount: 38 }],
      sideboard: [{ cardName: 'Negate', setCode: 'M21', cardNumber: '57', amount: 2 }],
      commanderCard: commander,
    }, 'Commander')
    const [action, args] = send.mock.calls[0]
    expect(action).toBe('validateDeckFormat')
    // The proxy strips the commander from cards and moves it to the sideboard
    // (DeckJson.parse); it must arrive in `commanders` exactly like in joinTable.
    expect(args.deck.commanders).toEqual([commander])
    expect(args.deck.cards[0]).toEqual(commander)
    expect(args.deck.sideboard).toEqual([])
  })

  it('formats without an XMage validator keep the local validation', async () => {
    expect(xmageDeckTypeFor('Timeless')).toBeNull()
    expect(await fetchFormatIssues(deck, 'Timeless')).toBeNull()
    expect(send).not.toHaveBeenCalled()
  })

  it.each([
    ['card DB not ready', { ...formatReport, ready: false }],
    ['format without a validator in this release', { ...formatReport, supported: false }],
  ])('drops the report when: %s', async (_label, data) => {
    // Such a report says valid=false with no errors: using it would flag the deck as illegal for no reason.
    send.mockResolvedValue({ ok: true, data })
    expect(await fetchFormatIssues(deck, 'Modern')).toBeNull()
  })

  it('a proxy failure does not break the builder', async () => {
    send.mockRejectedValue(new Error('ws closed'))
    expect(await fetchFormatIssues(deck, 'Modern')).toBeNull()
    expect(console.warn).toHaveBeenCalled()
  })
})

describe('fetchDeckIssues sends the same prepared deck as fetchFormatIssues', () => {
  const send = vi.fn()

  beforeEach(() => {
    send.mockReset()
    send.mockResolvedValue({ ok: true, data: { ready: true, missing: [], mismatches: [] } })
    setGateway({ send } as unknown as Gateway)
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    setGateway(null)
    vi.restoreAllMocks()
  })

  it('fills the missing basic printings before validating', async () => {
    await fetchDeckIssues({ name: 'Test', cards: [{ cardName: 'Island', setCode: '', cardNumber: '', amount: 10 }], sideboard: [] }, 'Standard')
    const sent = send.mock.calls[0][1].deck
    expect(sent.cards[0].setCode).not.toBe('')
    expect(sent.cards[0].cardNumber).not.toBe('')
  })

  it('moves commanders into the commanders list for commander formats', async () => {
    const cmd = { cardName: 'Atraxa, Praetors\' Voice', setCode: 'C16', cardNumber: '189', amount: 1 }
    await fetchDeckIssues({ name: 'CMD', cards: [cmd], sideboard: [], commanderCard: cmd }, 'Commander')
    expect(send.mock.calls[0][1].deck.commanders).toEqual([cmd])
  })
})
