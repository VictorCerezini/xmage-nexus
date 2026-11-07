import { useSyncExternalStore } from 'react'
import type { CardView, GameView, PermanentView } from '../net/types'
import { cardArtPreference, type CardArtPreference } from './artPreferences'

/**
 * Shared card art: the art a player picked for a card (`artPreferences.ts`), or the printing their
 * deck had (`decks/deckArt.ts`), is shown to the other players too, for that player's copies.
 *
 * The XMage server only passes on printings it has in its own card list, and swaps any other
 * printing for one it knows. So the choice travels outside the game state: each player's client
 * posts it in the game chat as a hidden line, and the other clients read it and hide it. The image
 * itself always comes from Scryfall, like any other card image.
 *
 * A card is only announced once it is in a public zone (battlefield, stack, graveyard, exile,
 * command zone), so the line never tells anyone which cards are still in a hand or a library.
 *
 * Wire format: `~nxart1 <base64url JSON>`, at most `MAX_LINE` characters (the server truncates
 * chat lines at 500). JSON: `{ n, r?, a: [entry, ...] }`. An entry is `[name, setCode, cardNumber]`
 * for an art pick (every copy of the card), or `[name, setCode, cardNumber, onSet, onNumber]` for a
 * deck printing (only the copies the game shows as the printing `onSet`/`onNumber`, the one the
 * server got in its place). `n` is a counter (the server drops a line identical to the same user's
 * previous one), `r: 1` asks the other clients to post their choices again (sent when a client,
 * player or spectator, starts or rejoins a game).
 */

export const SHARED_ART_PREFIX = '~nxart1 '
const MAX_LINE = 480

export type SharedArtEntry =
  | [name: string, setCode: string, cardNumber: string]
  | [name: string, setCode: string, cardNumber: string, onSet: string, onNumber: string]

export interface SharedArtMessage {
  n: number
  r?: 1
  a: SharedArtEntry[]
}

const norm = (name: string) => name.trim().toLowerCase()
const printingOf = (setCode: string, cardNumber: string) => `${setCode.trim().toUpperCase()}|${cardNumber.trim().toLowerCase()}`

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(text: string): string {
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

export function isSharedArtLine(message: string | null | undefined): boolean {
  return typeof message === 'string' && message.trimStart().startsWith(SHARED_ART_PREFIX.trim())
}

function line(msg: SharedArtMessage): string {
  return SHARED_ART_PREFIX + toBase64Url(JSON.stringify(msg))
}

/**
 * Chat lines for these entries, each within the server's size limit. `nextN` gives each line its
 * own counter. With `request` the first line asks the others to post theirs again; a request with
 * no entries is a single line with an empty list.
 */
export function encodeSharedArt(entries: SharedArtEntry[], request: boolean, nextN: () => number): string[] {
  const lines: string[] = []
  let batch: SharedArtEntry[] = []
  let wantRequest = request
  const flush = () => {
    const msg: SharedArtMessage = { n: nextN(), a: batch }
    if (wantRequest) msg.r = 1
    lines.push(line(msg))
    batch = []
    wantRequest = false
  }
  for (const entry of entries) {
    const candidate = [...batch, entry]
    const probe: SharedArtMessage = { n: 0, a: candidate, ...(wantRequest ? { r: 1 as const } : {}) }
    // n grows by a few digits at most: leave room for it
    if (batch.length > 0 && line(probe).length + 8 > MAX_LINE) flush()
    batch.push(entry)
  }
  if (batch.length > 0 || wantRequest) flush()
  return lines
}

export function decodeSharedArt(message: string): SharedArtMessage | null {
  if (!isSharedArtLine(message)) return null
  try {
    const raw = JSON.parse(fromBase64Url(message.trim().slice(SHARED_ART_PREFIX.trim().length).trim())) as unknown
    if (!raw || typeof raw !== 'object') return null
    const r = raw as { n?: unknown; r?: unknown; a?: unknown }
    if (!Array.isArray(r.a)) return null
    const a: SharedArtEntry[] = []
    for (const e of r.a) {
      if (!Array.isArray(e) || !e.every((x) => typeof x === 'string' && x.trim() !== '')) continue
      if (e.length === 3) a.push([e[0], e[1], e[2]])
      else if (e.length === 5) a.push([e[0], e[1], e[2], e[3], e[4]])
    }
    return { n: typeof r.n === 'number' ? r.n : 0, ...(r.r === 1 ? { r: 1 as const } : {}), a }
  } catch {
    return null
  }
}

/** Where an entry is kept: the card name, plus the printing the game shows for a deck printing. */
function entryKey(e: SharedArtEntry): string {
  return e.length === 5 ? `${norm(e[0])}|${printingOf(e[3], e[4])}` : norm(e[0])
}

// --- per-game state -------------------------------------------------------------------------

let currentGameId: string | null = null
let myPlayerId: string | null = null
/** normalized player name → playerId (a stolen permanent names its owner, not their id). */
const playerIdByName = new Map<string, string>()
/** playerId → entry key (see `entryKey`) → the art that player chose. */
const byPlayer = new Map<string, Map<string, CardArtPreference>>()
/** cardId → id of the player whose zone holds it (for cards without a controller in the view). */
const zoneOwner = new Map<string, string>()
/** This viewer's own in-match art changes for other players' cards: holderId → name → art. */
const overrides = new Map<string, Map<string, CardArtPreference>>()
/** What this client already announced in the current game: entry key → entry. */
const announced = new Map<string, SharedArtEntry>()
let requestSent = false
// random start: after a page reload the first line must not repeat the last one sent before it
let counter = Math.floor(Math.random() * 1_000_000)

/** A printing of the deck this client last sent that the server did not have. */
export interface DeckArtEntry {
  /** every name the card can have in the game (each face, the server's spelling) */
  names: string[]
  /** the printing the server got in its place: the one the game shows */
  on: CardArtPreference
  /** the deck's own printing */
  art: CardArtPreference
  /** no other printing of this card was sent: any copy of it in the game is this one */
  only: boolean
}

/**
 * The deck printings the server did not have (it got another printing of the same card). Kept
 * across the games of a match, and across a page reload in this tab; replaced each time a deck is
 * sent.
 */
let deckArt: DeckArtEntry[] | null = null
const DECK_ART_KEY = 'mage-web-deck-art-v1'

function isPrinting(v: unknown): v is CardArtPreference {
  const p = v as Partial<CardArtPreference> | null
  return !!p && typeof p.setCode === 'string' && typeof p.cardNumber === 'string' && !!p.setCode && !!p.cardNumber
}

function loadedDeckArt(): DeckArtEntry[] {
  if (deckArt) return deckArt
  deckArt = []
  try {
    const raw = JSON.parse(sessionStorage.getItem(DECK_ART_KEY) ?? '[]') as unknown
    if (Array.isArray(raw)) {
      for (const e of raw as Partial<DeckArtEntry>[]) {
        if (!e || !Array.isArray(e.names) || !isPrinting(e.on) || !isPrinting(e.art)) continue
        const names = e.names.filter((n): n is string => typeof n === 'string' && !!n.trim())
        if (names.length > 0) deckArt.push({ names, on: e.on, art: e.art, only: e.only === true })
      }
    }
  } catch {
    deckArt = []
  }
  return deckArt
}

let version = 0
const listeners = new Set<() => void>()
function emit(): void {
  version++
  for (const l of listeners) l()
}

/** Re-renders the caller when another player's art arrives (cards read it through `cardKey`). */
export function useSharedArtVersion(): number {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => {
        listeners.delete(l)
      }
    },
    () => version,
    () => version,
  )
}

export function resetSharedArt(gameId: string | null = null): void {
  const hadArt = byPlayer.size > 0 || overrides.size > 0
  currentGameId = gameId
  myPlayerId = null
  playerIdByName.clear()
  byPlayer.clear()
  overrides.clear()
  zoneOwner.clear()
  announced.clear()
  requestSent = false
  if (hadArt) emit()
}

/** Remembers the art of the deck just sent (see `deckArt`). An empty list forgets the last one. */
export function setDeckArt(entries: DeckArtEntry[]): void {
  if (loadedDeckArt().length === 0 && entries.length === 0) return
  deckArt = entries.map((e) => ({ names: [...e.names], on: { ...e.on }, art: { ...e.art }, only: e.only }))
  try {
    if (deckArt.length > 0) sessionStorage.setItem(DECK_ART_KEY, JSON.stringify(deckArt))
    else sessionStorage.removeItem(DECK_ART_KEY)
  } catch {}
  emit()
}

/** The names to look a card up by: its own, and its front face while it is transformed. */
function lookupNames(card: CardView): string[] {
  const names = [card.name ?? '']
  if (card.transformed === true && card.secondCardFace?.name) names.push(card.secondCardFace.name)
  return [...new Set(names.map(norm).filter(Boolean))]
}

/** The keys a copy of a card can be found under in a player's entries (see `entryKey`). */
function lookupKeys(card: CardView): string[] {
  const set = card.expansionSetCode ?? ''
  const num = card.cardNumber ?? ''
  return lookupNames(card).flatMap((n) => (set.trim() && num.trim() ? [n, `${n}|${printingOf(set, num)}`] : [n]))
}

function artIn(map: Map<string, CardArtPreference> | undefined, card: CardView): CardArtPreference | null {
  if (!map) return null
  for (const key of lookupKeys(card)) {
    const art = map.get(key)
    if (art) return art
  }
  return null
}

/** This viewer's own art pick for the card. */
function viewerPick(card: CardView): CardArtPreference | null {
  for (const name of [card.name, card.transformed === true ? card.secondCardFace?.name : undefined]) {
    const pick = name ? cardArtPreference(name) : null
    if (pick) return pick
  }
  return null
}

/**
 * The deck printing for this copy: the entry whose card the server got as this very printing, else
 * the only art the deck had for the card (when the server shows yet another printing of it).
 */
function deckArtFor(card: CardView): CardArtPreference | null {
  const entries = loadedDeckArt()
  if (entries.length === 0) return null
  const names = lookupNames(card)
  const forCard = entries.filter((e) => e.names.some((n) => names.includes(norm(n))))
  if (forCard.length === 0) return null
  const shown = printingOf(card.expansionSetCode ?? '', card.cardNumber ?? '')
  const exact = forCard.find((e) => printingOf(e.on.setCode, e.on.cardNumber) === shown)
  return exact?.art ?? forCard.find((e) => e.only)?.art ?? null
}

/** The art this client chose for its own copy: its art pick, else the printing its deck had. */
function ownArtFor(card: CardView): CardArtPreference | null {
  return viewerPick(card) ?? (myPlayerId ? deckArtFor(card) : null)
}

function ensureGame(gameId: string): void {
  if (gameId !== currentGameId) resetSharedArt(gameId)
}

function cardsOf(map: unknown): CardView[] {
  if (!map) return []
  if (Array.isArray(map)) return map.filter((c): c is CardView => !!c && typeof c === 'object')
  if (typeof map === 'object') return Object.values(map as Record<string, CardView>).filter((c) => !!c && typeof c === 'object')
  return []
}

/** A card a player could have picked an art for (not a token, an ability, an emblem or a face-down card). */
function isArtCard(card: CardView): boolean {
  if (card.faceDown === true || card.isToken === true || card.isAbility === true) return false
  if (card.mageObjectType && /TOKEN|EMBLEM|DUNGEON|PLANE|ABILITY/i.test(card.mageObjectType)) return false
  return !!card.name?.trim()
}

/**
 * Whose copy a card is: its owner when the view names one (a permanent another player controls),
 * else its controller, else the player whose zone holds it.
 */
function holderOf(card: CardView): string | undefined {
  const ownerName = (card as PermanentView).nameOwner
  if (typeof ownerName === 'string' && ownerName.trim()) {
    const owner = playerIdByName.get(norm(ownerName))
    if (owner) return owner
  }
  return card.controllerId ?? (card.id ? zoneOwner.get(card.id) : undefined)
}

/**
 * Reads a game view: remembers who holds which card and which player is this client. Returns the
 * entries this client should announce now: its own cards in public zones with a chosen art (a pick,
 * or its deck printing) not announced yet, or announced with a different art.
 */
export function noteGameView(gameId: string, game: GameView): SharedArtEntry[] {
  ensureGame(gameId)
  zoneOwner.clear()
  playerIdByName.clear()
  const players = game.players ?? []
  const me = players.find((p) => p.controlled) ?? null
  myPlayerId = me?.playerId ?? null
  const publicCards: CardView[] = []
  for (const p of players) {
    if (p.name) playerIdByName.set(norm(p.name), p.playerId)
    const zones: CardView[] = [
      ...cardsOf(p.battlefield as Record<string, PermanentView>),
      ...cardsOf(p.graveyard),
      ...cardsOf(p.exile),
      ...cardsOf(p.commandList),
    ]
    for (const card of zones) {
      if (card.id) zoneOwner.set(card.id, p.playerId)
    }
    publicCards.push(...zones)
  }
  for (const card of cardsOf(game.myHand)) {
    if (card.id && myPlayerId) zoneOwner.set(card.id, myPlayerId)
  }
  publicCards.push(...cardsOf(game.stack))
  if (!me) return []
  // a card this player owns, wherever it is (another player may control it); not one it only controls
  const mine = publicCards.filter((c) => holderOf(c) === me.playerId)

  const fresh: SharedArtEntry[] = []
  const freshKeys = new Set<string>()
  for (const card of mine) {
    if (!isArtCard(card)) continue
    const deck = deckArtFor(card)
    for (const name of [card.name, card.secondCardFace?.name]) {
      if (!name?.trim()) continue
      const pick = cardArtPreference(name)
      let entry: SharedArtEntry | null = null
      if (pick) entry = [name.trim(), pick.setCode, pick.cardNumber]
      else if (deck && card.expansionSetCode?.trim() && card.cardNumber?.trim()) {
        entry = [name.trim(), deck.setCode, deck.cardNumber, card.expansionSetCode.trim(), card.cardNumber.trim()]
      }
      if (!entry) continue
      const key = entryKey(entry)
      const prev = announced.get(key)
      if (prev && prev[1] === entry[1] && prev[2] === entry[2]) continue
      if (freshKeys.has(key)) continue
      freshKeys.add(key)
      fresh.push(entry)
    }
  }
  return fresh
}

/** Lines to post now: new entries, plus the one-time request for the others' choices. */
export function linesToAnnounce(entries: SharedArtEntry[]): { lines: string[]; request: boolean } {
  const request = !requestSent
  if (entries.length === 0 && !request) return { lines: [], request: false }
  for (const e of entries) announced.set(entryKey(e), e)
  requestSent = true
  return { lines: encodeSharedArt(entries, request, () => ++counter), request }
}

/** Lines answering another client's request: everything this client announced in this game. */
export function linesToResend(): string[] {
  const entries = [...announced.values()]
  return entries.length === 0 ? [] : encodeSharedArt(entries, false, () => ++counter)
}

/** Undo `linesToAnnounce` bookkeeping after the chat send failed, so the next update retries. */
export function forgetAnnounced(entries: SharedArtEntry[], requestFailed: boolean): void {
  for (const e of entries) announced.delete(entryKey(e))
  if (requestFailed) requestSent = false
}

/**
 * Reads a hidden art line from the game chat. `senderName` is the chat user name, which is the
 * player's name. Returns whether the sender asked for everyone's choices again. Lines from this
 * client itself are ignored; a spectator's line is only a request (a spectator has no cards).
 */
export function ingestSharedArt(gameId: string, game: GameView | null, senderName: string, message: string): { resendRequested: boolean } {
  ensureGame(gameId)
  const msg = decodeSharedArt(message)
  if (!msg || !game) return { resendRequested: false }
  const sender = (game.players ?? []).find((p) => norm(p.name) === norm(senderName))
  if (sender?.controlled) return { resendRequested: false }
  if (!sender) return { resendRequested: msg.r === 1 }
  if (msg.a.length > 0) {
    const map = byPlayer.get(sender.playerId) ?? new Map<string, CardArtPreference>()
    for (const e of msg.a) map.set(entryKey(e), { setCode: e[1], cardNumber: e[2] })
    byPlayer.set(sender.playerId, map)
    emit()
  }
  return { resendRequested: msg.r === 1 }
}

/**
 * True when this copy belongs to another player (or the viewer is not playing). For those copies an
 * art change made during the match only lasts for this match and only on this screen; the owner's
 * own choice stays their default for everyone else.
 */
export function isOtherPlayersCard(card: CardView): boolean {
  const holder = holderOf(card)
  return !myPlayerId || !holder || holder !== myPlayerId
}

/** Records this viewer's in-match art change for another player's copy of a card. */
export function setMatchArtOverride(card: CardView, name: string, setCode: string, cardNumber: string): void {
  const holder = holderOf(card) ?? '*'
  const map = overrides.get(holder) ?? new Map<string, CardArtPreference>()
  map.set(norm(name), { setCode, cardNumber })
  overrides.set(holder, map)
  emit()
}

function uniqueArt(maps: Iterable<Map<string, CardArtPreference>>, card: CardView): CardArtPreference | null {
  let found: CardArtPreference | null = null
  for (const map of maps) {
    const art = artIn(map, card)
    if (!art) continue
    if (found && (found.setCode !== art.setCode || found.cardNumber !== art.cardNumber)) return null
    found = art
  }
  return found
}

/**
 * The art to show for a card in the game, or null for the printing the server sent.
 * - This client's own copy: its art pick, else the printing its deck had.
 * - Another player's copy: this viewer's in-match change, else the art that player chose, else this
 *   viewer's own pick for the card.
 * - A card whose holder the view does not tell (a library search, cards being looked at): this
 *   viewer's in-match change, else its own art (most such cards are its own), else the only art
 *   another player shared for that card.
 */
export function artFor(card: CardView): CardArtPreference | null {
  if (!card.name?.trim() || card.faceDown === true || card.isToken === true) return null
  if (!isArtCard(card)) return cardArtPreference(card.name)
  const holder = holderOf(card)
  if (holder && holder === myPlayerId) return ownArtFor(card)
  if (holder) {
    return artIn(overrides.get(holder), card) ?? artIn(overrides.get('*'), card) ?? artIn(byPlayer.get(holder), card) ?? viewerPick(card)
  }
  return artIn(overrides.get('*'), card) ?? ownArtFor(card) ?? uniqueArt(overrides.values(), card) ?? uniqueArt(byPlayer.values(), card)
}
