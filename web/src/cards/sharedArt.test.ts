import { beforeEach, describe, expect, it, vi } from 'vitest'
import { makeCard, makeGameView, makePermanent, makePlayer } from '../__fixtures__/gameViews'
import type { CardView, GameView } from '../net/types'
import { rememberDeckArt } from '../decks/deckArt'
import { cardArtPreference, resetCardArtPreferences, setCardArtPreference } from './artPreferences'
import { cardKey, resetCardImageCache } from './cardImages'
import {
  SHARED_ART_PREFIX,
  decodeSharedArt,
  encodeSharedArt,
  ingestSharedArt,
  isOtherPlayersCard,
  isSharedArtLine,
  linesToAnnounce,
  linesToResend,
  noteGameView,
  resetSharedArt,
  artFor,
  canRestoreDefaultArt,
  forgetAnnounced,
  forgetOwnArtDefaults,
  restoreDefaultArt,
  setDeckArt,
  setMatchArtOverride,
  setOwnArtInMatch,
  type SharedArtEntry,
} from './sharedArt'

const GAME = 'g-art'

/** Me (Alice) and Bob: each has a Trostani on the battlefield, Alice also one in hand. */
function view(opts: { me?: string; extra?: Partial<GameView> } = {}): GameView {
  const me = opts.me ?? 'Alice'
  return makeGameView({
    myPlayerId: me === 'Spectator' ? null : `p-${me}`,
    myHand: me === 'Alice' ? { h1: makeCard({ id: 'h1', name: 'Sol Ring' }) } : {},
    players: [
      makePlayer({
        playerId: 'p-Alice',
        name: 'Alice',
        controlled: me === 'Alice',
        battlefield: { a1: makePermanent({ id: 'a1', name: 'Trostani, Selesnya\'s Voice', controllerId: 'p-Alice' }) },
        graveyard: { a2: makeCard({ id: 'a2', name: 'Lightning Bolt' }) },
      }),
      makePlayer({
        playerId: 'p-Bob',
        name: 'Bob',
        controlled: me === 'Bob',
        battlefield: { b1: makePermanent({ id: 'b1', name: 'Trostani, Selesnya\'s Voice', controllerId: 'p-Bob' }) },
      }),
    ],
    ...opts.extra,
  })
}

/** The same table with other battlefields: Alice's and Bob's. */
function withBattlefields(me: string, alice: Record<string, CardView>, bob: Record<string, CardView>): GameView {
  const v = view({ me })
  v.players = (v.players ?? []).map((p) => ({ ...p, battlefield: (p.name === 'Alice' ? alice : bob) as never }))
  return v
}

const bobsTrostani = { id: 'b1', name: 'Trostani, Selesnya\'s Voice', controllerId: 'p-Bob', expansionSetCode: 'GPT', cardNumber: '128' } as CardView
const myTrostani = { id: 'a1', name: 'Trostani, Selesnya\'s Voice', controllerId: 'p-Alice', expansionSetCode: 'GPT', cardNumber: '128' } as CardView

function bobSays(entries: SharedArtEntry[], request = false): { resendRequested: boolean } {
  const [line] = encodeSharedArt(entries, request, () => 7)
  return ingestSharedArt(GAME, view(), 'Bob', line)
}

beforeEach(() => {
  resetSharedArt()
  resetCardArtPreferences()
  resetCardImageCache()
})

describe('shared art wire format', () => {
  it('CRD-6: a line round-trips names with accents and apostrophes', () => {
    const entries: SharedArtEntry[] = [['Trostani, Selesnya\'s Voice', 'GPT', '128'], ['Jötun Grunt', 'CSP', '8']]
    const lines = encodeSharedArt(entries, true, () => 1)
    expect(lines).toHaveLength(1)
    expect(lines[0].startsWith(SHARED_ART_PREFIX)).toBe(true)
    // nothing the server treats specially: no command prefix, no [[card links]], no spaces in the payload
    expect(lines[0]).toMatch(/^~nxart1 [A-Za-z0-9_-]+$/)
    expect(isSharedArtLine(lines[0])).toBe(true)
    expect(decodeSharedArt(lines[0])).toEqual({ n: 1, r: 1, a: entries })
  })

  it('CRD-6: many entries are split into lines the server will not truncate', () => {
    const entries: SharedArtEntry[] = Array.from({ length: 60 }, (_, i) => [`Some Long Card Name Number ${i}`, 'PLST', `ABC-${i}`])
    let n = 99990
    const lines = encodeSharedArt(entries, false, () => ++n)
    expect(lines.length).toBeGreaterThan(1)
    for (const l of lines) expect(l.length).toBeLessThanOrEqual(480)
    expect(lines.flatMap((l) => decodeSharedArt(l)!.a)).toEqual(entries)
    // each line has its own counter, so the server's "same message twice" filter never drops one
    expect(new Set(lines).size).toBe(lines.length)
  })

  it('CRD-6: ordinary chat and broken lines are not art lines', () => {
    expect(isSharedArtLine('gg')).toBe(false)
    expect(isSharedArtLine('nxart1 abc')).toBe(false)
    expect(decodeSharedArt('~nxart1 !!!not-base64')).toBeNull()
    expect(decodeSharedArt(SHARED_ART_PREFIX + btoa('{"a":[["", "X", "1"], ["Bolt", "LEA", "161"]]}'))).toEqual({ n: 0, a: [['Bolt', 'LEA', '161']] })
  })
})

describe('what this client announces', () => {
  it('CRD-6: only its own cards in public zones, never a card in hand', () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'GPT', '128')
    setCardArtPreference('Lightning Bolt', 'LEA', '161')
    setCardArtPreference('Sol Ring', 'LEA', '270')
    const fresh = noteGameView(GAME, view())
    expect(fresh).toEqual([
      ['Trostani, Selesnya\'s Voice', 'GPT', '128'],
      ['Lightning Bolt', 'LEA', '161'],
    ])
  })

  it('CRD-6: announces once, again only when the pick changes, and asks the others once per game', () => {
    setCardArtPreference('Lightning Bolt', 'LEA', '161')
    const first = linesToAnnounce(noteGameView(GAME, view()))
    expect(first.request).toBe(true)
    expect(decodeSharedArt(first.lines[0])).toMatchObject({ r: 1, a: [['Lightning Bolt', 'LEA', '161']] })

    expect(linesToAnnounce(noteGameView(GAME, view())).lines).toEqual([])

    setCardArtPreference('Lightning Bolt', '2XM', '130')
    const again = linesToAnnounce(noteGameView(GAME, view()))
    expect(again.request).toBe(false)
    expect(decodeSharedArt(again.lines[0])?.a).toEqual([['Lightning Bolt', '2XM', '130']])
    expect(linesToResend().flatMap((l) => decodeSharedArt(l)!.a)).toEqual([['Lightning Bolt', '2XM', '130']])
  })

  it('CRD-6: a card on the stack is announced by the player who cast it', () => {
    setCardArtPreference('Counterspell', 'LEA', '55')
    const stack = { s1: makeCard({ id: 's1', name: 'Counterspell', controllerId: 'p-Alice' }), s2: makeCard({ id: 's2', name: 'Shock', controllerId: 'p-Bob' }) }
    setCardArtPreference('Shock', 'M19', '156')
    expect(noteGameView(GAME, view({ extra: { stack } })).map((e) => e[0])).toEqual(['Counterspell'])
  })
})

describe('which art another player\'s copy shows', () => {
  it('CRD-6: the owner\'s pick, over the viewer\'s own preference; the viewer\'s copy keeps the viewer\'s pick', () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view())
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '999']])
    expect(cardKey(bobsTrostani)).toBe('SLD/999')
    expect(cardKey(myTrostani)).toBe('PRM/50000')
  })

  it('CRD-6: with no pick from the owner the viewer\'s own preference still applies', () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view())
    expect(cardKey(bobsTrostani)).toBe('PRM/50000')
  })

  it('CRD-6: a card without a controller is matched to the player whose zone holds it', () => {
    noteGameView(GAME, view())
    bobSays([['Lightning Bolt', 'LEA', '161']])
    // Alice's graveyard Bolt is not Bob's: Bob's pick does not touch it
    expect(artFor({ id: 'a2', name: 'Lightning Bolt' } as CardView)).toBeNull()
    // a card the view places nowhere: only one player shared an art for it, so that one is used
    expect(artFor({ id: 'zz', name: 'Lightning Bolt' } as CardView)).toEqual({ setCode: 'LEA', cardNumber: '161' })
  })

  it('CRD-6: lines from this client itself, from unknown senders, or for another game are ignored', () => {
    noteGameView(GAME, view())
    const [line] = encodeSharedArt([['Trostani, Selesnya\'s Voice', 'SLD', '999']], false, () => 1)
    ingestSharedArt(GAME, view(), 'Alice', line)
    ingestSharedArt(GAME, view(), 'Mallory', line)
    expect(artFor(bobsTrostani)).toBeNull()
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '999']])
    expect(artFor(bobsTrostani)).not.toBeNull()
    noteGameView('g-next', view())
    expect(artFor(bobsTrostani)).toBeNull()
  })

  it('CRD-6: reports when the sender asks for everyone\'s picks again', () => {
    noteGameView(GAME, view())
    expect(bobSays([], true)).toEqual({ resendRequested: true })
    expect(bobSays([['Shock', 'M19', '156']])).toEqual({ resendRequested: false })
  })

  it('CRD-6: tokens and face-down cards never take a shared art', () => {
    noteGameView(GAME, view())
    bobSays([['Saproling', 'RAV', '1'], ['Trostani, Selesnya\'s Voice', 'SLD', '999']])
    expect(artFor({ id: 't1', name: 'Saproling', isToken: true, controllerId: 'p-Bob' } as CardView)).toBeNull()
    expect(artFor({ ...bobsTrostani, faceDown: true } as CardView)).toBeNull()
  })

  it('CRD-6: the viewer can still change another player\'s card for themselves, for this match only', () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view())
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '999']])
    expect(isOtherPlayersCard(bobsTrostani)).toBe(true)
    expect(isOtherPlayersCard(myTrostani)).toBe(false)

    setMatchArtOverride(bobsTrostani, 'Trostani, Selesnya\'s Voice', 'RVR', '216')
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    // the viewer's own copy and deck preference are untouched
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    // a later announcement from Bob does not undo the viewer's change
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '1000']])
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    // next game: back to whatever the owner picks
    noteGameView('g-next', view())
    expect(cardKey(bobsTrostani)).toBe('PRM/50000')
  })

  it('CRD-6: a spectator treats every card as someone else\'s', () => {
    noteGameView(GAME, view({ me: 'Spectator' }))
    expect(isOtherPlayersCard(myTrostani)).toBe(true)
    expect(noteGameView(GAME, view({ me: 'Spectator' }))).toEqual([])
  })

  it("CRD-6: a card another player took keeps its owner's art, and its owner still announces it", () => {
    // Alice took Bob's Trostani: it sits on her battlefield, and the view names Bob as its owner
    const takenFromBob = { ...bobsTrostani, controllerId: 'p-Alice', nameOwner: 'Bob', controlledByOwner: false } as CardView
    setCardArtPreference("Trostani, Selesnya's Voice", 'PRM', '50000')
    const game = withBattlefields('Alice', { b1: takenFromBob }, {})
    // Alice does not announce a card she only controls
    expect(noteGameView(GAME, game).map((e) => e[0])).not.toContain("Trostani, Selesnya's Voice")
    bobSays([["Trostani, Selesnya's Voice", 'SLD', '999']])
    expect(cardKey(takenFromBob)).toBe('SLD/999')
    expect(isOtherPlayersCard(takenFromBob)).toBe(true)

    // Bob's own screen: the card is his, so he announces it although Alice controls it
    resetSharedArt()
    resetCardArtPreferences()
    setCardArtPreference("Trostani, Selesnya's Voice", 'SLD', '999')
    const asBob = withBattlefields('Bob', { b1: takenFromBob }, {})
    expect(noteGameView(GAME, asBob)).toEqual([["Trostani, Selesnya's Voice", 'SLD', '999']])
  })

  it('CRD-6: a transformed card keeps the art shared for its front face', () => {
    noteGameView(GAME, view())
    bobSays([['Delver of Secrets', 'ISD', '51']])
    const flipped = {
      id: 'b9', name: 'Insectile Aberration', controllerId: 'p-Bob', expansionSetCode: 'MID', cardNumber: '47',
      transformed: true, secondCardFace: { name: 'Delver of Secrets' },
    } as CardView
    expect(cardKey(flipped)).toBe('ISD/51#back')
  })

  it('CRD-6: a deck printing only goes on the copies the server sent as the printing it names', () => {
    noteGameView(GAME, view())
    bobSays([['Island', 'SLD', '1', 'M21', '261']])
    const island = (num: string) => ({ id: `i${num}`, name: 'Island', controllerId: 'p-Bob', expansionSetCode: 'M21', cardNumber: num }) as CardView
    expect(cardKey(island('261'))).toBe('SLD/1')
    expect(cardKey(island('260'))).toBe('M21/260')
    // an art pick from Bob covers every copy, and wins
    bobSays([['Island', 'UNF', '236']])
    expect(cardKey(island('261'))).toBe('UNF/236')
    expect(cardKey(island('260'))).toBe('UNF/236')
  })

  it("CRD-6: a card the view places nowhere (a library search) shows this viewer's own art before anyone else's", () => {
    setCardArtPreference("Trostani, Selesnya's Voice", 'PRM', '50000')
    noteGameView(GAME, view())
    bobSays([["Trostani, Selesnya's Voice", 'SLD', '999']])
    expect(cardKey({ id: 'lib-1', name: "Trostani, Selesnya's Voice", expansionSetCode: 'GPT', cardNumber: '128' } as CardView)).toBe('PRM/50000')
  })

  it("CRD-6: a spectator's request is answered, but a spectator's line never sets an art", () => {
    noteGameView(GAME, view())
    const [ask] = encodeSharedArt([["Trostani, Selesnya's Voice", 'SLD', '999']], true, () => 3)
    expect(ingestSharedArt(GAME, view(), 'Carol', ask)).toEqual({ resendRequested: true })
    expect(artFor(bobsTrostani)).toBeNull()
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
  })

  it('CRD-6: a deck printing entry round-trips through a line', () => {
    const entries: SharedArtEntry[] = [['Island', 'SLD', '1', 'M21', '261'], ['Sol Ring', 'LEA', '270']]
    const [l] = encodeSharedArt(entries, false, () => 1)
    expect(decodeSharedArt(l)?.a).toEqual(entries)
    // an entry of any other length is dropped
    expect(decodeSharedArt(SHARED_ART_PREFIX + btoa('{"a":[["Island","SLD","1","M21"]]}'))?.a).toEqual([])
  })
})

describe('restoring the default art', () => {
  const TROSTANI = "Trostani, Selesnya's Voice"
  const sent = (lines: string[]) => lines.flatMap((l) => decodeSharedArt(l)!.a)

  beforeEach(() => {
    forgetOwnArtDefaults()
    setDeckArt([])
  })

  it("CRD-8: another player's card goes back to the art its owner chose", () => {
    noteGameView(GAME, view())
    bobSays([[TROSTANI, 'SLD', '999']])
    expect(canRestoreDefaultArt(bobsTrostani, TROSTANI)).toBe(false)
    setMatchArtOverride(bobsTrostani, TROSTANI, 'RVR', '216')
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    expect(canRestoreDefaultArt(bobsTrostani, TROSTANI)).toBe(true)

    restoreDefaultArt(bobsTrostani, TROSTANI)
    expect(cardKey(bobsTrostani)).toBe('SLD/999')
    expect(canRestoreDefaultArt(bobsTrostani, TROSTANI)).toBe(false)
    // what Bob picks later shows again
    bobSays([[TROSTANI, 'SLD', '1000']])
    expect(cardKey(bobsTrostani)).toBe('SLD/1000')
  })

  it("CRD-8: restoring another player's card leaves the viewer's own pick alone, and shows what it showed before", () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    // Bob shared nothing: before the change his copy showed the viewer's own pick
    expect(cardKey(bobsTrostani)).toBe('PRM/50000')
    setMatchArtOverride(bobsTrostani, TROSTANI, 'RVR', '216')
    restoreDefaultArt(bobsTrostani, TROSTANI)
    expect(cardKey(bobsTrostani)).toBe('PRM/50000')
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    // nothing new to tell the others: the change never left this screen
    expect(noteGameView(GAME, view())).toEqual([[TROSTANI, 'PRM', '50000']])
  })

  it("CRD-8: a change made while the card's owner was unknown (a library search) is undone too", () => {
    noteGameView(GAME, view())
    bobSays([[TROSTANI, 'SLD', '999']])
    const searched = { id: 'lib-9', name: TROSTANI, expansionSetCode: 'GPT', cardNumber: '128' } as CardView
    setMatchArtOverride(searched, TROSTANI, 'RVR', '216')
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    expect(canRestoreDefaultArt(bobsTrostani, TROSTANI)).toBe(true)
    restoreDefaultArt(bobsTrostani, TROSTANI)
    expect(cardKey(bobsTrostani)).toBe('SLD/999')
    expect(canRestoreDefaultArt(searched, TROSTANI)).toBe(false)
  })

  it('CRD-8: my own card goes back to the art pick it had when the match started, and the others get it again', () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    linesToAnnounce(noteGameView(GAME, view()))
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(false)
    setOwnArtInMatch(TROSTANI, 'SLD', '999')
    // a second change: the default is still the pick from before the first one
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    expect(cardKey(myTrostani)).toBe('RVR/216')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'RVR', '216']])
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(true)

    restoreDefaultArt(myTrostani, TROSTANI)
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(false)
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'PRM', '50000']])
  })

  it('CRD-8: my own card with no pick before goes back to its deck printing, and the others drop my pick', () => {
    setDeckArt([{ names: [TROSTANI], on: { setCode: 'TEST', cardNumber: '0' }, art: { setCode: 'SLD', cardNumber: '1500' }, only: true }])
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'SLD', '1500', 'TEST', '0']])
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'RVR', '216']])

    restoreDefaultArt(myTrostani, TROSTANI)
    expect(cardArtPreference(TROSTANI)).toBeNull()
    expect(cardKey(myTrostani)).toBe('SLD/1500')
    // the pick is taken back; the deck printing went out before, so it is not sent again
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI]])
    expect(linesToAnnounce(noteGameView(GAME, view())).lines).toEqual([])
    // a player who asks again gets the deck printing, not the pick
    expect(sent(linesToResend())).toEqual([[TROSTANI, 'SLD', '1500', 'TEST', '0']])
  })

  it('CRD-8: with a pick only made during the match and no deck printing, the others go back to the printing the server sent', () => {
    linesToAnnounce(noteGameView(GAME, view()))
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    linesToAnnounce(noteGameView(GAME, view()))
    restoreDefaultArt(myTrostani, TROSTANI)
    expect(cardKey(myTrostani)).toBe('GPT/128')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI]])
    expect(linesToResend()).toEqual([])
  })

  it("CRD-8: when another player takes back a pick, their card shows their deck printing (or the server's) again", () => {
    noteGameView(GAME, view())
    bobSays([[TROSTANI, 'SLD', '1500', 'GPT', '128'], [TROSTANI, 'RVR', '216']])
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    bobSays([[TROSTANI]])
    expect(cardKey(bobsTrostani)).toBe('SLD/1500')
    bobSays([['Lightning Bolt', 'LEA', '161']])
    bobSays([['Lightning Bolt']])
    expect(artFor({ id: 'zz', name: 'Lightning Bolt' } as CardView)).toBeNull()
  })

  it('CRD-8: if taking back the pick fails to post, the next update tries again', () => {
    linesToAnnounce(noteGameView(GAME, view()))
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    linesToAnnounce(noteGameView(GAME, view()))
    restoreDefaultArt(myTrostani, TROSTANI)
    const entries = noteGameView(GAME, view())
    expect(entries).toEqual([[TROSTANI]])
    linesToAnnounce(entries)
    forgetAnnounced(entries, false)
    expect(noteGameView(GAME, view())).toEqual([[TROSTANI]])
  })

  it('CRD-8: picking the default art again leaves nothing to restore', () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    setOwnArtInMatch(TROSTANI, 'PRM', '50000')
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(false)
  })

  it('CRD-8: after a restore, the next change goes back to the art the card had just before it', () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    restoreDefaultArt(myTrostani, TROSTANI)
    // changed in the deck builder in the meantime
    setCardArtPreference(TROSTANI, 'SLD', '999')
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    restoreDefaultArt(myTrostani, TROSTANI)
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'SLD', cardNumber: '999' })
  })

  it('CRD-8: a take-back entry round-trips through a line', () => {
    const entries: SharedArtEntry[] = [[TROSTANI], ['Sol Ring', 'LEA', '270']]
    const [l] = encodeSharedArt(entries, false, () => 1)
    expect(decodeSharedArt(l)?.a).toEqual(entries)
  })

  it('CRD-8: both halves of a split card go back', () => {
    const fireIce = { id: 'a7', name: 'Fire // Ice', controllerId: 'p-Alice' } as CardView
    noteGameView(GAME, view())
    setCardArtPreference('Fire // Ice', 'MH2', '290')
    setOwnArtInMatch('Fire // Ice', 'APC', '128')
    expect(canRestoreDefaultArt(fireIce, 'Fire // Ice')).toBe(true)
    restoreDefaultArt(fireIce, 'Fire // Ice')
    expect(cardArtPreference('Fire')).toEqual({ setCode: 'MH2', cardNumber: '290' })
    expect(cardArtPreference('Ice')).toEqual({ setCode: 'MH2', cardNumber: '290' })
  })

  it('CRD-8: the undo lasts the whole match, and stops once the next deck is sent', async () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    // the next game of the match
    noteGameView('g-next', view())
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(true)
    // a new table: the art it starts with is the default now
    await rememberDeckArt([])
    expect(canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(false)
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'RVR', cardNumber: '216' })
  })

  it('CRD-8: the undo survives a page reload in the same tab', async () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    setOwnArtInMatch(TROSTANI, 'RVR', '216')
    vi.resetModules()
    const reloaded = await import('./sharedArt')
    const prefs = await import('./artPreferences')
    reloaded.noteGameView(GAME, view())
    expect(reloaded.canRestoreDefaultArt(myTrostani, TROSTANI)).toBe(true)
    reloaded.restoreDefaultArt(myTrostani, TROSTANI)
    expect(prefs.cardArtPreference(TROSTANI)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
  })
})
