import { TABLE } from '../table-names'

import { makeBaseScenario } from '../fake'
import type { Scenario } from '../fake'
import { makePermanent } from '../../src/__fixtures__/gameViews'
import type { GameView, PlayerView } from '../../src/net/types'
import {
  GAME_ID, TABLE_ID, SIM_NAME, HUMAN_NAME, HUMAN_PLAYER_ID, SIM_PLAYER_ID,
} from '../humanGameConstants'

/** A big land row tapped by hand: each click on a land taps it (or untaps a tapped one). */
export function manualTapScenario(): Scenario {
  const land = (id: string, name: string, tapped: boolean) =>
    makePermanent({
      id,
      name,
      cardNumber: '262',
      expansionSetCode: 'TEST',
      cardTypes: ['Land'],
      superTypes: name.includes(' ') ? [] : ['Basic'],
      subTypes: name.includes(' ') ? [] : [name],
      tapped,
    })

  const lands: [string, string][] = [
    ...[1, 2, 3, 4, 5, 6].map((i): [string, string] => [`forest-${i}`, 'Forest']),
    ...[1, 2, 3, 4].map((i): [string, string] => [`mountain-${i}`, 'Mountain']),
    ...[1, 2, 3].map((i): [string, string] => [`swamp-${i}`, 'Swamp']),
    ['grove', 'Temple Garden'],
    ['tarn', 'Scalding Tarn'],
  ]
  const tapped = new Set<string>()

  const player = (id: string, name: string, controlled: boolean, battlefield: PlayerView['battlefield']) =>
    ({
      playerId: id,
      name,
      life: 20,
      controlled,
      isHuman: controlled,
      hasPriority: controlled,
      isActive: controlled,
      handCount: 0,
      libraryCount: 40,
      battlefield,
      manaPool: { white: 0, blue: 0, black: 0, red: 0, green: 0, colorless: 0 },
      graveyard: {},
      exile: {},
      sideboard: {},
      helperCards: {},
      topCard: null,
      wins: 0,
      winsNeeded: 2,
      counters: [],
      commandList: [],
      attachments: [],
      hasLeft: false,
      timerActive: false,
      statesSavedSize: 0,
      priorityTimeSavedTimeMs: 0,
    }) as unknown as PlayerView

  const getGameView = (): GameView => ({
    gameCycle: 1,
    turn: 3,
    step: 'PRECOMBAT_MAIN',
    phase: 'PRECOMBAT_MAIN',
    special: false,
    rollbackTurnsAllowed: false,
    totalErrorsCount: 0,
    totalEffectsCount: 0,
    priorityTime: 120,
    bufferTime: 0,
    activePlayerId: HUMAN_PLAYER_ID,
    activePlayerName: HUMAN_NAME,
    priorityPlayerName: HUMAN_NAME,
    players: [
      player(
        HUMAN_PLAYER_ID,
        HUMAN_NAME,
        true,
        Object.fromEntries(lands.map(([id, name]) => [id, land(id, name, tapped.has(id))])),
      ),
      player(SIM_PLAYER_ID, SIM_NAME, false, {}),
    ],
    myPlayerId: HUMAN_PLAYER_ID,
    myHand: {},
    myHelperEmblems: {},
    canPlayObjects: {
      objects: Object.fromEntries(
        lands.filter(([id]) => !tapped.has(id)).map(([id]) => [id, { basicCastAbilities: [{ id, value: 'Tap for mana' }] }]),
      ),
    },
    opponentHands: {},
    watchedHands: {},
    stack: {},
    exiles: [],
    revealed: [],
    lookedAt: [],
    companion: [],
    combat: [],
  })

  return makeBaseScenario({
    tableId: TABLE_ID,
    tableName: TABLE.manualTap,
    gameId: GAME_ID,
    getGameView,
    selectMessage: 'Tap lands by hand',
    onSendPlayerUUID: (conn, uuid) => {
      if (tapped.has(uuid)) tapped.delete(uuid)
      else tapped.add(uuid)
      conn.broadcast('GAME_UPDATE', { gameView: getGameView() }, GAME_ID)
    },
  })
}
