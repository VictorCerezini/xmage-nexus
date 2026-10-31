import { useMemo } from 'react'
import type { PlayerView } from '../net/types'
import OpponentZone from './OpponentZone'
import PlayerZone from './PlayerZone'
import BoardShell, { BoardColDivider, BoardDivider } from './BoardShell'
import { useBoardPresenter, useBoardPlayers } from './useBoardPresenter'
import { useSeatStates, useSwitchedHand, type BoardProps } from './boardShared'
import { EMPTY_TARGET_ZONES } from './targetZones'
import DefeatedSeat from './DefeatedSeat'
import { opponentRevealedCards } from './revealedCards'
import { allBoardsRows } from './boardLayout'
import './TwoHeadedBoard.css'
import './AllBoardsBoard.css'

/** Every player's board at once, for tables too big for the 2×2 pod: two rows that seat the
 *  players around the table in turn order. */
export default function AllBoardsBoard({
  game,
  targetIds = [],
  chosenTargetIds = [],
  onTargetClick,
  targetZone = null,
  targetZones = EMPTY_TARGET_ZONES,
  playableIds = [],
  onPlayableClick,
  onCardHover,
  combatSelectable = [],
  combatMode = null,
  combatChosen = [],
  onCombatClick,
  attackingIds = [],
  blockingIds = [],
  crossZonePlayables = [],
  onPlayCrossZone,
}: BoardProps) {
  const { me, isSpectator } = useBoardPlayers(game, true)
  const switchedHand = useSwitchedHand(game)
  const { seatState, toggleSeat } = useSeatStates()
  const presenter = useBoardPresenter({
    game,
    targetIds,
    chosenTargetIds,
    targetZone,
    playableIds,
    combatSelectable,
    combatMode,
    combatChosen,
    crossZonePlayables,
    onTargetClick,
    onPlayableClick,
    onCombatClick,
    onCardHover,
  })
  const { handleCardHover, handleCardClick, targetIdSet, playableIdSet } = presenter
  const rows = useMemo(() => allBoardsRows(game?.players ?? [], me?.playerId), [game?.players, me?.playerId])

  const renderSeat = (player: PlayerView, mirrored: boolean) => {
    if (player === me) {
      return (
        <div className="pod-cell pod-cell--me" key={player.playerId} data-testid="all-boards-cell">
          <PlayerZone
            player={player}
            hand={game?.myHand ?? {}}
            onCardClick={handleCardClick}
            onHandCardClick={onPlayableClick}
            onCardHover={handleCardHover}
            targetIds={targetIdSet}
            targetZone={targetZone}
            targetZones={targetZones}
            playableIds={playableIdSet}
            combatSelectable={combatSelectable}
            combatMode={combatMode}
            combatChosen={combatChosen}
            attackingIds={attackingIds}
            blockingIds={blockingIds}
            crossZonePlayables={crossZonePlayables}
            onPlayCrossZone={onPlayCrossZone}
            helperEmblems={game?.myHelperEmblems}
            compactPod
            showHand={false}
          />
        </div>
      )
    }
    const state = seatState(player)
    const cellState = state === 'collapsed' ? ' seat-cell--out' : state === 'open' ? ' seat-cell--open' : ''
    return (
      <div className={`pod-cell${cellState}`} key={player.playerId} data-testid="all-boards-cell">
        {state !== 'alive' && <DefeatedSeat player={player} open={state === 'open'} onToggle={() => toggleSeat(player.playerId)} />}
        {state !== 'collapsed' && (
          <OpponentZone
            player={player}
            onCardClick={handleCardClick}
            onCardHover={handleCardHover}
            targetIds={targetIdSet}
            targetZone={targetZone}
            targetZones={targetZones}
            revealedCards={opponentRevealedCards(game, player)}
            playableIds={playableIdSet}
            combatSelectable={combatSelectable}
            combatMode={combatMode}
            combatChosen={combatChosen}
            attackingIds={attackingIds}
            blockingIds={blockingIds}
            mirrored={mirrored}
            compactPod
          />
        )}
      </div>
    )
  }

  const renderRow = (players: PlayerView[], mirrored: boolean) =>
    players.flatMap((player, i) => [
      ...(i > 0 ? [<BoardColDivider key={`divider-${player.playerId}`} />] : []),
      renderSeat(player, mirrored),
    ])

  return (
    <BoardShell
      className="pod-board all-boards-board"
      testId="all-boards-board"
      presenter={presenter}
      handBar={!isSpectator ? {
        cards: switchedHand ?? game?.myHand ?? {},
        onCardClick: onPlayableClick,
        onHover: (card, rect) => handleCardHover(card, rect, { fromHand: true }),
        playableIds: playableIdSet,
        targetIds: targetIdSet,
      } : null}
    >
      <div className="pod-row pod-row--top">{renderRow(rows.top, false)}</div>
      <BoardDivider />
      <div className="pod-row pod-row--bottom">{renderRow(rows.bottom, true)}</div>
    </BoardShell>
  )
}
