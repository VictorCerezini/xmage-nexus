import { validateDeck, validateDeckFormat } from '../net/commands'
import { prepareDeckForXMage } from './deckNormalize'
import type { DeckFormatValidationResult, DeckMismatchCard, DeckMissingCard, DeckValidationResult } from '../net/types'
import type { DeckFormat } from './types'

/** Cualquier mazo {name, cards, sideboard} con entradas DeckCard. */
export interface DeckLike {
  name: string
  cards: { cardName: string; setCode: string; cardNumber: string; amount: number }[]
  sideboard: { cardName: string; setCode: string; cardNumber: string; amount: number }[]
  commanders?: { cardName: string; setCode: string; cardNumber: string; amount: number }[]
  commanderCard?: { cardName: string; setCode: string; cardNumber: string; amount: number }
  partnerCard?: { cardName: string; setCode: string; cardNumber: string; amount: number }
}

/** Fold card names for comparison: collapses straight/curly apostrophes and whitespace. */
export function foldCardName(name: string): string {
  return (name ?? '').replace(/[’']/g, "'").trim().toLowerCase()
}

/** Synchronizes commanders array with commanderCard and partnerCard for XMage validation. */
export function syncCommandersForValidation<D extends DeckLike>(deck: D): D {
  if (!deck.commanderCard) return deck
  const commanders: { cardName: string; setCode: string; cardNumber: string; amount: number }[] = [deck.commanderCard]
  if (
    deck.partnerCard &&
    (deck.partnerCard.cardName !== deck.commanderCard.cardName ||
      deck.partnerCard.setCode !== deck.commanderCard.setCode ||
      deck.partnerCard.cardNumber !== deck.commanderCard.cardNumber)
  ) {
    commanders.push(deck.partnerCard)
  }
  return {
    ...deck,
    commanders,
  }
}

/**
 * Valida un mazo contra la BD de cartas del proxy (misma release y semántica
 * que el servidor XMage objetivo). Devuelve null si el proxy no pudo validar
 * (sin conexión o BD de cartas no disponible) — la validación es advisory.
 */
export async function fetchDeckIssues(deck: DeckLike, format?: DeckFormat): Promise<DeckValidationResult | null> {
  try {
    const deckType = format ? xmageDeckTypeFor(format) ?? undefined : undefined
    const report = await validateDeck(prepareDeckForXMage(deck, deckType) as never)
    return report && report.ready ? report : null
  } catch (e) {
    console.warn('[deckIssues] validateDeck no disponible:', e instanceof Error ? e.message : e)
    return null
  }
}

/** Clave estable de una entrada de mazo (cardName|setCode|cardNumber). */
export function deckIssueKey(cardName: string, setCode: string, cardNumber: string): string {
  return `${(cardName ?? '').replace(/[’']/g, "'").trim()}|${(setCode ?? '').trim().toUpperCase()}|${(cardNumber ?? '').trim()}`
}

/** Formato web -> deckType de config.xml del servidor (null: sin validador en XMage). */
export function xmageDeckTypeFor(format: DeckFormat): string | null {
  switch (format) {
    case 'Standard': return 'Constructed - Standard'
    case 'Modern': return 'Constructed - Modern'
    case 'Pioneer': return 'Constructed - Pioneer'
    case 'Legacy': return 'Constructed - Legacy'
    case 'Vintage': return 'Constructed - Vintage'
    case 'Pauper': return 'Constructed - Pauper'
    case 'Historic': return 'Constructed - Historic'
    case 'Freeform': return 'Constructed - Freeform'
    case 'Commander': return 'Variant Magic - Commander'
    case 'Brawl': return 'Variant Magic - Brawl'
    case 'Oathbreaker': return 'Variant Magic - Oathbreaker'
    case 'PennyDreadfulCommander': return 'Variant Magic - Penny Dreadful Commander'
    case 'EuropeanHighlander': return 'Constructed - European Highlander'
    case 'CanadianHighlander': return 'Constructed - Canadian Highlander'
    default: return null // Timeless y demás sin equivalente en XMage
  }
}

/**
 * Valida el mazo con el DeckValidator OFICIAL de XMage (misma release que el
 * servidor objetivo): tamaños, bans, comandante/partner, identidad de color.
 * El payload pasa por prepareDeckForXMage, así que el validador ve EXACTAMENTE
 * lo que se enviaría al crear/unirse a una mesa (comandantes en `commanders`,
 * no en el main). Devuelve null si el proxy no pudo validar (sin conexión, BD
 * no disponible o formato sin validador) — advisory, nunca bloqueante.
 */
export async function fetchFormatIssues(deck: DeckLike, format: DeckFormat): Promise<DeckFormatValidationResult | null> {
  try {
    const deckType = xmageDeckTypeFor(format)
    if (!deckType) return null
    const report = await validateDeckFormat(prepareDeckForXMage(deck, deckType), deckType)
    return report && report.ready && report.supported ? report : null
  } catch (e) {
    console.warn('[deckIssues] validateDeckFormat no disponible:', e instanceof Error ? e.message : e)
    return null
  }
}

/** Set de claves con problemas (rechazadas o cargadas como otra carta). */
export function issueKeysFromReport(report: DeckValidationResult): Set<string> {
  const keys = new Set<string>()
  for (const c of [...report.missing, ...report.mismatches]) {
    for (const p of issuePrintings(c)) keys.add(deckIssueKey(p.cardName, p.setCode, p.cardNumber))
  }
  return keys
}

export interface DeckPrinting {
  cardName: string
  setCode: string
  cardNumber: string
}

type DeckIssue = DeckMissingCard | DeckMismatchCard

/**
 * Every deck printing a report entry stands for: the (normalized) printing the
 * proxy validated plus the raw printings the client actually stored.
 */
export function issuePrintings(issue: DeckIssue): DeckPrinting[] {
  const out: DeckPrinting[] = [{ cardName: issue.cardName, setCode: issue.setCode, cardNumber: issue.cardNumber }]
  for (const s of issue.sources ?? []) {
    if (out.some((p) => p.setCode === s.setCode && p.cardNumber === s.cardNumber)) continue
    out.push({ cardName: issue.cardName, setCode: s.setCode, cardNumber: s.cardNumber })
  }
  return out
}

export interface DeckFix {
  from: DeckPrinting
  to: DeckPrinting
}

/** Fixes that move every deck printing behind `issue` to `to`. */
export function fixesForIssue(issue: DeckIssue, to: DeckPrinting): DeckFix[] {
  const target = { cardName: to.cardName, setCode: to.setCode, cardNumber: to.cardNumber }
  return issuePrintings(issue).map((from) => ({ from, to: target }))
}

/** Checks if two card printings refer to the same printing (case/apostrophe-insensitive). */
export function isSamePrinting(a: DeckPrinting, b: DeckPrinting): boolean {
  return (
    foldCardName(a.cardName) === foldCardName(b.cardName) &&
    a.setCode.trim().toUpperCase() === b.setCode.trim().toUpperCase() &&
    a.cardNumber.trim().toLowerCase() === b.cardNumber.trim().toLowerCase()
  )
}

/** Reemplaza la impresión `from` por `to` en main, sideboard, commanders y comandante designado. */
export function applySuggestion<D extends { cards: DeckPrinting[]; sideboard: DeckPrinting[] }>(
  deck: D,
  from: DeckPrinting,
  to: DeckPrinting,
): D {
  const samePrinting = (c: DeckPrinting) => isSamePrinting(c, from)
  const swap = (cards: DeckPrinting[]) =>
    cards.map((c) =>
      samePrinting(c)
        ? { ...c, cardName: to.cardName, setCode: to.setCode, cardNumber: to.cardNumber }
        : c,
    )
  const next: D = { ...deck, cards: swap(deck.cards), sideboard: swap(deck.sideboard) }
  if (Array.isArray((deck as Record<string, unknown>).commanders)) {
    ;(next as Record<string, unknown>).commanders = swap(
      (deck as Record<string, unknown>).commanders as DeckPrinting[],
    )
  }
  for (const field of ['commanderCard', 'partnerCard', 'coverCard'] as const) {
    const cur = (next as Record<string, unknown>)[field] as DeckPrinting | undefined | null
    if (cur && samePrinting(cur)) {
      ;(next as Record<string, unknown>)[field] = {
        ...cur,
        cardName: to.cardName,
        setCode: to.setCode,
        cardNumber: to.cardNumber,
      }
    }
  }
  return next
}

/** Applies every `from → to` replacement in order (one-click auto-resolve). */
export function applySuggestions<D extends { cards: DeckPrinting[]; sideboard: DeckPrinting[] }>(
  deck: D,
  fixes: DeckFix[],
): D {
  return fixes.reduce((acc, f) => applySuggestion(acc, f.from, f.to), deck)
}

/**
 * The server lacks this printing but has the same card in another one. The app swaps the printing by
 * itself when the deck is sent (`fixPrintingsQuietly`) and keeps the deck's printing as the card's
 * art, so this is never shown to the player. Only a card the server cannot play at all is.
 */
export function isPrintingOnlyIssue(issue: DeckIssue): boolean {
  if (!issue.suggestions || issue.suggestions.length === 0) return false
  return 'resolvedName' in issue || issue.reason === 'OUTDATED_PRINTING'
}

/** The report without the problems the app fixes by itself: what is left needs the player. */
export function withoutPrintingOnlyIssues(report: DeckValidationResult): DeckValidationResult {
  return {
    ...report,
    missing: report.missing.filter((c) => !isPrintingOnlyIssue(c)),
    mismatches: report.mismatches.filter((c) => !isPrintingOnlyIssue(c)),
  }
}

/** A printing the server lacked, the one sent in its place, and whether the art can stand alone. */
export interface DeckArtSwap {
  /** the deck's own printing: the art the player chose */
  art: DeckPrinting
  /** the printing sent to the server in its place: the one the game will show */
  sent: DeckPrinting
  /** no other printing of this card goes to the server, so every copy of it in the game is this one */
  only: boolean
}

export interface QuietFix<D> {
  /** the deck with every printing-only problem moved to a printing the server has */
  deck: D
  /** each swapped printing: the art the player chose, and the printing the server got instead */
  arts: DeckArtSwap[]
  /** how many problems were fixed */
  fixed: number
}

const printingKey = (p: { setCode: string; cardNumber: string }) => `${p.setCode.trim().toUpperCase()}|${p.cardNumber.trim().toLowerCase()}`

/**
 * Moves every card the server only lacks in this printing to one it has. Each swapped printing gets
 * a printing of its own where the server has enough (not one the deck already sends, nor one given
 * to another swapped printing of the same card), so in the game every copy can still show the art
 * its deck entry had (five Islands with five arts stay five arts).
 */
export function fixPrintingsQuietly<D extends DeckLike>(deck: D, report: DeckValidationResult): QuietFix<D> {
  const issues = [...report.missing, ...report.mismatches].filter(isPrintingOnlyIssue)
  if (issues.length === 0) return { deck, arts: [], fixed: 0 }
  const entries = [
    ...deck.cards,
    ...deck.sideboard,
    ...(deck.commanders ?? []),
    ...(deck.commanderCard ? [deck.commanderCard] : []),
    ...(deck.partnerCard ? [deck.partnerCard] : []),
  ]
  const issueKeys = new Set(issues.flatMap(issuePrintings).map((p) => `${foldCardName(p.cardName)}|${printingKey(p)}`))
  const kept = entries.filter((c) => !issueKeys.has(`${foldCardName(c.cardName)}|${printingKey(c)}`))
  const fixes: DeckFix[] = []
  const swaps: { name: string; art: DeckPrinting | null; sent: DeckPrinting }[] = []
  for (const issue of issues) {
    const name = foldCardName(issue.cardName)
    const taken = new Set([
      ...kept.filter((c) => foldCardName(c.cardName) === name).map(printingKey),
      ...swaps.filter((s) => s.name === name).map((s) => printingKey(s.sent)),
    ])
    const suggestions = issue.suggestions!
    const to = suggestions.find((s) => !taken.has(printingKey(s))) ?? suggestions[0]
    fixes.push(...fixesForIssue(issue, to))
    const printings = issuePrintings(issue)
    const stored = entries.find((c) => c.setCode.trim() && c.cardNumber.trim() && printings.some((p) => isSamePrinting(c, p)))
    swaps.push({
      name,
      art: stored ? { cardName: stored.cardName.trim(), setCode: stored.setCode.trim(), cardNumber: stored.cardNumber.trim() } : null,
      sent: { cardName: to.cardName, setCode: to.setCode, cardNumber: to.cardNumber },
    })
  }
  const arts: DeckArtSwap[] = []
  for (const s of swaps) {
    if (!s.art) continue
    const art = s.art
    const sameCard = swaps.filter((o) => o.name === s.name)
    const only =
      !kept.some((c) => foldCardName(c.cardName) === s.name) &&
      sameCard.every((o) => !!o.art && printingKey(o.art) === printingKey(art))
    arts.push({ art, sent: s.sent, only })
  }
  return { deck: applySuggestions(deck, fixes), arts, fixed: issues.length }
}

/** Índice de la entrada con ese nombre marcada como problemática por el servidor (-1 si no hay). */
export function findFlaggedSameName(
  cards: DeckPrinting[],
  flaggedKeys: Set<string>,
  cardName: string,
): number {
  return cards.findIndex(
    (c) =>
      foldCardName(c.cardName) === foldCardName(cardName) &&
      flaggedKeys.has(deckIssueKey(c.cardName, c.setCode, c.cardNumber)),
  )
}
