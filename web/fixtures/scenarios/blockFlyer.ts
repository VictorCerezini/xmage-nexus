/**
 * A flying commander attacks the human, who only has a creature that cannot block it.
 * Rebuilt from a real v0.7.10 game against a model seat's Queen Marchesa: the server lists every
 * untapped creature as a possible blocker, and picking one that cannot block the attacker
 * brings an EMPTY "Select attacker to block" GAME_TARGET (HumanPlayer.selectCombatGroup),
 * with required=false, options.targetZone and the blocker's name as secondMessage.
 */

import { TABLE } from '../table-names'
import { makeBaseScenario } from '../fake'
import type { FakeConn, Scenario } from '../fake'
import { commanderDamageRule, commanderInfoRule, makeCard, makeGameView, makePermanent, makePlayer } from '../../src/__fixtures__/gameViews'
import type { CardView, GameView, PermanentView } from '../../src/net/types'
import { GAME_ID, TABLE_ID, SIM_NAME, HUMAN_NAME, HUMAN_PLAYER_ID, SIM_PLAYER_ID } from '../humanGameConstants'

export const QUEEN_ID = '7186962f-5aa4-4012-8a59-1f74685155d1'
export const HERITAGE_ID = 'd9d4b0a2-6c1e-4f43-9a0e-3c3b6f0e1d9d'
export const ANJE_ID = 'cad1f5e8-2b8c-4f0e-8a51-6a1f0d1c7e2a'
export const ARCHON_ID = '448a2c77-9d1b-4a8e-b0a4-2f6b3c9e8d10'
export const TRIGGER_ID = 'f1e2d3c4-b5a6-4978-8a6b-5c4d3e2f1a0b'

export type BlockFlyerStage = 'precombat' | 'trigger' | 'blockers' | 'pick' | 'damage'

export function blockFlyerScenario(opts: { withTrigger?: boolean } = {}): Scenario & { stage: () => BlockFlyerStage } {
  const withTrigger = opts.withTrigger !== false
  let stage: BlockFlyerStage = 'precombat'
  let humanLife = 15

  const queen = (): PermanentView => {
    const attacking = stage !== 'precombat'
    return makePermanent({
      id: QUEEN_ID,
      name: 'Queen Marchesa',
      displayName: 'Queen Marchesa',
      expansionSetCode: 'CN2',
      cardNumber: '78',
      manaCost: ['{1}', '{R}', '{W}', '{B}'],
      manaValue: 4,
      power: '3',
      toughness: '3',
      cardTypes: ['Creature'],
      subTypes: ['Human', 'Assassin'],
      superTypes: ['Legendary'],
      color: { white: true, blue: false, black: true, red: true, green: false },
      rarity: 'MYTHIC',
      controllerId: SIM_PLAYER_ID,
      controllerName: SIM_NAME,
      ownerId: SIM_PLAYER_ID,
      ownerName: SIM_NAME,
      tapped: attacking,
      rules: [
        'Flying, deathtouch, haste',
        'When Queen Marchesa enters, you become the monarch.',
        'At the beginning of your upkeep, if an opponent is the monarch, create a 1/1 black Assassin creature token with deathtouch and haste.',
        ...(stage === 'blockers' || stage === 'pick' || stage === 'damage' ? ['Double strike'] : []),
        commanderInfoRule(2),
        commanderDamageRule(15, HUMAN_NAME),
      ],
    } as Partial<PermanentView> & { name: string })
  }

  const heritage = (): PermanentView =>
    makePermanent({
      id: HERITAGE_ID,
      name: "Duelist's Heritage",
      displayName: "Duelist's Heritage",
      expansionSetCode: 'CN2',
      cardNumber: '21',
      manaCost: ['{2}', '{W}'],
      manaValue: 3,
      cardTypes: ['Enchantment'],
      controllerId: SIM_PLAYER_ID,
      ownerId: SIM_PLAYER_ID,
      rules: ['Whenever one or more creatures attack, you may have target attacking creature gain double strike until end of turn.'],
    } as Partial<PermanentView> & { name: string })

  const anje = (): PermanentView =>
    makePermanent({
      id: ANJE_ID,
      name: 'Anje Falkenrath',
      displayName: 'Anje Falkenrath',
      expansionSetCode: 'C19',
      cardNumber: '37',
      manaCost: ['{1}', '{B}', '{R}'],
      manaValue: 3,
      power: '1',
      toughness: '3',
      cardTypes: ['Creature'],
      subTypes: ['Vampire'],
      superTypes: ['Legendary'],
      controlled: true,
      controllerId: HUMAN_PLAYER_ID,
      ownerId: HUMAN_PLAYER_ID,
      rules: ['Haste', '{T}, Discard a card: Draw a card.', commanderInfoRule(1)],
    } as Partial<PermanentView> & { name: string })

  const archon = (): PermanentView =>
    makePermanent({
      id: ARCHON_ID,
      name: 'Archon of Cruelty',
      displayName: 'Archon of Cruelty',
      expansionSetCode: 'MH2',
      cardNumber: '75',
      manaCost: ['{6}', '{B}', '{B}'],
      manaValue: 8,
      power: '6',
      toughness: '6',
      cardTypes: ['Creature'],
      subTypes: ['Archon'],
      controlled: true,
      controllerId: HUMAN_PLAYER_ID,
      ownerId: HUMAN_PLAYER_ID,
      rules: ['Flying', 'Archon of Cruelty can\'t block this turn.'],
    } as Partial<PermanentView> & { name: string })

  const trigger = (): CardView =>
    makeCard({
      id: TRIGGER_ID,
      name: 'Ability',
      mageObjectType: 'ABILITY_STACK_FROM_CARD',
      abilityType: 'TRIGGERED_NONMANA',
      controllerId: SIM_PLAYER_ID,
      targets: [QUEEN_ID],
      rules: ['Whenever one or more creatures attack, you may have target attacking creature gain double strike until end of turn.'],
      sourceCard: heritage(),
    } as Partial<CardView> & { name: string })

  const view = (): GameView => {
    const q = queen()
    const combat = stage === 'precombat'
      ? []
      : [{ attackers: { [QUEEN_ID]: q }, blockers: {}, isBlocked: false, defenderId: HUMAN_PLAYER_ID, defenderName: HUMAN_NAME }]
    const step = stage === 'precombat' ? 'PRECOMBAT_MAIN' : stage === 'trigger' ? 'DECLARE_ATTACKERS' : stage === 'damage' ? 'COMBAT_DAMAGE' : 'DECLARE_BLOCKERS'
    const human = makePlayer({
      playerId: HUMAN_PLAYER_ID,
      name: HUMAN_NAME,
      controlled: true,
      life: humanLife,
      handCount: 3,
      hasPriority: stage !== 'precombat',
      battlefield: { [ANJE_ID]: anje(), [ARCHON_ID]: archon() },
    })
    const sim = makePlayer({
      playerId: SIM_PLAYER_ID,
      name: SIM_NAME,
      life: 40,
      isActive: true,
      handCount: 4,
      battlefield: { [QUEEN_ID]: q, [HERITAGE_ID]: heritage() },
    })
    return makeGameView({
      players: [human, sim],
      myPlayerId: HUMAN_PLAYER_ID,
      phase: stage === 'precombat' ? 'PRECOMBAT_MAIN' : 'COMBAT',
      step,
      activePlayerId: SIM_PLAYER_ID,
      activePlayerName: SIM_NAME,
      priorityPlayerName: stage === 'precombat' ? SIM_NAME : HUMAN_NAME,
      turn: 15,
      stack: stage === 'trigger' ? { [TRIGGER_ID]: trigger() } : {},
      combat: combat as unknown as GameView['combat'],
    })
  }

  const selectBlockers = (conn: FakeConn) => {
    stage = 'blockers'
    conn.broadcast('GAME_UPDATE', { gameView: view() }, GAME_ID)
    conn.broadcast('GAME_SELECT', { message: 'Select blockers', options: { possibleBlockers: [ANJE_ID] }, gameView: view() }, GAME_ID)
  }

  const base = makeBaseScenario({
    tableId: TABLE_ID,
    tableName: TABLE.blockFlyer,
    gameId: GAME_ID,
    getGameView: view,
    onStartMatch: (conn) => {
      conn.broadcast('GAME_UPDATE', { gameView: view() }, GAME_ID)
      setTimeout(() => {
        if (withTrigger) {
          stage = 'trigger'
          conn.broadcast('GAME_UPDATE', { gameView: view() }, GAME_ID)
          conn.broadcast('GAME_SELECT', { message: 'Play instants and activated abilities', options: {}, gameView: view() }, GAME_ID)
        } else {
          selectBlockers(conn)
        }
      }, 1500)
    },
    onSendPlayerBoolean: (conn) => {
      if (stage === 'trigger' || stage === 'pick') {
        selectBlockers(conn)
      } else if (stage === 'blockers') {
        stage = 'damage'
        humanLife = 9
        conn.broadcast('GAME_UPDATE', { gameView: view() }, GAME_ID)
      }
    },
    onSendPlayerUUID: (conn, uuid) => {
      if (stage !== 'blockers' || uuid !== ANJE_ID) return
      stage = 'pick'
      conn.broadcast('GAME_TARGET', {
        message: 'Select attacker to block',
        targets: [],
        flag: false,
        options: { targetZone: 'BATTLEFIELD', secondMessage: 'Anje Falkenrath [cad]' },
        gameView: view(),
      }, GAME_ID)
    },
  })
  return Object.assign(base, { stage: () => stage })
}
