import { makeBaseScenario } from '../fake'
import { TABLE } from '../table-names'
import type { DeckValidationResult } from '../../src/net/types'

/**
 * Escenario del FixtureServer para deckvalidation.spec.ts: mesa del lobby y acción `validateDeck`.
 * - "Rhystic Tutor" (CY #77): impresión que el servidor no tiene, con sugerencia PCY #77
 *   (OUTDATED_PRINTING: la app la cambia sola, DCK-17).
 * - "Agatha's Soul Cauldron" (PWOE #242s): promo que el proxy normaliza a WOE #242s antes de
 *   validar, con la impresión cruda en `sources` (también se arregla sola).
 * - "Nonexistent Card" (ZZZ #1): carta que el servidor no puede jugar (UNIMPLEMENTED).
 * - "Banisher Priest" (C20 #77): mismatch sin sugerencia (el servidor cargaría Fiend Hunter).
 * `fixedDeck` es el mazo sin las cartas rechazadas, como lo devuelve el proxy.
 */

export const BAD_CARD = { cardName: 'Rhystic Tutor', setCode: 'CY', cardNumber: '77' }
export const GOOD_SUGGESTION = { cardName: 'Rhystic Tutor', setCode: 'PCY', cardNumber: '77' }
export const MISMATCH_CARD = { cardName: 'Banisher Priest', setCode: 'C20', cardNumber: '77' }
export const PROMO_CARD = { cardName: "Agatha's Soul Cauldron", setCode: 'PWOE', cardNumber: '242s' }
export const UNPLAYABLE_CARD = { cardName: 'Nonexistent Card', setCode: 'ZZZ', cardNumber: '1' }

interface DeckArg {
  name?: string
  cards?: Array<{ cardName: string; setCode: string; cardNumber: string; amount: number }>
  sideboard?: Array<{ cardName: string; setCode: string; cardNumber: string; amount: number }>
}

type Printing = { cardName: string; setCode: string; cardNumber: string }
const same = (c: Printing, p: Printing) => c.cardName === p.cardName && c.setCode === p.setCode && c.cardNumber === p.cardNumber

export function deckIssuesScenario(): ReturnType<typeof makeBaseScenario> {
  return makeBaseScenario({
    tableId: 'table-deck-issues-1',
    tableName: TABLE.deckIssues,
    gameId: 'game-deck-issues-1',
    seats: [
      { playerName: 'host-e2e', seatIndex: 0, playerType: 'HUMAN' },
      { playerName: '', seatIndex: 1, playerType: 'HUMAN' },
    ],
    onValidateDeck(input?: unknown): DeckValidationResult {
      const deck = input as DeckArg | undefined
      const cards = [...(deck?.cards ?? []), ...(deck?.sideboard ?? [])]
      const amountOf = (p: Printing) => cards.filter((c) => same(c, p)).reduce((acc, c) => acc + c.amount, 0)
      const missing: DeckValidationResult['missing'] = []
      if (amountOf(BAD_CARD) > 0) {
        missing.push({ ...BAD_CARD, amount: amountOf(BAD_CARD), reason: 'OUTDATED_PRINTING', suggestions: [GOOD_SUGGESTION] })
      }
      if (amountOf(PROMO_CARD) > 0) {
        missing.push({
          cardName: PROMO_CARD.cardName,
          setCode: 'WOE',
          cardNumber: '242s',
          amount: amountOf(PROMO_CARD),
          reason: 'OUTDATED_PRINTING',
          suggestions: [{ cardName: PROMO_CARD.cardName, setCode: 'WOE', cardNumber: '242' }],
          sources: [{ setCode: PROMO_CARD.setCode, cardNumber: PROMO_CARD.cardNumber }],
        })
      }
      if (amountOf(UNPLAYABLE_CARD) > 0) {
        missing.push({ ...UNPLAYABLE_CARD, amount: amountOf(UNPLAYABLE_CARD), reason: 'UNIMPLEMENTED' })
      }
      const mismatches: DeckValidationResult['mismatches'] = amountOf(MISMATCH_CARD) > 0
        ? [{ ...MISMATCH_CARD, amount: amountOf(MISMATCH_CARD), resolvedName: 'Fiend Hunter' }]
        : []
      if (missing.length === 0) return { ready: true, missing, mismatches }
      const rejected = (c: Printing) => same(c, BAD_CARD) || same(c, PROMO_CARD) || same(c, UNPLAYABLE_CARD)
      return {
        ready: true,
        missing,
        mismatches,
        fixedDeck: {
          name: deck?.name ?? '',
          cards: (deck?.cards ?? []).filter((c) => !rejected(c)),
          sideboard: (deck?.sideboard ?? []).filter((c) => !rejected(c)),
        },
      }
    },
  })
}
