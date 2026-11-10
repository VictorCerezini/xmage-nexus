import { beforeEach, describe, expect, it, vi } from 'vitest'
import { makeCard, makeGameView, makePermanent, makePlayer } from '../__fixtures__/gameViews'
import type { CardView, GameView } from '../net/types'
import { cardArtPreference, resetCardArtPreferences, setCardArtPreference } from './artPreferences'
import { cardKey, resetCardImageCache } from './cardImages'
import { learnModalFaces } from './modalFaces'
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
  restoreDefaultArt,
  setCopyArt,
  setDeckArt,
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

  it("CRD-11: with no art shared by the owner, their copy shows their deck's printing, never the viewer's own pick", () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view())
    // Bob's deck has GPT 128 (the server sends it): the viewer's pick is for the viewer's own copy
    expect(artFor(bobsTrostani)).toBeNull()
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    // the same on the stack, in a graveyard and in exile: Bob's cards are Bob's
    const stack = { s1: makeCard({ id: 's1', name: 'Trostani, Selesnya\'s Voice', controllerId: 'p-Bob', expansionSetCode: 'GPT', cardNumber: '128' }) }
    noteGameView(GAME, view({ extra: { stack } }))
    expect(cardKey(stack.s1)).toBe('GPT/128')
    const v = view()
    v.players = (v.players ?? []).map((p) =>
      p.name === 'Bob'
        ? { ...p, graveyard: { g1: makeCard({ id: 'g1', name: 'Trostani, Selesnya\'s Voice', expansionSetCode: 'GPT', cardNumber: '128' }) }, exile: { e1: makeCard({ id: 'e1', name: 'Trostani, Selesnya\'s Voice', expansionSetCode: 'GPT', cardNumber: '128' }) } }
        : p,
    )
    noteGameView(GAME, v)
    expect(cardKey({ id: 'g1', name: 'Trostani, Selesnya\'s Voice', expansionSetCode: 'GPT', cardNumber: '128' } as CardView)).toBe('GPT/128')
    expect(cardKey({ id: 'e1', name: 'Trostani, Selesnya\'s Voice', expansionSetCode: 'GPT', cardNumber: '128' } as CardView)).toBe('GPT/128')
  })

  it('CRD-12: an art picked for a spell // land card covers its land face on the battlefield, for me and for the others', () => {
    learnModalFaces("Emeria's Call", 'Emeria, Shattered Skyclave')
    setCardArtPreference("Emeria's Call", 'PZNR', '12')
    // played as the land: the server names the land face only
    const land = makePermanent({ id: 'm1', name: 'Emeria, Shattered Skyclave', controllerId: 'p-Alice', expansionSetCode: 'ZNR', cardNumber: '12', cardTypes: ['Land'] })
    const asAlice = withBattlefields('Alice', { m1: land }, {})
    expect(noteGameView(GAME, asAlice)).toContainEqual(["Emeria's Call", 'PZNR', '12'])
    expect(cardKey(land)).toBe('PZNR/12#back')

    // Bob's screen: Alice's line names the front face, and her land shows that printing's land face
    resetCardArtPreferences()
    const asBob = withBattlefields('Bob', { m1: land }, {})
    noteGameView('g-bob', asBob)
    expect(cardKey(land)).toBe('ZNR/12#back')
    const [line] = encodeSharedArt([["Emeria's Call", 'PZNR', '12']], false, () => 1)
    ingestSharedArt('g-bob', asBob, 'Alice', line)
    expect(cardKey(land)).toBe('PZNR/12#back')
  })

  it("CRD-11: a spectator sees each player's own art, not the spectator's picks", () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view({ me: 'Spectator' }))
    expect(cardKey(myTrostani)).toBe('GPT/128')
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
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

  it('CRD-6: the viewer can still change another player\'s card for themselves, for this game only', () => {
    setCardArtPreference('Trostani, Selesnya\'s Voice', 'PRM', '50000')
    noteGameView(GAME, view())
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '999']])
    expect(isOtherPlayersCard(bobsTrostani)).toBe(true)
    expect(isOtherPlayersCard(myTrostani)).toBe(false)

    setCopyArt(bobsTrostani, 'RVR', '216')
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    // the viewer's own copy and deck preference are untouched
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    // a later announcement from Bob does not undo the viewer's change
    bobSays([['Trostani, Selesnya\'s Voice', 'SLD', '1000']])
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    // next game: back to whatever the owner picks (Bob has not told it yet: his deck's printing)
    noteGameView('g-next', view())
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
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
    expect(decodeSharedArt(SHARED_ART_PREFIX + btoa('{"a":[[],["Island","SLD","1","M21","261","x"]]}'))?.a).toEqual([])
  })
})

describe('changing one copy during the game', () => {
  const sent = (lines: string[]) => lines.flatMap((l) => decodeSharedArt(l)!.a)
  const swamp = (id: string, controllerId: string) =>
    ({ id, name: 'Swamp', controllerId, expansionSetCode: 'M21', cardNumber: '272', cardTypes: ['Land'] }) as CardView
  /** Alice and Bob, each with two Swamps on the battlefield. */
  const swamps = (me: string) =>
    withBattlefields(me, { s1: swamp('s1', 'p-Alice'), s2: swamp('s2', 'p-Alice') }, { s3: swamp('s3', 'p-Bob'), s4: swamp('s4', 'p-Bob') })

  beforeEach(() => setDeckArt([]))

  it('CRD-10: changing one of my Swamps leaves my other Swamps and my deck alone, and only that one goes out', () => {
    linesToAnnounce(noteGameView(GAME, swamps('Alice')))
    setCopyArt(swamp('s1', 'p-Alice'), 'UNF', '238')
    expect(cardKey(swamp('s1', 'p-Alice'))).toBe('UNF/238')
    expect(cardKey(swamp('s2', 'p-Alice'))).toBe('M21/272')
    // the deck builder's art for the card is not touched
    expect(cardArtPreference('Swamp')).toBeNull()
    expect(sent(linesToAnnounce(noteGameView(GAME, swamps('Alice'))).lines)).toEqual([['Swamp', 'UNF', '238', 's1']])
    expect(linesToAnnounce(noteGameView(GAME, swamps('Alice'))).lines).toEqual([])
    expect(sent(linesToResend())).toEqual([['Swamp', 'UNF', '238', 's1']])
  })

  it("CRD-10: the other players show the change on that copy only, over the owner's art for the card", () => {
    noteGameView(GAME, swamps('Bob'))
    const [line] = encodeSharedArt([['Swamp', 'SLD', '1'], ['Swamp', 'UNF', '238', 's1']], false, () => 1)
    ingestSharedArt(GAME, swamps('Bob'), 'Alice', line)
    expect(cardKey(swamp('s1', 'p-Alice'))).toBe('UNF/238')
    expect(cardKey(swamp('s2', 'p-Alice'))).toBe('SLD/1')
    // Bob's own Swamps are his
    expect(cardKey(swamp('s3', 'p-Bob'))).toBe('M21/272')
  })

  it("CRD-10: a player's copy entry never changes another player's card", () => {
    noteGameView(GAME, swamps('Alice'))
    bobSays([['Swamp', 'UNF', '238', 's1'], ['Swamp', 'UNF', '239', 's3']])
    expect(cardKey(swamp('s1', 'p-Alice'))).toBe('M21/272')
    expect(cardKey(swamp('s3', 'p-Bob'))).toBe('UNF/239')
    expect(cardKey(swamp('s4', 'p-Bob'))).toBe('M21/272')
    // a copy the view places nowhere (a library search) is found by its id, before the viewer's own art
    setCardArtPreference('Swamp', 'PRM', '50000')
    bobSays([['Swamp', 'UNF', '242', 's9']])
    expect(artFor({ id: 's9', name: 'Swamp' } as CardView)).toEqual({ setCode: 'UNF', cardNumber: '242' })
    expect(artFor({ id: 's8', name: 'Swamp' } as CardView)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    // a spectator neither: Bob's entries for Alice's Swamps do not count
    noteGameView(GAME, swamps('Spectator'))
    bobSays([['Swamp', 'UNF', '243', 's2']])
    expect(artFor(swamp('s1', 'p-Alice'))).toBeNull()
    expect(artFor(swamp('s2', 'p-Alice'))).toBeNull()
  })

  it("CRD-10: changing one of another player's copies changes only that one, and only on this screen", () => {
    noteGameView(GAME, swamps('Alice'))
    bobSays([['Swamp', 'SLD', '1']])
    setCopyArt(swamp('s3', 'p-Bob'), 'UNF', '238')
    expect(cardKey(swamp('s3', 'p-Bob'))).toBe('UNF/238')
    expect(cardKey(swamp('s4', 'p-Bob'))).toBe('SLD/1')
    expect(cardKey(swamp('s1', 'p-Alice'))).toBe('M21/272')
    // a later choice from Bob does not undo it
    bobSays([['Swamp', 'UNF', '238', 's3'], ['Swamp', 'SLD', '2']])
    setCopyArt(swamp('s3', 'p-Bob'), 'UNF', '240')
    bobSays([['Swamp', 'UNF', '241', 's3']])
    expect(cardKey(swamp('s3', 'p-Bob'))).toBe('UNF/240')
    // nothing goes out for it
    expect(noteGameView(GAME, swamps('Alice'))).toEqual([])
  })

  it('CRD-10: a change made in hand is only told once the card is in a public zone, and it follows the card', () => {
    const solRing = makeCard({ id: 'h1', name: 'Sol Ring', controllerId: 'p-Alice' })
    noteGameView(GAME, view())
    setCopyArt(solRing, 'LEA', '270')
    expect(noteGameView(GAME, view())).toEqual([])
    const played = view()
    played.myHand = {}
    played.players = (played.players ?? []).map((p) => (p.name === 'Alice' ? { ...p, battlefield: { h1: makePermanent({ id: 'h1', name: 'Sol Ring', controllerId: 'p-Alice' }) } } : p))
    expect(noteGameView(GAME, played)).toEqual([['Sol Ring', 'LEA', '270', 'h1']])
    // in the graveyard it is the same card
    expect(cardKey({ id: 'h1', name: 'Sol Ring', expansionSetCode: 'C21', cardNumber: '263' } as CardView)).toBe('LEA/270')
  })

  it('CRD-10: the change lasts for this game only', () => {
    noteGameView(GAME, swamps('Alice'))
    setCopyArt(swamp('s1', 'p-Alice'), 'UNF', '238')
    setCopyArt(swamp('s3', 'p-Bob'), 'UNF', '239')
    noteGameView('g-next', swamps('Alice'))
    expect(cardKey(swamp('s1', 'p-Alice'))).toBe('M21/272')
    expect(cardKey(swamp('s3', 'p-Bob'))).toBe('M21/272')
  })

  it('CRD-10: picking the art the copy shows anyway leaves no change', () => {
    setCardArtPreference('Swamp', 'PRM', '50000')
    noteGameView(GAME, swamps('Alice'))
    setCopyArt(swamp('s1', 'p-Alice'), 'UNF', '238')
    setCopyArt(swamp('s1', 'p-Alice'), 'prm', '50000')
    expect(canRestoreDefaultArt(swamp('s1', 'p-Alice'))).toBe(false)
    // Bob shared nothing: his copy shows the printing the server sent, not the viewer's own pick
    setCopyArt(swamp('s3', 'p-Bob'), 'M21', '272')
    expect(canRestoreDefaultArt(swamp('s3', 'p-Bob'))).toBe(false)
    setCopyArt(swamp('s3', 'p-Bob'), 'PRM', '50000')
    expect(canRestoreDefaultArt(swamp('s3', 'p-Bob'))).toBe(true)
    // with no art chosen at all, the default is the printing the server sent
    resetCardArtPreferences()
    setCopyArt(swamp('s4', 'p-Bob'), 'M21', '272')
    expect(canRestoreDefaultArt(swamp('s4', 'p-Bob'))).toBe(false)
  })

  it('CRD-10: a copy entry and its take-back round-trip through a line', () => {
    const entries: SharedArtEntry[] = [['Swamp', 'UNF', '238', 's1'], ['Swamp', 's2'], ['Swamp'], ['Sol Ring', 'LEA', '270']]
    const [l] = encodeSharedArt(entries, false, () => 1)
    expect(decodeSharedArt(l)?.a).toEqual(entries)
  })

  it('CRD-10: the changes survive a page reload in the same game, not in another one', async () => {
    noteGameView(GAME, swamps('Alice'))
    setCopyArt(swamp('s1', 'p-Alice'), 'UNF', '238')
    setCopyArt(swamp('s3', 'p-Bob'), 'UNF', '239')
    vi.resetModules()
    const reloaded = await import('./sharedArt')
    reloaded.noteGameView(GAME, swamps('Alice'))
    expect(reloaded.artFor(swamp('s1', 'p-Alice'))).toEqual({ setCode: 'UNF', cardNumber: '238' })
    expect(reloaded.artFor(swamp('s3', 'p-Bob'))).toEqual({ setCode: 'UNF', cardNumber: '239' })
    expect(reloaded.artFor(swamp('s2', 'p-Alice'))).toBeNull()
    // a restore holds after a reload too
    reloaded.restoreDefaultArt(swamp('s1', 'p-Alice'))
    vi.resetModules()
    const again = await import('./sharedArt')
    again.noteGameView(GAME, swamps('Alice'))
    expect(again.artFor(swamp('s1', 'p-Alice'))).toBeNull()
    expect(again.artFor(swamp('s3', 'p-Bob'))).toEqual({ setCode: 'UNF', cardNumber: '239' })
    vi.resetModules()
    const elsewhere = await import('./sharedArt')
    elsewhere.noteGameView('g-other', swamps('Alice'))
    expect(elsewhere.artFor(swamp('s1', 'p-Alice'))).toBeNull()
  })
})

describe('restoring the default art', () => {
  const TROSTANI = "Trostani, Selesnya's Voice"
  const sent = (lines: string[]) => lines.flatMap((l) => decodeSharedArt(l)!.a)

  beforeEach(() => setDeckArt([]))

  it("CRD-8: another player's copy goes back to the art its owner chose", () => {
    noteGameView(GAME, view())
    bobSays([[TROSTANI, 'SLD', '999']])
    expect(canRestoreDefaultArt(bobsTrostani)).toBe(false)
    setCopyArt(bobsTrostani, 'RVR', '216')
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    expect(canRestoreDefaultArt(bobsTrostani)).toBe(true)

    restoreDefaultArt(bobsTrostani)
    expect(cardKey(bobsTrostani)).toBe('SLD/999')
    expect(canRestoreDefaultArt(bobsTrostani)).toBe(false)
    // what Bob picks later shows again
    bobSays([[TROSTANI, 'SLD', '1000']])
    expect(cardKey(bobsTrostani)).toBe('SLD/1000')
  })

  it("CRD-8: restoring another player's copy leaves the viewer's own pick alone, and shows what it showed before", () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    noteGameView(GAME, view())
    // Bob shared nothing: before the change his copy showed his deck's printing
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
    setCopyArt(bobsTrostani, 'RVR', '216')
    restoreDefaultArt(bobsTrostani)
    expect(cardKey(bobsTrostani)).toBe('GPT/128')
    expect(cardArtPreference(TROSTANI)).toEqual({ setCode: 'PRM', cardNumber: '50000' })
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    // nothing new to tell the others: the change never left this screen
    expect(noteGameView(GAME, view())).toEqual([[TROSTANI, 'PRM', '50000']])
  })

  it('CRD-8: my own copy goes back to my art pick, and the others drop the change', () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    linesToAnnounce(noteGameView(GAME, view()))
    expect(canRestoreDefaultArt(myTrostani)).toBe(false)
    setCopyArt(myTrostani, 'SLD', '999')
    setCopyArt(myTrostani, 'RVR', '216')
    expect(cardKey(myTrostani)).toBe('RVR/216')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'RVR', '216', 'a1']])
    expect(canRestoreDefaultArt(myTrostani)).toBe(true)

    restoreDefaultArt(myTrostani)
    expect(cardKey(myTrostani)).toBe('PRM/50000')
    expect(canRestoreDefaultArt(myTrostani)).toBe(false)
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'a1']])
    expect(linesToAnnounce(noteGameView(GAME, view())).lines).toEqual([])
    // a player who asks again gets the pick, not the change
    expect(sent(linesToResend())).toEqual([[TROSTANI, 'PRM', '50000']])
  })

  it('CRD-8: my own copy with no pick goes back to its deck printing', () => {
    setDeckArt([{ names: [TROSTANI], on: { setCode: 'TEST', cardNumber: '0' }, art: { setCode: 'SLD', cardNumber: '1500' }, only: true }])
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'SLD', '1500', 'TEST', '0']])
    setCopyArt(myTrostani, 'RVR', '216')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'RVR', '216', 'a1']])
    restoreDefaultArt(myTrostani)
    expect(cardKey(myTrostani)).toBe('SLD/1500')
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI, 'a1']])
    expect(sent(linesToResend())).toEqual([[TROSTANI, 'SLD', '1500', 'TEST', '0']])
  })

  it("CRD-8: when another player takes back a copy's change, that copy shows their art again", () => {
    noteGameView(GAME, view())
    bobSays([[TROSTANI, 'SLD', '1500', 'GPT', '128'], [TROSTANI, 'RVR', '216', 'b1']])
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    bobSays([[TROSTANI, 'b1']])
    expect(cardKey(bobsTrostani)).toBe('SLD/1500')
  })

  it('CRD-8: if taking back the change fails to post, the next update tries again', () => {
    linesToAnnounce(noteGameView(GAME, view()))
    setCopyArt(myTrostani, 'RVR', '216')
    linesToAnnounce(noteGameView(GAME, view()))
    restoreDefaultArt(myTrostani)
    const entries = noteGameView(GAME, view())
    expect(entries).toEqual([[TROSTANI, 'a1']])
    linesToAnnounce(entries)
    forgetAnnounced(entries, false)
    expect(noteGameView(GAME, view())).toEqual([[TROSTANI, 'a1']])
  })

  it('CRD-6: an art pick removed in the deck builder is taken back, and a take-back drops the pick on the others', () => {
    setCardArtPreference(TROSTANI, 'PRM', '50000')
    linesToAnnounce(noteGameView(GAME, view()))
    resetCardArtPreferences()
    expect(sent(linesToAnnounce(noteGameView(GAME, view())).lines)).toEqual([[TROSTANI]])
    expect(linesToResend()).toEqual([])

    bobSays([[TROSTANI, 'SLD', '1500', 'GPT', '128'], [TROSTANI, 'RVR', '216']])
    expect(cardKey(bobsTrostani)).toBe('RVR/216')
    bobSays([[TROSTANI]])
    expect(cardKey(bobsTrostani)).toBe('SLD/1500')
  })
})

describe('changing a token during the game', () => {
  const ZOMBIE = 'Zombie Token'
  const sent = (lines: string[]) => lines.flatMap((l) => decodeSharedArt(l)!.a)
  const zombie = (id: string, controllerId: string) =>
    ({ id, name: ZOMBIE, controllerId, expansionSetCode: 'M21', cardNumber: '0', isToken: true, mageObjectType: 'TOKEN', cardTypes: ['Creature'] }) as CardView
  /** Alice has two Zombie tokens, Bob one. */
  const zombies = (me: string) => withBattlefields(me, { z1: zombie('z1', 'p-Alice'), z2: zombie('z2', 'p-Alice') }, { z3: zombie('z3', 'p-Bob') })
  const TM21 = { setCode: 'TM21', cardNumber: '11' }

  beforeEach(() => setDeckArt([]))

  it('CRD-10: changing one of my Zombie tokens changes that token only, the others see it, and it can be put back', () => {
    linesToAnnounce(noteGameView(GAME, zombies('Alice')))
    const z1Before = cardKey(zombie('z1', 'p-Alice'))
    const z2Before = cardKey(zombie('z2', 'p-Alice'))
    expect(z1Before).not.toBe('TM21/11')
    setCopyArt(zombie('z1', 'p-Alice'), 'TM21', '11')
    expect(artFor(zombie('z1', 'p-Alice'))).toEqual(TM21)
    expect(cardKey(zombie('z1', 'p-Alice'))).toBe('TM21/11')
    expect(cardKey(zombie('z2', 'p-Alice'))).toBe(z2Before)
    expect(artFor(zombie('z3', 'p-Bob'))).toBeNull()
    expect(cardArtPreference(ZOMBIE)).toBeNull()
    // the change goes out for that token only, once; a player who asks again gets it too
    expect(sent(linesToAnnounce(noteGameView(GAME, zombies('Alice'))).lines)).toEqual([[ZOMBIE, 'TM21', '11', 'z1']])
    expect(linesToAnnounce(noteGameView(GAME, zombies('Alice'))).lines).toEqual([])
    expect(sent(linesToResend())).toEqual([[ZOMBIE, 'TM21', '11', 'z1']])

    expect(canRestoreDefaultArt(zombie('z1', 'p-Alice'))).toBe(true)
    restoreDefaultArt(zombie('z1', 'p-Alice'))
    expect(artFor(zombie('z1', 'p-Alice'))).toBeNull()
    expect(cardKey(zombie('z1', 'p-Alice'))).toBe(z1Before)
    expect(sent(linesToAnnounce(noteGameView(GAME, zombies('Alice'))).lines)).toEqual([[ZOMBIE, 'z1']])
    expect(linesToResend()).toEqual([])
  })

  it("CRD-10: the other players show a token's change on that token only, and drop it when it is taken back", () => {
    noteGameView(GAME, zombies('Bob'))
    const z2Before = cardKey(zombie('z2', 'p-Alice'))
    const [line] = encodeSharedArt([[ZOMBIE, 'TM21', '11', 'z1']], false, () => 1)
    ingestSharedArt(GAME, zombies('Bob'), 'Alice', line)
    expect(cardKey(zombie('z1', 'p-Alice'))).toBe('TM21/11')
    expect(cardKey(zombie('z2', 'p-Alice'))).toBe(z2Before)
    expect(artFor(zombie('z3', 'p-Bob'))).toBeNull()
    const [back] = encodeSharedArt([[ZOMBIE, 'z1']], false, () => 2)
    ingestSharedArt(GAME, zombies('Bob'), 'Alice', back)
    expect(artFor(zombie('z1', 'p-Alice'))).toBeNull()
  })

  it('CRD-10: a token never takes an art picked for its name, by the viewer or by its owner', () => {
    setCardArtPreference(ZOMBIE, 'PRM', '1')
    noteGameView(GAME, zombies('Alice'))
    bobSays([[ZOMBIE, 'SLD', '2']])
    expect(artFor(zombie('z1', 'p-Alice'))).toBeNull()
    expect(artFor(zombie('z3', 'p-Bob'))).toBeNull()
    // and nothing goes out for it
    expect(noteGameView(GAME, zombies('Alice'))).toEqual([])
  })

  it("CRD-10: changing another player's token changes it on this screen only", () => {
    noteGameView(GAME, zombies('Alice'))
    setCopyArt(zombie('z3', 'p-Bob'), 'TM21', '11')
    expect(cardKey(zombie('z3', 'p-Bob'))).toBe('TM21/11')
    expect(artFor(zombie('z1', 'p-Alice'))).toBeNull()
    expect(noteGameView(GAME, zombies('Alice'))).toEqual([])
    // its owner's change does not undo it
    bobSays([[ZOMBIE, 'TM21', '12', 'z3']])
    expect(cardKey(zombie('z3', 'p-Bob'))).toBe('TM21/11')
    restoreDefaultArt(zombie('z3', 'p-Bob'))
    expect(cardKey(zombie('z3', 'p-Bob'))).toBe('TM21/12')
  })

  it('CRD-10: a token turned face down neither shows nor tells its change', () => {
    linesToAnnounce(noteGameView(GAME, zombies('Alice')))
    setCopyArt(zombie('z1', 'p-Alice'), 'TM21', '11')
    linesToAnnounce(noteGameView(GAME, zombies('Alice')))
    const hidden = { ...zombie('z1', 'p-Alice'), faceDown: true } as CardView
    expect(artFor(hidden)).toBeNull()
    const game = withBattlefields('Alice', { z1: hidden }, {})
    expect(noteGameView(GAME, game)).toEqual([])
  })

  it('CRD-10: an ability that comes from a token is not taken for the token', () => {
    const fromToken = { id: 'ab1', name: ZOMBIE, controllerId: 'p-Alice', isAbility: true, mageObjectType: 'ABILITY_STACK_FROM_TOKEN' } as CardView
    const game = zombies('Alice')
    game.stack = { ab1: fromToken } as never
    linesToAnnounce(noteGameView(GAME, game))
    setCopyArt(fromToken, 'TM21', '11')
    expect(noteGameView(GAME, game)).toEqual([])
  })
})
