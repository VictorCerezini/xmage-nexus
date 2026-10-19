# Engine→view triage (engine fields that do not travel in the DTO)

Canonical registry: `web/src/state/engineViewRegistry.ts`. Guard: `web/src/state/engineViewCoverage.test.ts`
(vitest: `cd web && npx vitest run src/state/engineViewCoverage.test.ts`) plus the baseline
`web/fixtures/engine-view-gap.baseline.json`, computed by `scripts/engine-view-schema.mjs` from the
checked-out fork.

Working rule: **a remote client against upstream beta.xmage.today, with no new view patches in the fork**.
If a field exists only in `mage.game.*` and is not copied into the `mage.view.*` DTO, the client cannot show it;
the decision records whether that is an accepted loss, a duplicate of another field that does travel, or
internal bookkeeping. The one exception is the LLM seat engine patch (see "Notable cases").

## Legend

| Decision | Meaning |
| --- | --- |
| `rendered` | The data arrives another way in the DTO (for example `rules`, `cardIcons`, `designationNames`) and the UI already draws it. Needs a `path:line` `ref` to the code that renders it. |
| `accepted` | A known, accepted limitation (no channel in the DTO). Needs a `causa`; may carry a `ref` when there is a partial path. |
| `bookkeeping` | Internal engine state (ledgers, flags, counters, configuration) with no rendering value. Needs a `causa`. |
| `parser-artifact` | Not a real field of the class: the field parser walks inner classes (`MarkedDamageInfo`, `ApprovingObjectResult`, `RollDieResult`). Needs a `causa`. |
| `duplicado` | The same data already travels in another DTO field. Needs a `causa` naming that field. |

Impact: `alto` (visible state with no channel), `medio` (partial channel), `bajo` (rules and edge cases), `nulo` (no loss).
The decision and impact values and the `causa` key are the registry's identifiers, so they stay as they are.

Current split: `accepted` 25 · `parser-artifact` 8 · `bookkeeping` 56 · `duplicado` 40 · `rendered` 11, 140 fields in all
(PermanentView 61, PlayerView 52, GameView 27).

## Decisions per field

| View | Field | Impact | Decision | Cause | Ref |
| --- | --- | --- | --- | --- | --- |
| PermanentView | `abilities` | nulo | accepted | engine Ability objects; the text and icons arrive in rules/cardIcons | — |
| PermanentView | `addCounters` | nulo | parser-artifact | a field of the inner class MarkedDamageInfo (PermanentImpl.java:55-66), not of PermanentImpl | — |
| PermanentView | `attachedToZoneChangeCounter` | nulo | bookkeeping | internal zone counter for attachment effects; the visible relation arrives through attachedTo | — |
| PermanentView | `attacking` | nulo | duplicado | the attacker and its defender arrive in GameView.combat | — |
| PermanentView | `beforeResetControllerId` | nulo | bookkeeping | previous controller, for undoing control effects; not shown | — |
| PermanentView | `blocking` | nulo | duplicado | blocks arrive in GameView.combat | — |
| PermanentView | `blockingSet` | nulo | duplicado | blocks arrive in GameView.combat | — |
| PermanentView | `canBeSacrificed` | bajo | accepted | a restriction the engine uses; the server already filters the offered actions | — |
| PermanentView | `cardType` | nulo | duplicado | the types arrive in CardView.cardTypes | — |
| PermanentView | `classLevel` | nulo | rendered | the "Class level: N" line arrives in rules and is parsed | `web/src/board/designations.ts:42` |
| PermanentView | `controlledFromStartOfControllerTurn` | nulo | bookkeeping | internal timestamp for "since the start of the turn" triggers | — |
| PermanentView | `copyFrom` | nulo | duplicado | the copy is marked by CardView.copy/nameOwner | — |
| PermanentView | `counter` | nulo | parser-artifact | a field of the inner class MarkedDamageInfo (PermanentImpl.java:55-66), not of PermanentImpl | — |
| PermanentView | `createOrder` | nulo | bookkeeping | internal creation order of permanents | — |
| PermanentView | `dealtDamageByThisTurn` | nulo | bookkeeping | internal damage record for triggers and conditions | — |
| PermanentView | `deathtouched` | nulo | bookkeeping | internal mark for deathtouch damage | — |
| PermanentView | `flipCardName` | nulo | duplicado | the alternate name arrives in CardView.alternateName (PermanentView.java:81) | — |
| PermanentView | `goadingPlayers` | nulo | rendered | goad arrives in cardIcons (OTHER_HAS_RESTRICTIONS) plus a "Goaded by..." line in rules | `web/src/board/CardIcons.tsx:27` |
| PermanentView | `harnessed` | nulo | rendered | the "{this} is harnessed" line arrives in rules and is parsed | `web/src/board/designations.ts:44` |
| PermanentView | `indirectPhase` | nulo | bookkeeping | internal indirect phasing; phasedIn is already sent | — |
| PermanentView | `info` | nulo | duplicado | its lines are merged into CardView.rules | — |
| PermanentView | `leftHalfUnlocked` | bajo | accepted | Room (Duskmourn); the engine reports it only in chat (PermanentImpl.java:2336-2364) and the desktop client does not read it either; the web client reads it from there (`game/rooms.ts`, a badge in `CardSlot`) | `web/src/board/CardSlot.tsx:253` |
| PermanentView | `legendRuleApplies` | nulo | bookkeeping | exception to the legend rule; it affects the engine, not rendering | — |
| PermanentView | `loyaltyActivationsAvailable` | bajo | accepted | the server already filters the playable planeswalker abilities (canPlayObjects) | — |
| PermanentView | `manaCost` | nulo | duplicado | the cost arrives in CardView.manaCostLeftStr/manaCostRightStr/manaValue | — |
| PermanentView | `markedDamage` | nulo | bookkeeping | damage marked per source (regeneration); the total arrives in damage | — |
| PermanentView | `markedLifelink` | nulo | bookkeeping | internal record of lifelink damage | — |
| PermanentView | `maxBlockedBy` | bajo | accepted | a blocking restriction the server enforces; the result arrives through canBlock/cardIcons | — |
| PermanentView | `maxBlocks` | bajo | accepted | a blocking restriction the server enforces; the result arrives through canBlock/cardIcons | — |
| PermanentView | `meldsToCard` | nulo | duplicado | the resulting meld card arrives in CardView.secondCardFace (CardView.java:588) | — |
| PermanentView | `minBlockedBy` | bajo | accepted | a blocking restriction the server enforces; the result arrives through canBlock/cardIcons | — |
| PermanentView | `monstrous` | nulo | rendered | the "{this} is monstrous" line arrives in rules and is parsed | `web/src/board/designations.ts:36` |
| PermanentView | `morphCard` | nulo | duplicado | the face-down state arrives in CardView.faceDown and PermanentView.morphed | — |
| PermanentView | `mutatedAbilities` | nulo | duplicado | the mutate pile abilities are merged into rules | — |
| PermanentView | `mutations` | nulo | duplicado | the mutate pile arrives in PermanentView.mutateView | — |
| PermanentView | `mutationsForView` | nulo | duplicado | the mutate pile arrives in PermanentView.mutateView (PermanentView.java:74) | — |
| PermanentView | `nightCard` | nulo | duplicado | the night side arrives in transformable/secondCardFace | — |
| PermanentView | `objectId` | nulo | duplicado | the id arrives in CardView.id | — |
| PermanentView | `originalControllerId` | nulo | bookkeeping | original controller, for control effects; not shown | — |
| PermanentView | `ownerId` | nulo | duplicado | ownership arrives through controlledByOwner/nameOwner | — |
| PermanentView | `pairedPermanent` | nulo | duplicado | the pairing arrives in CardView.pairedCard and the "paired" designation | — |
| PermanentView | `prepared` | nulo | rendered | the "Prepared" line arrives in rules and is parsed | `web/src/board/designations.ts:46` |
| PermanentView | `preparedSpellCopyId` | nulo | bookkeeping | internal id of the prepared copy (new in 1.4.62); the visible "Prepared" state arrives in rules (PermanentView.prepared) | — |
| PermanentView | `protectorId` | nulo | rendered | the "Protected by ..." line arrives in rules and is parsed | `web/src/board/designations.ts:47` |
| PermanentView | `prototyped` | nulo | bookkeeping | the server already applies the prototype characteristics; the flag is not shown | — |
| PermanentView | `renowned` | nulo | rendered | the "{this} is renowned" line arrives in rules and is parsed | `web/src/board/designations.ts:36` |
| PermanentView | `rightHalfUnlocked` | bajo | accepted | Room (Duskmourn); the engine reports it only in chat (PermanentImpl.java:2336-2364) and the desktop client does not read it either; the web client reads it from there (`game/rooms.ts`, a badge in `CardSlot`) | `web/src/board/CardSlot.tsx:253` |
| PermanentView | `ringBearerFlag` | nulo | rendered | the Ring-bearer arrives in cardIcons (RINGBEARER) and is found by that field | `web/src/game/MechanicsTray.tsx:61` |
| PermanentView | `roomWasUnlockedOnCast` | bajo | accepted | Room (Duskmourn); the engine reports it only in chat (PermanentImpl.java:2336-2364) and the desktop client does not read it either; the web client reads it from there (`game/rooms.ts`, a badge in `CardSlot`) | `web/src/board/CardSlot.tsx:253` |
| PermanentView | `secondSideCard` | nulo | duplicado | the other face arrives in CardView.secondCardFace | — |
| PermanentView | `solved` | nulo | rendered | the "Case is solved" line arrives in rules and is parsed | `web/src/board/designations.ts:43` |
| PermanentView | `sourceObject` | nulo | parser-artifact | a field of the inner class MarkedDamageInfo (PermanentImpl.java:55-66), not of PermanentImpl | — |
| PermanentView | `spellAbility` | nulo | bookkeeping | the playable ability arrives through canPlayObjects/stack | — |
| PermanentView | `subtype` | nulo | duplicado | the subtypes arrive in CardView.subTypes | — |
| PermanentView | `supertype` | nulo | duplicado | the supertypes arrive in CardView.superTypes | — |
| PermanentView | `suspected` | nulo | rendered | the "Suspected" line arrives in rules and is parsed | `web/src/board/designations.ts:40` |
| PermanentView | `text` | nulo | duplicado | the text arrives in CardView.rules | — |
| PermanentView | `timesLoyaltyUsed` | nulo | bookkeeping | internal count of loyalty uses | — |
| PermanentView | `topMutation` | nulo | duplicado | the top card of the mutate pile arrives in PermanentView.mutateView | — |
| PermanentView | `transformCount` | nulo | bookkeeping | internal transform count | — |
| PermanentView | `turnsOnBattlefield` | nulo | bookkeeping | internal count of turns on the battlefield | — |
| PlayerView | `abilities` | medio | accepted | player abilities (emblems/effects); the DTO has no channel for them | — |
| PlayerView | `abort` | nulo | bookkeeping | abort flag of the game thread | — |
| PlayerView | `alternativeSourceCosts` | bajo | accepted | the playable options arrive through canPlayObjects/GAME_CHOOSE_ABILITY | — |
| PlayerView | `approvingObject` | nulo | parser-artifact | a field of the inner class ApprovingObjectResult (PlayerImpl.java:1512-1514) | — |
| PlayerView | `canGainLife` | bajo | accepted | a global restriction (effects that forbid gaining life); no channel | — |
| PlayerView | `canLoseLife` | bajo | accepted | a global restriction (effects that forbid losing life); no channel | — |
| PlayerView | `canPlotFromTopOfLibrary` | bajo | accepted | the Plot offer travels in canPlayObjects | — |
| PlayerView | `commandersIds` | nulo | duplicado | the commanders arrive in commandList and in CommanderDamageMatrix | — |
| PlayerView | `dateLastAddedToStack` | nulo | bookkeeping | internal priority timestamp | — |
| PlayerView | `designations` | nulo | rendered | they arrive as designationNames (PlayerView.java:156-158) and are drawn in MechanicsTray/PlayerInfoBar | `web/src/game/MechanicsTray.tsx:142` |
| PlayerView | `draws` | nulo | bookkeeping | internal draw flag for the turn | — |
| PlayerView | `drawsFromBottom` | bajo | accepted | draw policy (format); not shown | — |
| PlayerView | `drawsOnOpponentsTurn` | bajo | accepted | draw policy (format); not shown | — |
| PlayerView | `hand` | nulo | duplicado | the hand arrives in myHand/opponentHands/handCount | — |
| PlayerView | `human` | nulo | duplicado | isHuman | — |
| PlayerView | `idleTimeout` | nulo | duplicado | the visible time is priorityTimeLeftSecs | — |
| PlayerView | `inRange` | bajo | accepted | multiplayer range of influence; the desktop client does not show it either | — |
| PlayerView | `isFastFailInTestMode` | nulo | bookkeeping | test mode flag | — |
| PlayerView | `isGameUnderControl` | nulo | bookkeeping | control is derived in web/src/state/control.ts (opponentHands) | — |
| PlayerView | `isTestMode` | nulo | bookkeeping | test mode flag | — |
| PlayerView | `justActivatedType` | nulo | bookkeeping | type of the last activated ability (internal) | — |
| PlayerView | `left` | nulo | duplicado | hasLeft | — |
| PlayerView | `library` | nulo | duplicado | libraryCount and topCard (revealed) | — |
| PlayerView | `loseByZeroOrLessLife` | bajo | accepted | the rule that 0 or less life loses the game; no channel | — |
| PlayerView | `loses` | nulo | bookkeeping | internal loss flag | — |
| PlayerView | `matchPlayer` | nulo | duplicado | the match score arrives in wins/winsNeeded | — |
| PlayerView | `maxAttackedBy` | bajo | accepted | attacker limit; the server already filters combat | — |
| PlayerView | `maxHandSize` | bajo | accepted | maximum hand size (discard); no channel | — |
| PlayerView | `modifier` | nulo | parser-artifact | a field of the inner class RollDieResult (PlayerImpl.java:3198-3207) | — |
| PlayerView | `naturalResult` | nulo | parser-artifact | a field of the inner class RollDieResult (PlayerImpl.java:3198-3207) | — |
| PlayerView | `passed` | nulo | duplicado | passedTurn/passedUntilEndOfTurn/passedUntilNextMain/... in PlayerView | — |
| PlayerView | `passedTurnSkipStack` | nulo | bookkeeping | internal variant of passing priority | — |
| PlayerView | `payLifeCostRestrictions` | bajo | accepted | life payment restrictions; the server filters the payment | — |
| PlayerView | `payManaMode` | nulo | bookkeeping | the UI payment mode (auto/manual); payment arrives through prompts | — |
| PlayerView | `phyrexianColors` | bajo | accepted | Phyrexian payment options through canPlayObjects | — |
| PlayerView | `planarResult` | nulo | parser-artifact | a field of the inner class RollDieResult (PlayerImpl.java:3198-3207) | — |
| PlayerView | `playersUnderYourControl` | nulo | bookkeeping | control is derived in web/src/state/control.ts | — |
| PlayerView | `priorityTimeLeft` | nulo | duplicado | priorityTimeLeftSecs | — |
| PlayerView | `quit` | nulo | duplicado | hasLeft | — |
| PlayerView | `range` | bajo | accepted | multiplayer range of influence; not shown | — |
| PlayerView | `reachedNextTurnAfterLeaving` | nulo | bookkeeping | internal mark for rejoining the game | — |
| PlayerView | `skippedAtLeastOnce` | nulo | bookkeeping | internal mark for skipped turns | — |
| PlayerView | `speed` | medio | accepted | upstream does not send the 1-4 level; its presence arrives through designationNames and is drawn, and the level is read from chat (`'s speed is now/increased/decreased to N`, `game/speed.ts`) for the tray and a badge | `web/src/game/PlayerInfoBar.tsx:201` |
| PlayerView | `startingDeckSize` | nulo | bookkeeping | deck size at the start (new in 1.4.62); the current size arrives in libraryCount | — |
| PlayerView | `status` | nulo | parser-artifact | a field of the inner class ApprovingObjectResult (PlayerImpl.java:1506-1514) | — |
| PlayerView | `storedBookmark` | nulo | duplicado | statesSavedSize | — |
| PlayerView | `timerTimeout` | nulo | duplicado | the timer badges are derived from timerActive/priorityTimeLeftSecs | — |
| PlayerView | `topCardRevealed` | nulo | duplicado | topCard | — |
| PlayerView | `turnController` | nulo | bookkeeping | turn control is derived in web/src/state/control.ts | — |
| PlayerView | `turnControllers` | nulo | bookkeeping | turn control is derived in web/src/state/control.ts | — |
| PlayerView | `turns` | nulo | bookkeeping | internal turn count of the player | — |
| PlayerView | `usersAllowedToSeeHandCards` | nulo | bookkeeping | internal hand visibility (watching); the server already filters opponentHands | — |
| GameView | `aiGame` | nulo | bookkeeping | flag for an AI vs AI game | — |
| GameView | `attackOption` | nulo | bookkeeping | multiplayer attack option (game config) | — |
| GameView | `checkPlayableState` | nulo | bookkeeping | internal flag to recompute playability | — |
| GameView | `concedingPlayers` | nulo | bookkeeping | queue of concessions; the effect arrives through hasLeft/GAME_END | — |
| GameView | `endTime` | nulo | bookkeeping | end timestamp; not shown | — |
| GameView | `enterWithCounters` | nulo | bookkeeping | enter-with counters; the result arrives in counters | — |
| GameView | `gameCards` | nulo | bookkeeping | internal card registry of the engine | — |
| GameView | `gameIndex` | nulo | bookkeeping | index of the game in the match; the score arrives in wins/winsNeeded | — |
| GameView | `gameOptions` | nulo | bookkeeping | bag of engine options; the UI uses what is already exposed | — |
| GameView | `gameStopped` | nulo | bookkeeping | internal flag for a stopped game | — |
| GameView | `id` | nulo | duplicado | the gameId arrives in GAME_INIT/GAME_UPDATE from the proxy | — |
| GameView | `meldCards` | nulo | bookkeeping | internal registry of meld cards | — |
| GameView | `minimumDeckSize` | nulo | bookkeeping | minimum deck size setting; not shown | — |
| GameView | `mulligan` | nulo | bookkeeping | mulligan state; the decision arrives through a prompt | — |
| GameView | `range` | bajo | accepted | multiplayer range of influence (config); not shown | — |
| GameView | `ready` | nulo | bookkeeping | internal flag for a ready game | — |
| GameView | `saveGame` | nulo | bookkeeping | save flag; not shown | — |
| GameView | `scopeRelevant` | nulo | bookkeeping | internal cache of relevant objects | — |
| GameView | `simulation` | nulo | bookkeeping | simulation game flag | — |
| GameView | `startMessage` | nulo | duplicado | sent as a status event at the start (GameImpl.java:1258) | — |
| GameView | `startTime` | nulo | bookkeeping | start timestamp; not shown | — |
| GameView | `startingHandSize` | nulo | bookkeeping | initial setting; not shown after the start | — |
| GameView | `startingLife` | nulo | bookkeeping | the current life arrives in PlayerView.life | — |
| GameView | `startingPlayerId` | nulo | duplicado | who starts is deduced from activePlayerId/turn | — |
| GameView | `state` | nulo | bookkeeping | internal state machine; phase/step/turn are already sent | — |
| GameView | `tableId` | nulo | duplicado | the proxy and lobby handle the tableId (TableClientMessage.tableId) | — |
| GameView | `winnerId` | nulo | duplicado | the winner arrives with the game end event (GAME_END) | — |

## Notable cases

- **Speed (level)**: the engine keeps the 1-4 level in `PlayerImpl.speed` but does not send it; the *presence*
  of the Speed designation does arrive through `designationNames` and is drawn in `PlayerInfoBar.tsx:201`.
  Accepted without a view patch.
- **Rooms (Duskmourn)**: `leftHalfUnlocked` / `rightHalfUnlocked` / `roomWasUnlockedOnCast` are only reported
  in chat (`PermanentImpl.java:2336-2364`); the desktop client does not read them either. Accepted.
- **Commander damage (the 21 rule)**: the per `(commander, player)` total lives in
  `CommanderInfoWatcher.damageToPlayer` and **does** reach the client, but only as tooltip text:
  `addInfo("Commander<targetId>", "<b>Commander</b> did N combat damage to player <font …>NAME</font>.")`
  on the commander object, and `CardUtil.getCardRulesWithAdditionalInfo` dumps
  `cardState.getInfo().values()` into `rules` (contract probe 2026-09-17; real wire format re-verified
  2026-10-08 against `fixtures/recorded/commander-4.json`). The web parses it in
  `web/src/board/commanders.ts` (`commanderStateCarriers` + `commanderDamageDealt`). What does **not**
  travel is whether the rule exists at all: `GameImpl.checkCommanderDamage` is `false` in Commander Duel
  (since Nov 16), Brawl and Oathbreaker, and it is copied into no `mage.view.*`, so the client cannot
  know whether the 21 threshold applies and the tab always labels it «21 lethal». Paths without a fork
  patch: derive the format from the table (`lobby/joinDeckFit.ts` already classifies
  Commander/Brawl/Oathbreaker on join) or present the threshold as informational. Under control theft the
  damage comes from *another* player's commander (the controller changes, the object does not), which is
  why attribution is done by object id.
- **Parser artifacts**: `PermanentImpl.addCounters` / `counter` / `sourceObject` are fields of the inner class
  `MarkedDamageInfo`; `PlayerImpl.approvingObject` / `status` belong to `ApprovingObjectResult`; `modifier` /
  `naturalResult` / `planarResult` to `RollDieResult`. None is instance state of `PermanentImpl`/`PlayerImpl`.
- **Live duplicates**: `hand`→`myHand`/`opponentHands`/`handCount`; `left`/`quit`→`hasLeft`;
  `priorityTimeLeft`→`priorityTimeLeftSecs`; `objectId`→`id`; `cardType`→`cardTypes`; `attacking`/`blocking`→`GameView.combat`;
  `designations`→`designationNames`; `ringBearerFlag`→`cardIcons` RINGBEARER.
- **The 5 declared gaps** (`KNOWN_DISPLAYABLE_GAPS` in the guard): 4 are rendered (`goadingPlayers` through
  the `cardIcons` restrictions; `harnessed`, `monstrous`, `renowned` through `rules` parsed in
  `designations.ts`) and `PlayerView.abilities` stays accepted (emblems and effects with no channel).
- **LLM seat engine patch**: the fork branch `llm-seats-engine` adds `landsPerTurn` and `landsPlayed` to
  `PlayerView` for the LLM seat bridge, so on that branch they are no longer in the gap. Against an
  unpatched server, such as upstream beta.xmage.today, they stay engine-only and the web client does not
  read them.

## Maintenance

When an upstream change adds fields to the gap or removes them, `engineViewCoverage.test.ts` fails and forces
a triage: add or update the row here and in `web/src/state/engineViewRegistry.ts`, or regenerate the baseline
with `node scripts/engine-view-schema.mjs --update-baseline` when the change is expected. The baseline follows
whichever fork branch is checked out.
