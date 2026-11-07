import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { makeCard, makeGameView, makePermanent, makePlayer } from '../__fixtures__/gameViews'
import type { CardView, GameView } from '../net/types'
import { getState, setState } from './state'
import { handleMessage } from './store'
import { reset } from './gateway'
import * as cmds from '../net/commands'
import { resetCardArtPreferences, setCardArtPreference } from '../cards/artPreferences'
import { cardKey, resetCardImageCache } from '../cards/cardImages'
import { decodeSharedArt, encodeSharedArt, resetSharedArt } from '../cards/sharedArt'

vi.mock('../net/commands', () => ({
  setGateway: vi.fn(),
  getGateway: vi.fn(),
  joinGame: vi.fn(),
  getGameChatId: vi.fn().mockResolvedValue(undefined),
  joinChat: vi.fn(),
  sendChatMessage: vi.fn().mockResolvedValue({ ok: true }),
  sendPlayerAction: vi.fn().mockResolvedValue({ ok: true }),
  sendPlayerBoolean: vi.fn(),
  sendPlayerUUID: vi.fn(),
  sendManaPaymentMode: vi.fn(),
  updateManaConfirmPreference: vi.fn(),
  updatePreferences: vi.fn(),
}))

const GAME = 'g-art'
const CHAT = 'c-art'
const TROSTANI = 'Trostani, Selesnya\'s Voice'

function table(me: string | null, turn = 3): GameView {
  return makeGameView({
    turn,
    myPlayerId: me ? `p-${me}` : null,
    myHand: me === 'Alice' ? { h1: makeCard({ id: 'h1', name: 'Sol Ring' }) } : {},
    players: ['Alice', 'Bob'].map((name) =>
      makePlayer({
        playerId: `p-${name}`,
        name,
        controlled: name === me,
        battlefield: { [`${name}-t`]: makePermanent({ id: `${name}-t`, name: TROSTANI, controllerId: `p-${name}` }) },
      }),
    ),
  })
}

let msgId = 10
function update(view: GameView) {
  handleMessage({ type: 'event', method: 'GAME_UPDATE', messageId: ++msgId, objectId: GAME, data: view })
}

function chat(username: string, message: string) {
  handleMessage({
    type: 'event',
    method: 'CHATMESSAGE',
    messageId: ++msgId,
    objectId: CHAT,
    data: { chatId: CHAT, username, message, messageType: 'TALK' },
  } as never)
}

function battlefieldOf(name: string): Record<string, CardView> {
  const player = (getState().game?.players ?? []).find((p) => p.name === name)
  return (player?.battlefield ?? {}) as Record<string, CardView>
}

const sent = () => vi.mocked(cmds.sendChatMessage).mock.calls.map(([, text]) => text as string)
const flush = () => new Promise((r) => setTimeout(r, 0))

describe('shared card art through the game chat', () => {
  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    resetSharedArt()
    resetCardArtPreferences()
    resetCardImageCache()
    setState({ gameId: GAME, gameChatId: CHAT, phase: 'game' })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('CRD-6: a player posts the art picked for their card once it is on the battlefield, not what is in hand', async () => {
    setCardArtPreference(TROSTANI, 'SLD', '999')
    setCardArtPreference('Sol Ring', 'LEA', '270')
    update(table('Alice'))
    await flush()
    expect(sent()).toHaveLength(1)
    expect(cmds.sendChatMessage).toHaveBeenCalledWith(CHAT, expect.stringMatching(/^~nxart1 /))
    expect(decodeSharedArt(sent()[0])).toMatchObject({ r: 1, a: [[TROSTANI, 'SLD', '999']] })

    // the next view with nothing new posts nothing
    update(table('Alice', 4))
    await flush()
    expect(sent()).toHaveLength(1)
  })

  it('CRD-6: the line from another player is hidden from the chat and the log, and their card shows their art', () => {
    setCardArtPreference(TROSTANI, 'PRM', '1')
    update(table('Alice'))
    const before = { chat: getState().chatMessages.length, log: getState().log.length }
    const [line] = encodeSharedArt([[TROSTANI, 'SLD', '999']], false, () => 42)
    chat('Bob', line)
    expect(getState().chatMessages).toHaveLength(before.chat)
    expect(getState().log).toHaveLength(before.log)

    const bobs = battlefieldOf('Bob')
    const alices = battlefieldOf('Alice')
    expect(cardKey(bobs['Bob-t'])).toBe('SLD/999')
    expect(cardKey(alices['Alice-t'])).toBe('PRM/1')

    // ordinary talk still shows
    chat('Bob', 'gg')
    expect(getState().chatMessages).toHaveLength(before.chat + 1)
  })

  it('CRD-6: when another client asks, this player posts its picks again', async () => {
    vi.useFakeTimers()
    setCardArtPreference(TROSTANI, 'SLD', '999')
    update(table('Alice'))
    await vi.advanceTimersByTimeAsync(0)
    vi.mocked(cmds.sendChatMessage).mockClear()

    const [ask] = encodeSharedArt([], true, () => 5)
    chat('Bob', ask)
    expect(sent()).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(500)
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])?.a).toEqual([[TROSTANI, 'SLD', '999']])
  })

  it('CRD-6: a failed send is tried again on the next view', async () => {
    setCardArtPreference(TROSTANI, 'SLD', '999')
    vi.mocked(cmds.sendChatMessage).mockResolvedValueOnce({ ok: false } as never)
    update(table('Alice'))
    await flush()
    update(table('Alice', 4))
    await flush()
    expect(sent()).toHaveLength(2)
    expect(decodeSharedArt(sent()[1])).toMatchObject({ r: 1, a: [[TROSTANI, 'SLD', '999']] })
  })

  it('CRD-6: a spectator reads the lines and only posts one request, never an art', async () => {
    setCardArtPreference(TROSTANI, 'SLD', '999')
    update(table(null))
    await flush()
    update(table(null, 4))
    await flush()
    // one line asking the players to post again what they shared before it started watching
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])).toMatchObject({ r: 1, a: [] })
    const [ask] = encodeSharedArt([[TROSTANI, 'RVR', '216']], true, () => 5)
    chat('Bob', ask)
    await new Promise((r) => setTimeout(r, 500))
    expect(sent()).toHaveLength(1)
    const bobs = battlefieldOf('Bob')
    expect(cardKey(bobs['Bob-t'])).toBe('RVR/216')
  })

  it("CRD-6: a player answers a spectator's request", async () => {
    vi.useFakeTimers()
    setCardArtPreference(TROSTANI, 'SLD', '999')
    update(table('Alice'))
    await vi.advanceTimersByTimeAsync(0)
    vi.mocked(cmds.sendChatMessage).mockClear()

    const [ask] = encodeSharedArt([], true, () => 5)
    chat('Carol', ask)
    await vi.advanceTimersByTimeAsync(500)
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])?.a).toEqual([[TROSTANI, 'SLD', '999']])
  })
})

// The order a Commander game really starts in (seen in the logs of a real two-client game): the
// server's first view (GAME_INIT) is built before the game begins, so the command zones are still
// empty; the commanders first show up in the "who starts" and mulligan prompts (GAME_TARGET,
// GAME_ASK), which go to the deciding player instead of a full update (GAME_UPDATE).
describe('shared card art from the first prompts of a game', () => {
  const KRENKO = 'Krenko, Mob Boss'

  function commanderTable(me: string, withCommanders: boolean): GameView {
    return makeGameView({
      turn: 0,
      myPlayerId: `p-${me}`,
      players: ['Alice', 'Bob'].map((name) =>
        makePlayer({
          playerId: `p-${name}`,
          name,
          controlled: name === me,
          // shaped like the server's commander entry (fixtures/recorded/commander-zone.json)
          commandList: withCommanders
            ? [makeCard({ id: `${name}-cmd`, name: KRENKO, displayName: KRENKO, mageObjectType: 'COMMANDER', expansionSetCode: 'M13', cardNumber: '138', controllerId: null } as never)]
            : [],
        }),
      ),
    })
  }

  function event(method: string, data: unknown) {
    handleMessage({ type: 'event', method, messageId: ++msgId, objectId: GAME, data } as never)
  }

  function commanderOf(name: string): CardView {
    const player = (getState().game?.players ?? []).find((p) => p.name === name)
    return ((player?.commandList ?? []) as CardView[])[0]
  }

  beforeEach(() => {
    reset()
    vi.clearAllMocks()
    resetSharedArt()
    resetCardArtPreferences()
    resetCardImageCache()
    setState({ gameId: GAME, gameChatId: CHAT, phase: 'game' })
  })

  it('CRD-6: the deciding player posts its commander art from the "who starts" prompt, before any full update', async () => {
    setCardArtPreference(KRENKO, 'SLD', '1234')
    event('GAME_INIT', commanderTable('Alice', false))
    await flush()
    // the first view has nothing public yet: only the request
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])).toMatchObject({ r: 1, a: [] })

    event('GAME_TARGET', { gameView: commanderTable('Alice', true), message: 'Select a starting player', targets: [], flag: true })
    await flush()
    expect(sent()).toHaveLength(2)
    expect(decodeSharedArt(sent()[1])?.a).toEqual([[KRENKO, 'SLD', '1234']])
  })

  it('CRD-6: a mulligan prompt also announces the commander art', async () => {
    setCardArtPreference(KRENKO, 'SLD', '1234')
    event('GAME_INIT', commanderTable('Alice', false))
    event('GAME_ASK', { gameView: commanderTable('Alice', true), message: 'Mulligan?' })
    await flush()
    expect(sent().flatMap((l) => decodeSharedArt(l)?.a ?? [])).toEqual([[KRENKO, 'SLD', '1234']])
  })

  it("CRD-6: during the mulligan prompts the other player's commander shows that player's art, not this viewer's own", async () => {
    // both play the same commander; this viewer (Bob) picked another art for it
    setCardArtPreference(KRENKO, 'PLST', 'M13-138')
    event('GAME_INIT', commanderTable('Bob', false))
    const [line] = encodeSharedArt([[KRENKO, 'SLD', '1234']], false, () => 7)
    chat('Alice', line)
    event('GAME_ASK', { gameView: commanderTable('Bob', true), message: 'Mulligan?' })
    await flush()
    expect(cardKey(commanderOf('Alice'))).toBe('SLD/1234')
    expect(cardKey(commanderOf('Bob'))).toBe('PLST/M13-138')
  })

  it('CRD-6: at the start of a new game the art waits for the game chat, and goes out as soon as it is known', async () => {
    setCardArtPreference(KRENKO, 'SLD', '1234')
    let chatReady: (id: string) => void = () => {}
    const pending = new Promise<string>((r) => { chatReady = r })
    // asked on START_GAME, and again on GAME_INIT (not known yet then): leave that second answer
    // pending, so only the first one counts
    vi.mocked(cmds.getGameChatId).mockReturnValueOnce(pending as never).mockReturnValueOnce(new Promise(() => {}) as never)
    setState({ gameId: 'g-previous', gameChatId: 'c-previous' })
    event('START_GAME', { gameId: GAME, tableName: 'Table' })
    event('GAME_INIT', commanderTable('Alice', false))
    event('GAME_TARGET', { gameView: commanderTable('Alice', true), message: 'Select a starting player', targets: [], flag: true })
    await flush()
    expect(sent()).toHaveLength(0)

    chatReady(CHAT)
    await flush()
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])).toMatchObject({ r: 1, a: [[KRENKO, 'SLD', '1234']] })
  })

  it("CRD-6: in the next game of a match, the last game's cards are not posted when its chat becomes known", async () => {
    setCardArtPreference(TROSTANI, 'SLD', '999')
    // the last game's post failed, so its card still waits to be posted
    vi.mocked(cmds.sendChatMessage).mockResolvedValueOnce({ ok: false } as never)
    update(table('Alice'))
    await flush()
    expect(sent()).toHaveLength(1)

    let chatReady: (id: string) => void = () => {}
    vi.mocked(cmds.getGameChatId).mockReturnValueOnce(new Promise((r) => { chatReady = r }) as never)
    handleMessage({ type: 'event', method: 'START_GAME', messageId: ++msgId, objectId: 'g-next', data: { gameId: 'g-next' } } as never)
    chatReady('c-next')
    await flush()
    // nothing of the next game is known yet: the last game's battlefield is not posted to its chat
    expect(sent()).toHaveLength(1)
  })

  it('CRD-6: when the game chat becomes known after the first view, the art is posted without waiting for another view', async () => {
    setCardArtPreference(TROSTANI, 'SLD', '999')
    let chatReady: (id: string) => void = () => {}
    vi.mocked(cmds.getGameChatId).mockReturnValueOnce(new Promise((r) => { chatReady = r }))
    setState({ gameChatId: null })
    // a rejoin: the first view already has the card on the battlefield
    event('GAME_INIT', table('Alice', 5))
    await flush()
    expect(sent()).toHaveLength(0)

    chatReady(CHAT)
    await flush()
    expect(sent()).toHaveLength(1)
    expect(decodeSharedArt(sent()[0])).toMatchObject({ r: 1, a: [[TROSTANI, 'SLD', '999']] })
  })
})
