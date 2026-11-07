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
