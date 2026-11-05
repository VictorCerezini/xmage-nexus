package mage.client.bridge;

import mage.client.bridge.processor.BridgeChooseActionFlow;
import mage.client.bridge.processor.BridgeChooseActionFlowContext;
import mage.client.bridge.processor.BridgeChooseActionFlowManager;
import mage.client.bridge.processor.BridgeChooseActionInput;
import mage.client.bridge.processor.BridgeChooseActionStartResult;
import mage.client.bridge.processor.BridgeCallbackEvent;
import mage.client.bridge.processor.BridgeCommand;
import mage.client.bridge.processor.BridgeConcedeFlow;
import mage.client.bridge.processor.BridgeConcedeFlowManager;
import mage.client.bridge.processor.BridgeDecisionState;
import mage.client.bridge.processor.BridgeGameLogState;
import mage.client.bridge.processor.BridgeGameState;
import mage.client.bridge.processor.BridgeInteractionState;
import mage.client.bridge.processor.BridgeManaPlanEntry;
import mage.client.bridge.processor.BridgePassPriorityFlow;
import mage.client.bridge.processor.BridgePassPriorityFlowContext;
import mage.client.bridge.processor.BridgePassPriorityFlowManager;
import mage.client.bridge.processor.BridgeProcessorServices;
import mage.client.bridge.processor.BridgePublishedQueryState;
import mage.client.bridge.processor.BridgeProcessor;
import mage.client.bridge.processor.BridgeProcessorState;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.choices.ChoiceImpl;
import mage.client.bridge.tools.ActionResult;
import mage.client.bridge.tools.ChooseActionTool;
import mage.client.bridge.tools.GetGameHistoryTool;
import mage.client.bridge.tools.GetGameStateTool;
import mage.client.bridge.tools.GetOracleTextTool;
import mage.constants.CardType;
import mage.constants.PlayerAction;
import mage.game.BridgeLogEntry;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.PlayableObjectStats;
import mage.players.PlayableObjectsList;
import mage.remote.Session;
import mage.util.MultiAmountMessage;
import mage.util.ShortIdRegistry;
import mage.util.SubTypes;
import mage.view.AbilityPickerView;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.LookedAtView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.RevealedView;
import mage.view.SimpleCardView;
import mage.view.SimpleCardsView;
import mage.view.StackAbilityView;
import mage.view.TableClientMessage;
import mage.view.UserRequestMessage;
import org.junit.jupiter.api.Test;
import org.apache.log4j.Logger;
import sun.misc.Unsafe;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BridgeCallbackHandlerTest {

    @Test
    void acceptsValidMultiAmountInput() {
        GameClientMessage message = multiAmountMessage(List.of(
            new MultiAmountMessage("First", 0, 2),
            new MultiAmountMessage("Second", 0, 2)
        ), 2, 2);

        assertThat(BridgeCallbackHandler.validateMultiAmountInput(message, new int[]{1, 1})).isNull();
    }

    @Test
    void rejectsWrongItemCount() {
        GameClientMessage message = multiAmountMessage(List.of(
            new MultiAmountMessage("Only", 0, 9)
        ), 3, 9);

        assertThat(BridgeCallbackHandler.validateMultiAmountInput(message, new int[]{3, 6}))
            .isEqualTo("Invalid 'amounts' for GAME_GET_MULTI_AMOUNT: expected 1 entry, got 2. "
                + "Expected 1 amount and total 3-9.");
    }

    @Test
    void rejectsPerItemRangeViolations() {
        GameClientMessage message = multiAmountMessage(List.of(
            new MultiAmountMessage("Only", 1, 3)
        ), 1, 3);

        assertThat(BridgeCallbackHandler.validateMultiAmountInput(message, new int[]{0}))
            .isEqualTo("Invalid 'amounts' for GAME_GET_MULTI_AMOUNT: amounts[0]=0 is outside item range 1-3. "
                + "Expected 1 amount and total 1-3.");
    }

    @Test
    void rejectsTotalRangeViolations() {
        GameClientMessage message = multiAmountMessage(List.of(
            new MultiAmountMessage("First", 0, 3),
            new MultiAmountMessage("Second", 0, 3)
        ), 2, 2);

        assertThat(BridgeCallbackHandler.validateMultiAmountInput(message, new int[]{2, 1}))
            .isEqualTo("Invalid 'amounts' for GAME_GET_MULTI_AMOUNT: total 3 is outside allowed range 2. "
                + "Expected 2 amounts and total 2.");
    }

    @Test
    void failsFastWhenPendingActionLacksItemMetadata() {
        GameClientMessage message = new GameClientMessage(
            null,
            Collections.<String, Serializable>emptyMap(),
            (List<MultiAmountMessage>) null,
            1,
            2
        );

        assertThatThrownBy(() -> BridgeCallbackHandler.validateMultiAmountInput(message, new int[]{1}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GAME_GET_MULTI_AMOUNT is missing item metadata");
    }

    @Test
    void stripsMatchingAbilityPickerOrdinalPrefix() {
        assertThat(BridgeCallbackHandler.stripAbilityPickerOrdinalPrefix("1. {T}: Add {G}.", 0))
            .isEqualTo("{T}: Add {G}.");
        assertThat(BridgeCallbackHandler.stripAbilityPickerOrdinalPrefix(
            "2. {2}, {T}: Thespian's Stage becomes a copy of target land, except it has this ability.",
            1
        )).isEqualTo("{2}, {T}: Thespian's Stage becomes a copy of target land, except it has this ability.");
    }

    @Test
    void leavesUnmatchedLeadingNumbersAlone() {
        assertThat(BridgeCallbackHandler.stripAbilityPickerOrdinalPrefix("10 damage to any target.", 0))
            .isEqualTo("10 damage to any target.");
        assertThat(BridgeCallbackHandler.stripAbilityPickerOrdinalPrefix("10. {T}: Add {G}.", 0))
            .isEqualTo("10. {T}: Add {G}.");
    }

    @Test
    void stripsMultiDigitAbilityPickerOrdinalPrefix() {
        assertThat(BridgeCallbackHandler.stripAbilityPickerOrdinalPrefix("10. {T}: Add one mana of any color.", 9))
            .isEqualTo("{T}: Add one mana of any color.");
    }

    @Test
    void getGameLogChunkUsesAbsolutePerPlayerTurnsForCursorSlices() throws Exception {
        BridgeMageClient client = new BridgeMageClient("Alice");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        var result = handler.getGameLogChunk(0, 4);

        assertThat(result.log).isEqualTo("Alice turn 2:\nAlice cast Lightning Bolt targeting Bob");
        assertThat(result.total_length).isNull();
        assertThat(result.truncated).isFalse();
        assertThat(result.cursor).isEqualTo(6);
        assertThat(result.cursor_reset).isNull();
    }

    @Test
    void getGameLogChunkReportsFullLengthBeforeTruncating() throws Exception {
        BridgeMageClient client = new BridgeMageClient("Alice");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        String lastLine = "Alice cast Lightning Bolt targeting Bob";
        var result = handler.getGameLogChunk(lastLine.length(), null);

        assertThat(result.log).isEqualTo(lastLine);
        assertThat(result.total_length).isEqualTo(sampleBridgeLogText().length());
        assertThat(result.truncated).isTrue();
        assertThat(result.cursor).isEqualTo(6);
    }

    @Test
    void getGameLogSinceTurnDefaultsToClientPlayer() throws Exception {
        BridgeMageClient client = new BridgeMageClient("Alice");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        var result = handler.getGameLogSinceTurn(null, 2);

        assertThat(result.log).isEqualTo("Alice turn 2:\nAlice cast Lightning Bolt targeting Bob");
        assertThat(result.total_length).isEqualTo(sampleBridgeLogText().length());
        assertThat(result.truncated).isFalse();
        assertThat(result.cursor).isEqualTo(6);
        assertThat(result.since_turn).isEqualTo(2);
        assertThat(result.since_player).isEqualTo("Alice");
    }

    @Test
    void stepYieldStopsWhenCallbackOvershootsTargetStepInSameTurn() throws Exception {
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession(sessionProxy(autoPassSent, sendPlayerBooleanCalls));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        GameView upkeepView = gameView(7, 3, PhaseStep.UPKEEP);
        GameView postcombatMainView = gameView(8, 3, PhaseStep.POSTCOMBAT_MAIN);
        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", upkeepView);
        setField(handler, "lastTurnNumber", 3);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(upkeepView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            7
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority("precombat_main", null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(postcombatMainView, Collections.<String, Serializable>emptyMap(), "Pass after overshoot")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("reached_step");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.game_seq).isEqualTo(8);
            assertThat(result.current_step).isEqualTo("Postcombat Main");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void stripsHtmlNoiseFromMultiAmountDescriptions() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");

        GameClientMessage message = multiAmountMessage(List.of(
            new MultiAmountMessage(
                "<font color='#F0E68C' object_id='12345678-1234-1234-1234-123456789abc'>"
                    + "Savannah Lions</font> [7e2], P/T: 2/1",
                0,
                2
            )
        ), 2, 2);

        PendingAction pendingAction = new PendingAction(
            UUID.randomUUID(),
            ClientCallbackMethod.GAME_GET_MULTI_AMOUNT,
            message,
            "",
            7
        );
        processor.submit(BridgeCommand.of(() -> {
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));

        ActionResult result = handler.getActionChoices(null);

        assertThat(result.response_type).isEqualTo("multi_amount");
        assertThat(result.items).singleElement().satisfies(item ->
            assertThat(item).containsEntry("description", "Savannah Lions, P/T: 2/1")
        );
    }

    @Test
    void buildCardFieldsMapForCardViewPreservesSharedOracleFields() throws Exception {
        CardView secondFace = cardView(UUID.randomUUID(), "p2", "Awakened Insight");
        setField(secondFace, "manaCostLeftStr", List.of());
        setField(secondFace, "manaCostRightStr", List.of());
        setField(secondFace, "rules", List.of("<i>Flying</i>"));
        setField(secondFace, "cardTypes", List.of(CardType.PLANESWALKER));
        setField(secondFace, "subTypes", subTypes(SubType.JACE));
        setField(secondFace, "superTypes", List.of(SuperType.LEGENDARY));
        setField(secondFace, "startingLoyalty", "5");

        CardView frontFace = cardView(UUID.randomUUID(), "p1", "Test Front");
        setField(frontFace, "manaCostLeftStr", List.of("{2}", "{U}"));
        setField(frontFace, "manaCostRightStr", List.of());
        setField(frontFace, "rules", List.of("Flying", "<i>Ward</i> {2}"));
        setField(frontFace, "cardTypes", List.of(CardType.CREATURE));
        setField(frontFace, "subTypes", subTypes(SubType.HUMAN, SubType.WIZARD));
        setField(frontFace, "superTypes", List.of(SuperType.LEGENDARY));
        setField(frontFace, "power", "3");
        setField(frontFace, "toughness", "4");
        setField(frontFace, "secondCardFace", secondFace);

        Map<String, Object> entry = BridgeOracleTextService.buildCardFieldsMap(frontFace);

        assertThat(entry)
            .containsEntry("name", "Test Front")
            .containsEntry("mana_cost", "{2}{U}")
            .containsEntry("type", "Legendary Creature  - Human Wizard")
            .containsEntry("rules", List.of("Flying", "Ward {2}"))
            .containsEntry("power", "3")
            .containsEntry("toughness", "4");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondFaceEntry = (Map<String, Object>) entry.get("second_face");
        assertThat(secondFaceEntry)
            .containsEntry("name", "Awakened Insight")
            .containsEntry("type", "Legendary Planeswalker  - Jace")
            .containsEntry("rules", List.of("Flying"))
            .containsEntry("starting_loyalty", "5")
            .doesNotContainKeys("mana_cost", "power", "toughness", "starting_defense");
    }

    @Test
    void buildCardFieldsMapForCardViewOmitsNullRulesInsteadOfThrowing() throws Exception {
        CardView card = cardView(UUID.randomUUID(), "p1", "Rulesless Relic");
        setField(card, "rules", null);

        Map<String, Object> entry = BridgeOracleTextService.buildCardFieldsMap(card);

        assertThat(entry)
            .containsEntry("name", "Rulesless Relic")
            .doesNotContainKeys("rules", "toughness");
    }

    @Test
    void buildCardFieldsMapForCardInfoPreservesSharedOracleFields() throws Exception {
        CardInfo battle = new CardInfo();
        setField(battle, "name", "Test Invasion");
        battle.setManaCosts(List.of("{1}", "{W}"));
        battle.setTypes(List.of(CardType.BATTLE));
        battle.setSubtypes(List.of("Siege"));
        battle.setSuperTypes(List.of(SuperType.LEGENDARY));
        battle.setRules(List.of(
            "<i>Front</i> rule one",
            "Front rule two"
        ));
        setField(battle, "startingDefense", "4");

        Map<String, Object> fields = BridgeOracleTextService.buildCardFieldsMap(battle);

        assertThat(fields)
            .containsEntry("name", "Test Invasion")
            .containsEntry("mana_cost", "{1}{W}")
            .containsEntry("type", "Legendary Battle — Siege")
            .containsEntry("rules", List.of("Front rule one", "Front rule two"))
            .containsEntry("starting_defense", "4")
            .doesNotContainKeys("second_face", "power", "toughness");
    }

    @Test
    void stepYieldStopsOnNewTurnRegardlessOfCurrentStep() throws Exception {
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession(sessionProxy(autoPassSent, sendPlayerBooleanCalls));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        GameView upkeepView = gameView(7, 3, PhaseStep.UPKEEP);
        GameView nextTurnUntapView = gameView(8, 4, PhaseStep.UNTAP);
        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", upkeepView);
        setField(handler, "lastTurnNumber", 3);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(upkeepView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            7
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority("postcombat_main", null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(nextTurnUntapView, Collections.<String, Serializable>emptyMap(), "Pass on next turn")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("step_not_reached");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.game_seq).isEqualTo(8);
            assertThat(result.current_step).isEqualTo("Untap");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void returnsStackResolvedOnNextActionAfterPassiveUpdateClearsStack() throws Exception {
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession(sessionProxy(autoPassSent, sendPlayerBooleanCalls));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID watchedStackObjectId = UUID.randomUUID();
        GameView stackOccupied = gameView(7, watchedStackObjectId);
        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", stackOccupied);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(stackOccupied, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            7
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority("stack_resolved", null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();

            GameView stackCleared = gameView(8);
            setField(handler, "lastGameView", stackCleared);

            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(stackCleared, Collections.<String, Serializable>emptyMap(), "Pass after resolve")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("stack_resolved");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.game_seq).isEqualTo(8);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void treatsEmptyStackResolvedAsSinglePass() throws Exception {
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession(sessionProxy(autoPassSent, sendPlayerBooleanCalls));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        GameView emptyStack = gameView(7);
        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", emptyStack);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(emptyStack, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            7
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority("stack_resolved", null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            GameView nextActionView = gameView(8);
            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_ASK,
                gameId,
                new GameClientMessage(nextActionView, Collections.<String, Serializable>emptyMap(), "Mulligan hand?")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("non_priority_action");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_ASK");
            assertThat(result.game_seq).isEqualTo(8);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void passPriorityUsesLatestPassiveGameUpdateWhenNextSelectLacksGameView() throws Exception {
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession(sessionProxy(autoPassSent, sendPlayerBooleanCalls));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID landId = UUID.randomUUID();
        CardView land = cardView(landId, "p11", "Plains");
        setField(land, "cardTypes", List.of(CardType.LAND));
        setField(land, "rules", List.of("{T}: Add {W}."));
        setField(land, "manaCostLeftStr", List.of());
        setField(land, "manaCostRightStr", List.of());

        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        GameView initialView = gameView(21, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);

        CardsView updatedHand = new CardsView();
        updatedHand.put(landId, land);
        GameView updatedView = gameView(44, List.of(player), new CardsView());
        setField(updatedView, "myPlayerId", playerId);
        setField(updatedView, "myHand", updatedHand);
        setField(updatedView, "step", PhaseStep.PRECOMBAT_MAIN);
        setField(updatedView, "canPlayObjects", playableObjects(Map.of(landId, playStats("Play land"))));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Play instants and activated abilities"),
            "Play instants and activated abilities",
            21
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority(null, null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            enqueueCallback(handler, ClientCallbackMethod.GAME_UPDATE, gameId, updatedView);
            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("playable_cards");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.board).isNotNull();
            assertThat(result.choices).singleElement().satisfies(choice ->
                assertThat(choice).containsEntry("name", "Plains")
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void handCardAndRollbackRequestsAreAcceptedForTheRequester() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        List<List<Object>> playerActions = Collections.synchronizedList(new ArrayList<>());

        InvocationHandler sessionHandler = (proxy, method, args) -> {
            if ("sendPlayerAction".equals(method.getName())) {
                playerActions.add(List.of(args));
                return true;
            }
            return defaultReturnValue(method.getReturnType());
        };

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            sessionHandler
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        addActiveGame(handler, gameId);

        handler.handleCallback(new ClientCallback(ClientCallbackMethod.USER_REQUEST_DIALOG, gameId,
            userRequest(gameId, requesterId, PlayerAction.ADD_PERMISSION_TO_ROLLBACK_TURN, PlayerAction.DENY_PERMISSION_TO_ROLLBACK_TURN)));
        handler.handleCallback(new ClientCallback(ClientCallbackMethod.USER_REQUEST_DIALOG, gameId,
            userRequest(gameId, requesterId, PlayerAction.ADD_PERMISSION_TO_SEE_HAND_CARDS, null)));
        handler.awaitProcessorIdle();

        assertThat(playerActions).containsExactly(
            List.of(PlayerAction.ADD_PERMISSION_TO_ROLLBACK_TURN, gameId, requesterId),
            List.of(PlayerAction.ADD_PERMISSION_TO_SEE_HAND_CARDS, gameId, requesterId)
        );
    }

    private static UserRequestMessage userRequest(UUID gameId, UUID requesterId, PlayerAction accept, PlayerAction deny) {
        UserRequestMessage request = new UserRequestMessage("Request by TestPlayer", "Allow?");
        request.setRelatedUser(requesterId, "player1");
        request.setGameId(gameId);
        request.setButton1("Accept", accept);
        request.setButton2("Deny", deny);
        return request;
    }

    @Test
    void gameOverKeepsPostgameHistoryAvailableWithoutBlockingCallbackThread() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        AtomicInteger leaveChatCalls = new AtomicInteger();

        InvocationHandler sessionHandler = (proxy, method, args) -> {
            if ("leaveChat".equals(method.getName())) {
                leaveChatCalls.incrementAndGet();
                assertThat(args[0]).isEqualTo(chatId);
                return true;
            }
            return defaultReturnValue(method.getReturnType());
        };

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            sessionHandler
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        addActiveGame(handler, gameId, playerId);
        setCurrentChatId(handler, gameId, chatId);

        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.GAME_OVER,
            gameId,
            new GameClientMessage(gameView(9), Collections.<String, Serializable>emptyMap(), "Player Opponent is the winner"),
            false
        );
        callback.setBridgeEvents(List.of(
            new BridgeLogEntry(
                0, 9, "LAND_PLAYED", 3, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
                "TestPlayer", "TestPlayer", "Island", null, 0, true
            )
        ));
        handler.handleCallback(callback);
        handler.awaitProcessorIdle();

        assertThat(hasActiveGame(handler, gameId)).isFalse();
        assertThat(leaveChatCalls.get()).isEqualTo(1);

        var history = handler.getGameHistory(null, null);
        assertThat(history.cursor).isEqualTo(1);
        assertThat(history.event_count).isEqualTo(1);
        assertThat(history.history).contains("Turn 3 (TestPlayer):");
        assertThat(history.history).contains("TestPlayer played Island");
    }

    @Test
    void gameLogUsesProcessorLocalCursorInsteadOfServerEventIndex() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, List.of(
            new BridgeLogEntry(50, 50, "BEGIN_TURN", 3, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
                "TestPlayer", "TestPlayer", null, null, 0, true),
            new BridgeLogEntry(51, 51, "LAND_PLAYED", 3, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
                "TestPlayer", "TestPlayer", "Island", null, 0, true),
            new BridgeLogEntry(71, 71, "SPELL_CAST", 3, "PRECOMBAT_MAIN", "PRECOMBAT_MAIN",
                "TestPlayer", "TestPlayer", "Opt", null, 0, true)
        ));

        var log = handler.getGameLogChunk(0, null);
        var history = handler.getGameHistory(null, null);

        assertThat(log.cursor).isEqualTo(3);
        assertThat(history.cursor).isEqualTo(3);
        assertThat(log.log).contains("TestPlayer turn 1:");
        assertThat(history.event_count).isEqualTo(3);
    }

    @Test
    void getGameLogResetsStaleCursorFromLongerPriorGame() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        // reset() restarts the published cursor space at 0 for the next game,
        // so a cursor carried over from the longer prior game is out of range.
        handler.reset();
        setCachedBridgeEvents(handler, List.of(
            bridgeLogEntry(9, "BEGIN_TURN", 4, "Alice", "Alice", null, null),
            bridgeLogEntry(10, "SPELL_CAST", 4, "Alice", "Alice", "Shock", "Bob")
        ));

        var result = handler.getGameLogChunk(0, 6);

        assertThat(result.cursor_reset).isTrue();
        assertThat(result.cursor).isEqualTo(2);
        assertThat(result.log).contains("Alice turn 1:");
        assertThat(result.log).contains("Alice cast Shock targeting Bob");
    }

    @Test
    void getGameHistoryKeepsFutureCursorAtPublishedNextCursor() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        var result = handler.getGameHistory(null, 999);

        assertThat(result.cursor).isEqualTo(6);
        assertThat(result.event_count).isZero();
        assertThat(result.history).isEqualTo("No game events recorded yet.");
    }

    @Test
    void getGameHistoryReadsPublishedSnapshotWithoutRpc() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> defaultReturnValue(method.getReturnType())
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        setCachedBridgeEvents(handler, sampleBridgeLogEvents());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> historyFuture = executor.submit(() -> handler.getGameHistory(null, null));
            Future<Void> processorFuture = executor.submit(() -> processor.submit(BridgeCommand.of(() -> null)));
            processorFuture.get(1, TimeUnit.SECONDS);

            historyFuture.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void getGameHistoryIncludesBridgeEventsPushedViaCallback() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> defaultReturnValue(method.getReturnType())
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        addActiveGame(handler, gameId, playerId);

        ClientCallback callback = new ClientCallback(ClientCallbackMethod.GAME_UPDATE, gameId, null, false);
        callback.setBridgeEvents(List.of(
            bridgeLogEntry(0, "BEGIN_TURN", 4, "Alice", "Alice", null, null),
            bridgeLogEntry(1, "LAND_PLAYED", 4, "Alice", "Alice", "Shock", "Bob")
        ));
        handler.handleCallback(callback);
        handler.awaitProcessorIdle();

        GetGameHistoryTool.Result result = handler.getGameHistory(null, null);
        assertThat(result.event_count).isEqualTo(2);
        assertThat(result.history).contains("Alice played Shock");
    }

    @Test
    void endGameInfoCleansUpWhenGameOverMissed() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        AtomicInteger leaveChatCalls = new AtomicInteger();

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("leaveChat".equals(method.getName())) {
                    leaveChatCalls.incrementAndGet();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        addActiveGame(handler, gameId, playerId);
        setCurrentChatId(handler, gameId, chatId);
        setField(handler, "gameEverStarted", true);

        // Send END_GAME_INFO without prior GAME_OVER — simulates dropped callback
        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.END_GAME_INFO,
            gameId,
            null,
            false
        );
        handler.handleCallback(callback);

        handler.awaitProcessorIdle();
        assertThat(hasActiveGame(handler, gameId)).isFalse();
        assertThat(leaveChatCalls.get()).isEqualTo(1);
    }

    @Test
    void endGameInfoIsNoOpAfterGameOver() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        AtomicInteger leaveChatCalls = new AtomicInteger();

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("leaveChat".equals(method.getName())) {
                    leaveChatCalls.incrementAndGet();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        addActiveGame(handler, gameId, playerId);
        setCurrentChatId(handler, gameId, chatId);

        // Send GAME_OVER first
        ClientCallback gameOverCallback = new ClientCallback(
            ClientCallbackMethod.GAME_OVER,
            gameId,
            new GameClientMessage(gameView(9), Collections.<String, Serializable>emptyMap(), "Player Opponent is the winner"),
            false
        );
        handler.handleCallback(gameOverCallback);
        handler.awaitProcessorIdle();
        assertThat(leaveChatCalls.get()).isEqualTo(1);

        // Send END_GAME_INFO second — should be a no-op
        ClientCallback endGameInfoCallback = new ClientCallback(
            ClientCallbackMethod.END_GAME_INFO,
            gameId,
            null,
            false
        );
        handler.handleCallback(endGameInfoCallback);
        handler.awaitProcessorIdle();

        // leaveChat should NOT have been called a second time
        assertThat(leaveChatCalls.get()).isEqualTo(1);
    }

    @Test
    void concedeWaitsForGameOverOnProcessorFlow() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        CountDownLatch concedeSent = new CountDownLatch(1);
        AtomicReference<String> concedeThreadName = new AtomicReference<>();
        AtomicInteger leaveChatCalls = new AtomicInteger();

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerAction" -> {
                        concedeThreadName.set(Thread.currentThread().getName());
                        assertThat(args[0]).isEqualTo(mage.constants.PlayerAction.CONCEDE);
                        assertThat(args[1]).isEqualTo(gameId);
                        assertThat(args[2]).isNull();
                        concedeSent.countDown();
                        return defaultReturnValue(method.getReturnType());
                    }
                    case "leaveChat" -> {
                        leaveChatCalls.incrementAndGet();
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        addActiveGame(handler, gameId, playerId);
        setCurrentChatId(handler, gameId, chatId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(handler::concede);

            assertThat(concedeSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            handler.handleCallback(new ClientCallback(
                ClientCallbackMethod.GAME_OVER,
                gameId,
                new GameClientMessage(
                    gameView(9),
                    Collections.<String, Serializable>emptyMap(),
                    "Player Opponent is the winner"
                ),
                false
            ));

            assertThat(future.get(1, TimeUnit.SECONDS)).isTrue();
            handler.awaitProcessorIdle();
            assertThat(concedeThreadName.get()).startsWith("bridge-processor-TestPlayer");
            assertThat(hasActiveGame(handler, gameId)).isFalse();
            assertThat(leaveChatCalls.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void concedeReturnsFalseWhenProcessorStopsWhileWaitingForGameOver() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        CountDownLatch concedeSent = new CountDownLatch(1);

        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerAction".equals(method.getName())) {
                    concedeSent.countDown();
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        addActiveGame(handler, gameId, playerId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(handler::concede);

            assertThat(concedeSent.await(1, TimeUnit.SECONDS)).isTrue();
            client.stop();

            assertThat(future.get(1, TimeUnit.SECONDS)).isFalse();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void stackAbilitySummaryIncludesSourceCardAbilityTextAndReadableTargets() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID stackObjectId = UUID.randomUUID();
        CardView sourceCard = cardView(UUID.randomUUID(), "p11", "Emancipation Angel");
        StackAbilityView stackAbility = stackAbilityView(
            stackObjectId,
            sourceCard,
            "When Emancipation Angel enters, return a permanent you control to its owner's hand.",
            playerId
        );

        CardsView stack = new CardsView();
        stack.put(stackObjectId, stackAbility);
        GameView view = gameView(7, List.of(playerView(playerId, "TestPlayer", "p2")), stack);
        Map<String, Object> stackItem = cardFormatter().buildStackItem(
            stackAbility,
            view,
            playerId,
            false,
            false
        );
        assertThat(stackItem)
            .containsEntry("name", "Emancipation Angel")
            .containsEntry("source_card", "Emancipation Angel")
            .containsEntry(
                "ability_text",
                "When Emancipation Angel enters, return a permanent you control to its owner's hand."
            );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) stackItem.get("targets");
        assertThat(targets).singleElement().satisfies(target ->
            assertThat((Map<String, Object>) target).containsEntry("name", "TestPlayer (you)")
        );
    }

    @Test
    void gameChooseChoiceReturnsStructuredErrorForNamedChoiceParam() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        ChoiceImpl choice = new ChoiceImpl(true);
        choice.setMessage("Choose color");
        choice.setChoices(new LinkedHashSet<>(List.of("White", "Blue", "Black")));

        UUID gameId = UUID.randomUUID();
        GameView view = gameView(7);
        setField(handler, "lastGameView", view);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_CHOOSE_CHOICE,
            new GameClientMessage(view, Collections.<String, Serializable>emptyMap(), choice),
            "Choose color",
            7
        ));

        var result = handler.chooseAction(
            null, "Black", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error_code).isEqualTo("invalid_choice");
        assertThat(result.retryable).isTrue();
        assertThat(result.error)
            .contains("choice=\"Black\"")
            .contains("text=\"Black\"")
            .contains("choice=N")
            .doesNotContain("Unknown short ID");
        assertThat(result.choices)
            .extracting(entry -> entry.get("description"))
            .containsExactly("White", "Blue", "Black");
    }

    @Test
    void chooseActionReturnsStructuredErrorForUnknownShortId() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        GameView view = gameView(7);
        setField(handler, "lastGameView", view);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(view, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            7
        ));

        var result = handler.chooseAction(
            null, "p", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error_code).isEqualTo("invalid_choice");
        assertThat(result.retryable).isTrue();
        assertThat(result.error)
            .contains("Unknown short ID: p")
            .contains("get_action_choices");
    }

    @Test
    void chooseActionWaitsForNextDecisionInsteadOfReturningSingleTargetFollowup() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID onlyTarget = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();

        GameView askView = gameView(10);
        GameView targetView = gameView(11);
        GameView nextDecisionView = gameView(12);
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose a creature to copy",
            new CardsView(),
            Set.of(onlyTarget),
            true
        );
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities"
        );

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", askView);
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(true);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_TARGET, gameId, targetMessage);
                        return true;
                    }
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(onlyTarget);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(askView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            10
        ));

        var result = handler.chooseAction(
            null, null, true, null, null, null, null, null, null, null, null
        );

        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
        assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
        assertThat(result.success).isTrue();
        assertThat(result.action_taken).isEqualTo("yes");
        assertThat(result.warning).isNull();
        assertThat(result.game_seq).isEqualTo(12);
        assertThat(result.action_pending).isTrue();
        assertThat(result.action_type).isEqualTo("GAME_SELECT");
        assertThat(result.response_type).isEqualTo("boolean");
        assertThat(result.message).isEqualTo("Play spells and abilities");
    }

    @Test
    void chooseActionWaitsThroughMultipleAutoResolvedFollowups() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID onlyTarget = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();

        GameView askView = gameView(20);
        GameView targetView = gameView(21);
        GameView abilityView = gameView(22);
        GameView nextDecisionView = gameView(23);
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose a creature to copy",
            new CardsView(),
            Set.of(onlyTarget),
            true
        );
        AbilityPickerView emptyAbilityPicker = new AbilityPickerView(
            abilityView,
            new LinkedHashMap<>(),
            "Choose spell or ability"
        );
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities"
        );

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", askView);
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(true);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_TARGET, gameId, targetMessage);
                        return true;
                    }
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        if (sendPlayerUuidCalls.get() == 1) {
                            assertThat(args[1]).isEqualTo(onlyTarget);
                            enqueueCallback(handler, ClientCallbackMethod.GAME_CHOOSE_ABILITY, gameId, emptyAbilityPicker);
                        } else {
                            assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
                            assertThat(args[1]).isNull();
                            enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        }
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(askView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            20
        ));

        var result = handler.chooseAction(
            null, null, true, null, null, null, null, null, null, null, null
        );

        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
        assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
        assertThat(result.success).isTrue();
        assertThat(result.action_taken).isEqualTo("yes");
        assertThat(result.game_seq).isEqualTo(23);
        assertThat(result.action_pending).isTrue();
        assertThat(result.action_type).isEqualTo("GAME_SELECT");
        assertThat(result.response_type).isEqualTo("boolean");
        assertThat(result.message).isEqualTo("Play spells and abilities");
    }

    @Test
    void chooseActionDoesNotMonopolizeProcessorThreadWhileWaiting() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch sendPlayerBooleanCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(40);
        GameView nextDecisionView = gameView(41);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    sendPlayerBooleanCalled.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            40
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, true, null, null, null, null, null, null, null, null
            ));

            assertThat(sendPlayerBooleanCalled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            handler.awaitProcessorIdle();

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(nextDecisionView, Collections.<String, Serializable>emptyMap(), "Play spells and abilities")
            );

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("yes");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.game_seq).isEqualTo(41);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void batchChooseActionAttackersDoesNotMonopolizeProcessorThreadWhileWaiting() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID attackerUuid = UUID.randomUUID();
        CountDownLatch sendPlayerUuidCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView combatView = gameView(45);
        GameView confirmView = gameView(46);
        GameView nextDecisionView = gameView(47);

        registerShortId(handler, attackerUuid, "p1");

        var combatOptions = new LinkedHashMap<String, Serializable>();
        combatOptions.put("possibleAttackers", new ArrayList<>(List.of(attackerUuid)));
        GameClientMessage combatMessage = new GameClientMessage(combatView, combatOptions, "Declare attackers");
        GameClientMessage confirmMessage = new GameClientMessage(confirmView, Collections.<String, Serializable>emptyMap(), "Declare attackers");
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        sendPlayerUuidCalled.countDown();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(attackerUuid);
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(true);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", combatView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            combatMessage,
            "Declare attackers",
            45
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, null, null, null, null, null, null, null, new String[]{"p1"}, null
            ));

            assertThat(sendPlayerUuidCalled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            handler.awaitProcessorIdle();

            enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, confirmMessage);

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("batch_attack");
            assertThat(result.declared).containsExactly(Map.of("id", "p1"));
            assertThat(result.action_pending).isTrue();
            assertThat(result.game_seq).isEqualTo(47);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void batchChooseActionAttackersAllWithSingleAttackerUsesDirectUuidPath() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID attackerUuid = UUID.randomUUID();
        CountDownLatch sendPlayerUuidCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView combatView = gameView(145);
        GameView confirmView = gameView(146);
        GameView nextDecisionView = gameView(147);

        registerShortId(handler, attackerUuid, "p1");

        var combatOptions = new LinkedHashMap<String, Serializable>();
        combatOptions.put("possibleAttackers", new ArrayList<>(List.of(attackerUuid)));
        GameClientMessage combatMessage = new GameClientMessage(combatView, combatOptions, "Declare attackers");
        GameClientMessage confirmMessage = new GameClientMessage(confirmView, Collections.<String, Serializable>emptyMap(), "Declare attackers");
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        sendPlayerUuidCalled.countDown();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(attackerUuid);
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(true);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        return true;
                    }
                    case "sendPlayerString" -> throw new AssertionError("single-attacker attackers=all should not use special path");
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", combatView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            combatMessage,
            "Declare attackers",
            145
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, null, null, null, null, null, null, null, new String[]{"all"}, null
            ));

            assertThat(sendPlayerUuidCalled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            handler.awaitProcessorIdle();
            enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, confirmMessage);

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("batch_attack");
            assertThat(result.declared).containsExactly(Map.of("id", "all"));
            assertThat(result.action_pending).isTrue();
            assertThat(result.game_seq).isEqualTo(147);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void batchChooseActionAttackersAllWithSingleAttackerReturnsImmediateNextDecisionWithoutConfirm() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID attackerUuid = UUID.randomUUID();
        CountDownLatch sendPlayerUuidCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView combatView = gameView(182);
        GameView nextDecisionView = gameView(187, 5, PhaseStep.DECLARE_ATTACKERS);

        registerShortId(handler, attackerUuid, "p1");

        var combatOptions = new LinkedHashMap<String, Serializable>();
        combatOptions.put("possibleAttackers", new ArrayList<>(List.of(attackerUuid)));
        GameClientMessage combatMessage = new GameClientMessage(combatView, combatOptions, "Declare attackers");
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play instants and activated abilities"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        sendPlayerUuidCalled.countDown();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(attackerUuid);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        throw new AssertionError("single-attacker attackers=all should not confirm when XMage already moved to the next decision");
                    }
                    case "sendPlayerString" -> throw new AssertionError("single-attacker attackers=all should not use special path");
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", combatView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            combatMessage,
            "Declare attackers",
            182
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, null, null, null, null, null, null, null, new String[]{"all"}, null
            ));

            assertThat(sendPlayerUuidCalled.await(1, TimeUnit.SECONDS)).isTrue();

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
            assertThat(sendPlayerBooleanCalls.get()).isZero();
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("batch_attack");
            assertThat(result.declared).containsExactly(Map.of("id", "all"));
            assertThat(result.action_pending).isTrue();
            assertThat(result.message).isEqualTo("Play instants and activated abilities");
            assertThat(result.game_seq).isEqualTo(187);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void batchChooseActionBlockersHandlesTargetPromptAndReturnsNextDecision() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID blockerUuid = UUID.randomUUID();
        UUID attackerUuid = UUID.randomUUID();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView combatView = gameView(48);
        GameView targetView = gameView(49);
        GameView returnSelectView = gameView(50);
        GameView nextDecisionView = gameView(51);

        registerShortId(handler, blockerUuid, "p5");
        registerShortId(handler, attackerUuid, "p1");

        var combatOptions = new LinkedHashMap<String, Serializable>();
        combatOptions.put("possibleBlockers", new ArrayList<>(List.of(blockerUuid)));
        GameClientMessage combatMessage = new GameClientMessage(combatView, combatOptions, "Declare blockers");
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose attacker to block",
            new CardsView(),
            Set.of(attackerUuid),
            true
        );
        GameClientMessage returnSelectMessage = new GameClientMessage(
            returnSelectView,
            Collections.<String, Serializable>emptyMap(),
            "Declare blockers"
        );
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        if (sendPlayerUuidCalls.get() == 1) {
                            assertThat(args[1]).isEqualTo(blockerUuid);
                            enqueueCallback(handler, ClientCallbackMethod.GAME_TARGET, gameId, targetMessage);
                        } else {
                            assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
                            assertThat(args[1]).isEqualTo(attackerUuid);
                            enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, returnSelectMessage);
                        }
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(true);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextDecisionMessage);
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", combatView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            combatMessage,
            "Declare blockers",
            48
        ));

        ChooseActionTool.Result result = handler.chooseAction(
            null, null, null, null, null, null, null, null, null, null, new String[]{"p5:p1"}
        );

        assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
        assertThat(result.success).isTrue();
        assertThat(result.action_taken).isEqualTo("batch_block");
        assertThat(result.declared).containsExactly(Map.of("id", "p5", "blocks", "p1"));
        assertThat(result.action_pending).isTrue();
        assertThat(result.game_seq).isEqualTo(51);
    }

    @Test
    void chooseActionReturnsAfterClientStopWithoutFollowupCallback() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch sendPlayerBooleanCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(50);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    sendPlayerBooleanCalled.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            50
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, true, null, null, null, null, null, null, null, null
            ));

            assertThat(sendPlayerBooleanCalled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            client.stop();

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("yes");
            assertThat(result.action_pending).isNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void chooseActionReturnsAfterClientDisconnectWithoutFollowupCallback() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch sendPlayerBooleanCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(55);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    sendPlayerBooleanCalled.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            55
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, true, null, null, null, null, null, null, null, null
            ));

            assertThat(sendPlayerBooleanCalled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            client.disconnected(false, false);

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.success).isTrue();
            assertThat(result.action_taken).isEqualTo("yes");
            assertThat(result.action_pending).isNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void chooseActionReturnsAfterClientStopWithBatchTargetCallbackPending() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID blockerUuid = UUID.randomUUID();
        UUID attackerUuid = UUID.randomUUID();
        CountDownLatch targetCallbackQueued = new CountDownLatch(1);
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView combatView = gameView(56);
        GameView targetView = gameView(57);

        registerShortId(handler, blockerUuid, "p5");
        registerShortId(handler, attackerUuid, "p1");

        var combatOptions = new LinkedHashMap<String, Serializable>();
        combatOptions.put("possibleBlockers", new ArrayList<>(List.of(blockerUuid)));
        GameClientMessage combatMessage = new GameClientMessage(combatView, combatOptions, "Declare blockers");
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose attacker to block",
            new CardsView(),
            Set.of(attackerUuid),
            true
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        if (sendPlayerUuidCalls.get() == 1) {
                            assertThat(args[1]).isEqualTo(blockerUuid);
                            enqueueCallback(handler, ClientCallbackMethod.GAME_TARGET, gameId, targetMessage);
                            targetCallbackQueued.countDown();
                        } else {
                            assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
                            assertThat(args[1]).isEqualTo(attackerUuid);
                        }
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", combatView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            combatMessage,
            "Declare blockers",
            56
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChooseActionTool.Result> future = executor.submit(() -> handler.chooseAction(
                null, null, null, null, null, null, null, null, null, null, new String[]{"p5:p1"}
            ));

            assertThat(targetCallbackQueued.await(1, TimeUnit.SECONDS)).isTrue();
            BridgeDecisionState decisionState = (BridgeDecisionState) getField(handler, "decisionState");
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadlineNanos) {
                PendingAction pendingAction = decisionState.pendingAction();
                if (pendingAction != null && pendingAction.method() == ClientCallbackMethod.GAME_TARGET) {
                    break;
                }
                Thread.sleep(10);
            }

            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            client.stop();

            ChooseActionTool.Result result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerUuidCalls.get()).isEqualTo(2);
            assertThat(sendPlayerBooleanCalls.get()).isZero();
            assertThat(result.success).isFalse();
            assertThat(result.interrupted).isTrue();
            assertThat(result.action_taken).isEqualTo("batch_block");
            assertThat(result.declared).containsExactly(Map.of("id", "p5", "blocks", "p1"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void chooseActionReturnsCancelledResultWhenCallerThreadIsInterrupted() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch sendPlayerBooleanCalled = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(60);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    sendPlayerBooleanCalled.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_ASK,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Use effect of Clone?"),
            "Use effect of Clone?",
            60
        ));

        AtomicReference<ChooseActionTool.Result> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean interruptFlagAfterReturn = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                resultRef.set(handler.chooseAction(
                    null, null, true, null, null, null, null, null, null, null, null
                ));
                interruptFlagAfterReturn.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        }, "choose-action-interrupt-test");

        worker.start();
        try {
            assertThat(sendPlayerBooleanCalled.await(1, TimeUnit.SECONDS)).isTrue();

            worker.interrupt();

            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(errorRef.get()).isNull();
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(resultRef.get()).isNotNull();
            assertThat(resultRef.get().success).isFalse();
            assertThat(resultRef.get().error_code).isEqualTo("cancelled");
            assertThat(interruptFlagAfterReturn.get()).isTrue();
        } finally {
            worker.join(1000);
        }
    }

    @Test
    void chooseActionTickerFailsFlowWhenTickCommandThrowsIllegalStateException() throws Exception {
        AtomicBoolean failOnDecisionRead = new AtomicBoolean(false);
        BridgeProcessor processor = new BridgeProcessor(
            "TestPlayer",
            Logger.getLogger(BridgeChooseActionFlowManager.class),
            event -> { }
        );
        BridgeDecisionState decisionState = new BridgeDecisionState();
        BridgeChooseActionFlowContext context = new BridgeChooseActionFlowContext() {
            @Override
            public PendingAction currentPendingAction() {
                return null;
            }

            @Override
            public PendingAction currentDecisionAction() {
                if (failOnDecisionRead.get()) {
                    throw new IllegalStateException("choose_action tick failed");
                }
                return null;
            }

            @Override
            public boolean requestCannotContinue() {
                return false;
            }

            @Override
            public ChooseActionTool.Result noPendingActionResult() {
                return new ChooseActionTool.Result();
            }

            @Override
            public BridgeChooseActionStartResult applyChooseAction(BridgeChooseActionInput input, PendingAction action) {
                throw new AssertionError("applyChooseAction should not run");
            }

            @Override
            public String detectCombatSelect(PendingAction action) {
                throw new AssertionError("detectCombatSelect should not run");
            }

            @Override
            public UUID resolveShortId(String shortId) {
                throw new AssertionError("resolveShortId should not run");
            }

            @Override
            public Set<UUID> validTargets(PendingAction action) {
                throw new AssertionError("validTargets should not run");
            }

            @Override
            public boolean clearPendingActionIfCurrent(PendingAction action) {
                throw new AssertionError("clearPendingActionIfCurrent should not run");
            }

            @Override
            public void sendBooleanOrDie(UUID gameId, boolean data, String sendContext) {
                throw new AssertionError("sendBooleanOrDie should not run");
            }

            @Override
            public void sendUuidOrDie(UUID gameId, UUID data, String sendContext) {
                throw new AssertionError("sendUuidOrDie should not run");
            }

            @Override
            public void sendStringOrDie(UUID gameId, String data, String sendContext) {
                throw new AssertionError("sendStringOrDie should not run");
            }

            @Override
            public ChooseActionTool.Result buildChooseActionError(
                    ChooseActionTool.Result result,
                    String errorCode,
                    String message,
                    boolean retryable,
                    PendingAction action) {
                throw new AssertionError("buildChooseActionError should not run");
            }

            @Override
            public void finishChooseActionWithNextDecision(
                    ChooseActionTool.Result result,
                    PendingAction previousAction,
                    PendingAction nextAction) {
                throw new AssertionError("finishChooseActionWithNextDecision should not run");
            }

            @Override
            public void finishChooseActionWithoutNextDecision(
                    ChooseActionTool.Result result,
                    PendingAction previousAction) {
                throw new AssertionError("finishChooseActionWithoutNextDecision should not run");
            }

            @Override
            public void finishBatchChooseActionWithNextDecision(
                    ChooseActionTool.Result result,
                    PendingAction nextAction) {
                throw new AssertionError("finishBatchChooseActionWithNextDecision should not run");
            }

            @Override
            public void finishBatchChooseActionWithoutNextDecision(ChooseActionTool.Result result) {
                throw new AssertionError("finishBatchChooseActionWithoutNextDecision should not run");
            }

            @Override
            public ChooseActionTool.Result cancelledChooseActionResult(
                    PendingAction previousAction,
                    ChooseActionTool.Result partialResult) {
                throw new AssertionError("cancelledChooseActionResult should not run");
            }
        };
        BridgeChooseActionFlowManager manager = new BridgeChooseActionFlowManager(
            processor,
            "TestPlayer",
            decisionState,
            context,
            message -> {
                var result = new ChooseActionTool.Result();
                result.error = message;
                return result;
            }
        );
        processor.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BridgeChooseActionFlow flow = processor.submit(BridgeCommand.of(() -> manager.startPendingFlow(
                new BridgeChooseActionInput(null, null, null, null, null, null, null, null, null, null, null)
            )));

            Future<ChooseActionTool.Result> future = executor.submit(flow::awaitResult);
            failOnDecisionRead.set(true);

            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> {
                    Throwable outerCause = ((ExecutionException) error).getCause();
                    assertThat(outerCause).isInstanceOf(ExecutionException.class);
                    assertThat(outerCause.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("choose_action tick failed");
                });
            assertThat(decisionState.pendingChooseActionFlow()).isNull();
        } finally {
            manager.shutdown();
            processor.shutdown("test done");
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void passPriorityAutoHandlesSingleTargetFollowupBeforeReturningDecision() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID onlyTarget = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();

        GameView initialView = gameView(30);
        GameView targetView = gameView(31);
        GameView nextDecisionView = gameView(32);
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose a creature to copy",
            new CardsView(),
            Set.of(onlyTarget),
            true
        );
        GameClientMessage nextDecisionMessage = new GameClientMessage(
            nextDecisionView,
            Collections.<String, Serializable>emptyMap(),
            "Mulligan hand?"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(false);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_TARGET, gameId, targetMessage);
                        return true;
                    }
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(gameId);
                        assertThat(args[1]).isEqualTo(onlyTarget);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_ASK, gameId, nextDecisionMessage);
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            30
        ));

        ActionResult result = handler.passPriority("stack_resolved", null);

        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
        assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
        assertThat(result.stop_reason).isEqualTo("non_priority_action");
        assertThat(result.warning).isNull();
        assertThat(result.action_pending).isTrue();
        assertThat(result.action_type).isEqualTo("GAME_ASK");
        assertThat(result.game_seq).isEqualTo(32);
        assertThat(result.response_type).isEqualTo("boolean");
        assertThat(result.message).isEqualTo("Mulligan hand?");
    }

    @Test
    void passPriorityReturnsCombatDecisionAfterAutoPass() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(50);
        GameView combatView = gameView(51);
        var combatOptions = new java.util.HashMap<String, Serializable>();
        combatOptions.put("possibleAttackers", new java.util.ArrayList<>(List.of(UUID.randomUUID())));
        GameClientMessage combatMessage = new GameClientMessage(
            combatView,
            combatOptions,
            "Declare attackers"
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    assertThat(args[0]).isEqualTo(gameId);
                    assertThat(args[1]).isEqualTo(false);
                    enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, combatMessage);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            50
        ));

        ActionResult result = handler.passPriority(null, null);

        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
        assertThat(result.stop_reason).isEqualTo("combat");
        assertThat(result.action_pending).isTrue();
        assertThat(result.action_type).isEqualTo("GAME_SELECT");
        assertThat(result.game_seq).isEqualTo(51);
        assertThat(result.combat_phase).isEqualTo("attackers");
    }

    @Test
    void handleCallbackStoresSingleTargetAsPendingAction() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID onlyTarget = UUID.randomUUID();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        GameView targetView = gameView(33);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    sendPlayerUuidCalls.incrementAndGet();
                    assertThat(args[0]).isEqualTo(gameId);
                    assertThat(args[1]).isEqualTo(onlyTarget);
                    return false;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId, playerId);

        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.GAME_TARGET,
            gameId,
            new GameClientMessage(
                targetView,
                Collections.<String, Serializable>emptyMap(),
                "Choose a creature to copy",
                new CardsView(),
                Set.of(onlyTarget),
                true
            ),
            false
        );

        handler.handleCallback(callback);
        handler.awaitProcessorIdle();

        PendingAction pendingAction = (PendingAction) getField(handler, "pendingAction");
        assertThat(sendPlayerUuidCalls.get()).isZero();
        assertThat(pendingAction).isNotNull();
        assertThat(pendingAction.gameId()).isEqualTo(gameId);
        assertThat(pendingAction.method()).isEqualTo(ClientCallbackMethod.GAME_TARGET);
        assertThat(pendingAction.message()).isEqualTo("Choose a creature to copy");
        assertThat(pendingAction.gameSeq()).isEqualTo(33);
        assertThat(((GameClientMessage) pendingAction.data()).getGameView()).isSameAs(targetView);
        assertThat(getField(handler, "lastGameView")).isSameAs(targetView);
        assertThat(getField(handler, "playerDead")).isEqualTo(false);
        assertThat(handler.isActionPending()).isTrue();
    }

    @Test
    void getGameStateReadsPublishedProcessorSnapshot() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        GameView gameView = gameView(12, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            gameView,
            false
        ));
        handler.awaitProcessorIdle();

        var result = handler.getGameState(null);

        assertThat(result.available).isTrue();
        assertThat(result.snapshot_id).isEqualTo(publishedGameStateSnapshotId(handler));
        assertThat(result.game_seq).isEqualTo(12);
        assertThat(result.turn).isEqualTo(1);
        assertThat(result.active_player).isEqualTo("TestPlayer");
        assertThat(result.priority_player).isEqualTo("TestPlayer");
    }

    @Test
    void publishesGameStateSnapshotIdBeforeFirstMcpRead() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        GameView gameView = gameView(12, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            gameView,
            false
        ));
        handler.awaitProcessorIdle();

        assertThat(publishedGameStateSnapshotId(handler)).isEqualTo(handler.getGameState(null).snapshot_id);
    }

    @Test
    void getOracleTextReadsPublishedOracleSnapshotInsteadOfLiveGameViewBag() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        CardView bolt = cardView(cardId, "p1", "Lightning Bolt");
        setField(bolt, "manaCostLeftStr", List.of("{R}"));
        setField(bolt, "manaCostRightStr", List.of());
        setField(bolt, "rules", List.of("Lightning Bolt deals 3 damage to any target."));
        setField(bolt, "cardTypes", List.of(CardType.INSTANT));

        PlayerView player = playerView(playerId, "TestPlayer", "p9");
        CardsView hand = new CardsView();
        hand.put(cardId, bolt);
        GameView initialView = gameView(12, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(initialView, "myHand", hand);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        GameView mutatedLiveBagView = gameView(99, List.of(player), new CardsView());
        setField(mutatedLiveBagView, "myPlayerId", playerId);
        setField(mutatedLiveBagView, "myHand", new CardsView());
        setField(handler, "lastGameView", mutatedLiveBagView);

        GetOracleTextTool.Result result = handler.getOracleText(null, "p1", null, null);

        assertThat(result.success).isTrue();
        assertThat(result.name).isEqualTo("Lightning Bolt");
        assertThat(result.mana_cost).isEqualTo("{R}");
        assertThat(result.type).isEqualTo("Instant");
        assertThat(result.rules).containsExactly("Lightning Bolt deals 3 damage to any target.");
    }

    @Test
    void getOracleTextResolvesCardNameFromPublishedIndexWithoutCardRepository() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        CardView bolt = cardView(cardId, "p1", "Lightning Bolt");
        setField(bolt, "manaCostLeftStr", List.of("{R}"));
        setField(bolt, "manaCostRightStr", List.of());
        setField(bolt, "rules", List.of("Lightning Bolt deals 3 damage to any target."));
        setField(bolt, "cardTypes", List.of(CardType.INSTANT));

        PlayerView player = playerView(playerId, "TestPlayer", "p9");
        CardsView hand = new CardsView();
        hand.put(cardId, bolt);
        GameView initialView = gameView(12, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(initialView, "myHand", hand);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        // Name-based lookup should resolve from the published oracle index
        // even without CardRepository being populated for this card
        GetOracleTextTool.Result result = handler.getOracleText("Lightning Bolt", null, null, null);

        assertThat(result.success).isTrue();
        assertThat(result.name).isEqualTo("Lightning Bolt");
        assertThat(result.mana_cost).isEqualTo("{R}");
        assertThat(result.type).isEqualTo("Instant");
        assertThat(result.rules).containsExactly("Lightning Bolt deals 3 damage to any target.");
    }

    @Test
    void getOracleTextSupportsPublishedSecondFaceObjectIds() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID frontId = UUID.randomUUID();
        UUID backId = UUID.randomUUID();

        CardView backFace = cardView(backId, "p2", "Awakened Insight");
        setField(backFace, "manaCostLeftStr", List.of());
        setField(backFace, "manaCostRightStr", List.of());
        setField(backFace, "rules", List.of("<i>Flying</i>"));
        setField(backFace, "cardTypes", List.of(CardType.PLANESWALKER));
        setField(backFace, "subTypes", subTypes(SubType.JACE));
        setField(backFace, "superTypes", List.of(SuperType.LEGENDARY));
        setField(backFace, "startingLoyalty", "5");

        CardView frontFace = cardView(frontId, "p1", "Test Front");
        setField(frontFace, "manaCostLeftStr", List.of("{2}", "{U}"));
        setField(frontFace, "manaCostRightStr", List.of());
        setField(frontFace, "rules", List.of("Flying"));
        setField(frontFace, "cardTypes", List.of(CardType.CREATURE));
        setField(frontFace, "power", "3");
        setField(frontFace, "toughness", "4");
        setField(frontFace, "secondCardFace", backFace);

        PlayerView player = playerView(playerId, "TestPlayer", "p9");
        CardsView hand = new CardsView();
        hand.put(frontId, frontFace);
        GameView initialView = gameView(12, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(initialView, "myHand", hand);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        GetOracleTextTool.Result result = handler.getOracleText(null, "p2", null, null);

        assertThat(result.success).isTrue();
        assertThat(result.name).isEqualTo("Awakened Insight");
        assertThat(result.type).isEqualTo("Legendary Planeswalker  - Jace");
        assertThat(result.rules).containsExactly("Flying");
        assertThat(result.starting_loyalty).isEqualTo("5");
    }

    @Test
    void getOracleTextSupportsResolveOnlyAliasIds() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessorServices processorServices =
            (BridgeProcessorServices) getDirectField(handler, "processorServices");

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        CardView bolt = cardView(cardId, "p1", "Lightning Bolt");
        setField(bolt, "manaCostLeftStr", List.of("{R}"));
        setField(bolt, "manaCostRightStr", List.of());
        setField(bolt, "rules", List.of("Lightning Bolt deals 3 damage to any target."));
        setField(bolt, "cardTypes", List.of(CardType.INSTANT));

        PlayerView player = playerView(playerId, "TestPlayer", "p9");
        CardsView hand = new CardsView();
        hand.put(cardId, bolt);
        GameView initialView = gameView(12, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(initialView, "myHand", hand);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.awaitProcessorIdle();

        String localAlias = processorServices.shortIds().getOrAssign(cardId);
        assertThat(localAlias).startsWith("l");

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        GetOracleTextTool.Result result = handler.getOracleText(null, localAlias, null, null);

        assertThat(result.success).isTrue();
        assertThat(result.name).isEqualTo("Lightning Bolt");
        assertThat(result.rules).containsExactly("Lightning Bolt deals 3 damage to any target.");
    }

    @Test
    void getOracleTextSupportsPublishedStackAbilityObjectIds() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID sourceCardId = UUID.randomUUID();
        UUID stackAbilityId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        CardView sourceCard = cardView(sourceCardId, "p2", "Thespian's Stage");
        setField(sourceCard, "manaCostLeftStr", List.of());
        setField(sourceCard, "manaCostRightStr", List.of());
        setField(sourceCard, "rules", List.of("{2}, {T}: Thespian's Stage becomes a copy of target land, except it has this ability."));
        setField(sourceCard, "cardTypes", List.of(CardType.LAND));

        StackAbilityView stackAbility = stackAbilityView(stackAbilityId, sourceCard, "Ability text", targetId);
        setField(stackAbility, "displayName", null);
        setField(stackAbility, "shortId", "p1");

        PlayerView player = playerView(playerId, "TestPlayer", "p9");
        CardsView stack = new CardsView();
        stack.put(stackAbilityId, stackAbility);
        GameView initialView = gameView(12, List.of(player), stack);
        setField(initialView, "myPlayerId", playerId);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        GetOracleTextTool.Result result = handler.getOracleText(null, "p1", null, null);

        assertThat(result.success).isTrue();
        assertThat(result.name).isEqualTo("Thespian's Stage");
        assertThat(result.rules).containsExactly(
            "{2}, {T}: Thespian's Stage becomes a copy of target land, except it has this ability."
        );
    }

    @Test
    void getOracleTextTreatsCurrentActionChoiceIdsAsKnownImmediately() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID hiddenChoiceId = UUID.randomUUID();

        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        GameView view = gameView(12, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);
        setField(view, "canPlayObjects", playableObjects(Map.of(hiddenChoiceId, playStats("Cast Mystery Spell"))));

        PendingAction pendingAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            12
        );

        processor.submit(BridgeCommand.of(() -> {
            gameState.activateGame(gameId, playerId);
            publishedQueryState.projectGameState(view, 1, "test_init");
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));

        ActionResult choices = handler.getActionChoices(null);
        @SuppressWarnings("unchecked")
        String objectId = (String) ((Map<String, Object>) choices.choices.get(0)).get("id");

        GetOracleTextTool.Result oracle = handler.getOracleText(null, objectId, null, null);

        assertThat(oracle.success).isFalse();
        assertThat(oracle.error).isEqualTo("Object not found in current game state: " + objectId);
    }

    @Test
    void aRevealedTopCardIsOnTheBoardNamedAmongThePlayableCardsAndLookedUpById() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID topCardId = UUID.randomUUID();

        // Future Sight: the top card of the library is revealed, and it can be cast from there
        CardView elves = cardView(topCardId, "p4", "Llanowar Elves");
        setField(elves, "rules", List.of("{T}: Add {G}."));
        setField(elves, "cardTypes", List.of(CardType.CREATURE));
        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        setField(player, "topCard", elves);
        GameView view = gameView(12, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);
        setField(view, "canPlayObjects", playableObjects(Map.of(topCardId, playStats("Cast Llanowar Elves"))));

        projectDecision(handler, gameId, playerId, view, new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            12
        ));

        ActionResult choices = handler.getActionChoices(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) choices.choices.get(0);
        assertThat(choice).containsEntry("id", "p4").containsEntry("name", "Llanowar Elves");

        GetOracleTextTool.Result oracle = handler.getOracleText(null, "p4", null, null);
        assertThat(oracle.success).isTrue();
        assertThat(oracle.name).isEqualTo("Llanowar Elves");
        assertThat(oracle.rules).containsExactly("{T}: Add {G}.");

        Map<String, Object> me = handler.getGameState(null).players.get(0);
        assertThat(me.get("library_top")).isEqualTo(List.of(Map.of(
            "id", "p4",
            "name", "Llanowar Elves",
            "rules", List.of("{T}: Add {G}.")
        )));
    }

    @Test
    void aCardOfferedOnlyByThePendingChoiceIsLookedUpById() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID forestId = UUID.randomUUID();

        // a library search: the card is in no zone the board shows, and the server gave it no short id
        CardView forest = cardView(forestId, null, "Forest");
        setField(forest, "rules", List.of("({T}: Add {G}.)"));
        setField(forest, "cardTypes", List.of(CardType.LAND));
        CardsView offered = new CardsView();
        offered.put(forestId, forest);
        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        GameView view = gameView(12, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);

        projectDecision(handler, gameId, playerId, view, new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_TARGET,
            new GameClientMessage(
                (GameView) null,
                Collections.<String, Serializable>emptyMap(),
                "Search your library for a basic land card",
                offered,
                Set.of(forestId),
                false
            ),
            "Search your library for a basic land card",
            12
        ));

        ActionResult choices = handler.getActionChoices(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) choices.choices.get(0);
        assertThat(choice).containsEntry("name", "Forest");

        GetOracleTextTool.Result oracle = handler.getOracleText(null, (String) choice.get("id"), null, null);
        assertThat(oracle.success).as(String.valueOf(oracle.error)).isTrue();
        assertThat(oracle.name).isEqualTo("Forest");
        assertThat(oracle.rules).containsExactly("({T}: Add {G}.)");
    }

    @Test
    void revealedCardsAreFoundById() throws Exception {
        BridgeViewLocator locator = new BridgeViewLocator(new ShortIdRegistry(), message -> { });
        UUID revealedId = UUID.randomUUID();
        UUID companionId = UUID.randomUUID();
        CardView revealedCard = cardView(revealedId, "p7", "Dark Ritual");
        CardView companionCard = cardView(companionId, "p8", "Lurrus of the Dream-Den");

        GameView view = gameView(12, List.of(playerView(UUID.randomUUID(), "TestPlayer", "p2")), new CardsView());
        setField(view, "revealed", List.of(revealedView("Dark Confidant", revealedCard)));
        setField(view, "companion", List.of(revealedView("Companion", companionCard)));

        assertThat(locator.findCardViewById(revealedId, view)).isSameAs(revealedCard);
        assertThat(locator.findCardViewById(companionId, view)).isSameAs(companionCard);
        assertThat(locator.revealedCards(view)).containsExactly(revealedCard, companionCard);
        assertThat(locator.findCardViewById(UUID.randomUUID(), view)).isNull();
    }

    @Test
    void aLookedAtCardIsReadFromTheCardDatabase() throws Exception {
        BridgeViewLocator locator = new BridgeViewLocator(new ShortIdRegistry(), message -> { });
        UUID boltId = UUID.randomUUID();
        // Bolas's Citadel: the seat may look at its top card, which comes as a printing only
        SimpleCardView bolt = new SimpleCardView(boltId, "LEA", "161", false);
        bolt.setShortId("p9");
        SimpleCardsView cards = new SimpleCardsView();
        cards.put(boltId, bolt);
        LookedAtView lookedAt = (LookedAtView) UNSAFE.allocateInstance(LookedAtView.class);
        setField(lookedAt, "name", "Top card of your library");
        setField(lookedAt, "cards", cards);

        GameView view = gameView(12, List.of(playerView(UUID.randomUUID(), "TestPlayer", "p2")), new CardsView());
        setField(view, "lookedAt", List.of(lookedAt));

        CardView found = locator.findCardViewById(boltId, view);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(boltId);
        assertThat(found.getShortId()).isEqualTo("p9");
        assertThat(found.getDisplayName()).isEqualTo("Lightning Bolt");
        @SuppressWarnings("unchecked")
        List<String> rules = (List<String>) BridgeOracleTextService.buildCardFieldsMap(found).get("rules");
        assertThat(rules).anySatisfy(line -> assertThat(line).contains("3 damage"));
        assertThat(locator.findCardViewById(boltId, view)).as("read once, then kept").isSameAs(found);
    }

    @Test
    void getMyDecklistReadsPublishedSnapshotInsteadOfLiveDeckObject() {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        DeckCardLists deck = new DeckCardLists();
        deck.setCards(List.of(new DeckCardInfo("Lightning Bolt", "150", "lea", 4)));
        handler.setDeckList(deck);

        deck.setCards(List.of(new DeckCardInfo("Counterspell", "51", "lea", 2)));

        assertThat(handler.getMyDecklist())
            .containsEntry("cards", "4x Lightning Bolt")
            .doesNotContainEntry("cards", "2x Counterspell");
    }

    @Test
    void unrelatedRepublishDoesNotRebuildPublishedGameStateFromLiveBag() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        GameView initialView = gameView(12, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());
        GameView mutatedLiveBagView = gameView(99, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        GetGameStateTool.Result initial = handler.getGameState(null);
        setField(handler, "lastGameView", mutatedLiveBagView);
        publishProcessorState(handler);

        GetGameStateTool.Result reread = handler.getGameState(null);
        assertThat(reread.snapshot_id).isEqualTo(initial.snapshot_id);
        assertThat(reread.game_seq).isEqualTo(initial.game_seq);
    }

    @Test
    void unrelatedRepublishDoesNotRebuildPublishedActionChoicesFromProjectedContextInputs() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PlayerView initialPlayer = playerView(playerId, "TestPlayer", "p2");
        PlayerView mutatedPlayer = playerView(playerId, "TestPlayer", "p2");
        UUID landId = UUID.randomUUID();
        setField(
            mutatedPlayer,
            "battlefield",
            new LinkedHashMap<>(Map.of(landId, permanentView(landId, "p3", "Forest", false)))
        );

        GameView initialView = gameView(12, List.of(initialPlayer), new CardsView());
        GameView mutatedLiveBagView = gameView(99, List.of(mutatedPlayer), new CardsView());
        PendingAction pendingAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            12
        );

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        processor.submit(BridgeCommand.of(() -> {
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));

        ActionResult initial = handler.getActionChoices(null);
        setField(publishedQueryState, "projectedGameView", mutatedLiveBagView);
        publishProcessorState(handler);

        ActionResult reread = handler.getActionChoices(null);
        assertThat(reread.board_cursor).isEqualTo(initial.board_cursor);
        assertThat(reread.board).isEqualTo(initial.board);
        assertThat(reread.context).isEqualTo(initial.context);
    }

    @Test
    void visibleActionSnapshotChangeAdvancesBoardCursorEvenWhenBoardIsUnchanged() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");

        installPublishedSelectPrompt(handler);
        ActionResult initial = handler.getActionChoices(null);

        processor.submit(BridgeCommand.of(() -> {
            PendingAction nextAction = new PendingAction(
                UUID.randomUUID(),
                ClientCallbackMethod.GAME_ASK,
                new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Use the optional ability?"),
                "Use the optional ability?",
                12
            );
            decisionState.replacePendingAction(nextAction);
            return null;
        }));

        ActionResult changed = handler.getActionChoices(initial.board_cursor);
        assertThat(changed.board_cursor).isGreaterThan(initial.board_cursor);
        assertThat(changed.board_unchanged).isNull();
        assertThat(changed.board).isEqualTo(initial.board);
        assertThat(changed.action_type).isEqualTo("GAME_ASK");
    }

    @Test
    void actionableCallbackReplacementDoesNotBurnHiddenBoardCursor() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        GameView initialView = gameView(12, List.of(player), new CardsView());
        GameView replacementView = gameView(13, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(replacementView, "myPlayerId", playerId);

        PendingAction initialAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            12
        );

        processor.submit(BridgeCommand.of(() -> {
            gameState.activateGame(gameId, playerId);
            publishedQueryState.projectGameState(initialView, 1, "test_init");
            decisionState.replacePendingAction(initialAction);
            return null;
        }));

        ActionResult initial = handler.getActionChoices(null);

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_ASK,
            gameId,
            new GameClientMessage(replacementView, Collections.<String, Serializable>emptyMap(), "Use the optional ability?"),
            false
        ));
        handler.awaitProcessorIdle();

        ActionResult changed = handler.getActionChoices(initial.board_cursor);
        assertThat(changed.board_cursor).isEqualTo(initial.board_cursor + 1);
        assertThat(changed.action_type).isEqualTo("GAME_ASK");
        assertThat(changed.message).isEqualTo("Use the optional ability?");
    }

    @Test
    void turnAdvanceReprojectsPublishedActionChoicesAfterClearingFailedManaCast() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        BridgeInteractionState interactionState =
            (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();

        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        CardsView initialHand = new CardsView();
        CardView initialSpell = cardView(spellId, "p13", "Memnite");
        setField(initialSpell, "manaCostLeftStr", List.of());
        setField(initialSpell, "manaCostRightStr", List.of());
        initialHand.put(spellId, initialSpell);

        GameView initialView = gameView(12, List.of(player), new CardsView());
        setField(initialView, "myPlayerId", playerId);
        setField(initialView, "myHand", initialHand);
        setField(initialView, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Memnite"))));

        CardsView nextTurnHand = new CardsView();
        CardView nextTurnSpell = cardView(spellId, "p13", "Memnite");
        setField(nextTurnSpell, "manaCostLeftStr", List.of());
        setField(nextTurnSpell, "manaCostRightStr", List.of());
        nextTurnHand.put(spellId, nextTurnSpell);
        GameView nextTurnView = gameView(13, List.of(player), new CardsView());
        setField(nextTurnView, "myPlayerId", playerId);
        setField(nextTurnView, "myHand", nextTurnHand);
        setField(nextTurnView, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Memnite"))));
        setIntField(nextTurnView, "turn", 2);

        PendingAction pendingAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            12
        );

        processor.submit(BridgeCommand.of(() -> {
            gameState.activateGame(gameId, playerId);
            interactionState.advanceTurn(initialView);
            publishedQueryState.projectGameState(initialView, 1, "test_init");
            interactionState.markFailedManaCast(spellId);
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));

        ActionResult initialChoices = handler.getActionChoices(null);
        assertThat(initialChoices.choices)
            .singleElement()
            .satisfies(choice -> assertThat(choice.get("cast_cancelled_this_turn")).isEqualTo(true));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_UPDATE,
            gameId,
            nextTurnView,
            false
        ));
        handler.awaitProcessorIdle();

        ActionResult turnTwoChoices = handler.getActionChoices(null);
        assertThat(turnTwoChoices.choices)
            .extracting(choice -> choice.get("id"))
            .containsExactly("p13");
        assertThat(turnTwoChoices.choices.get(0)).doesNotContainKey("cast_cancelled_this_turn");
    }

    @Test
    void internalChoiceAutopopulationDoesNotAdvanceVisibleBoardCursor() throws Exception {
        BridgeCallbackHandler firstHandler = new BridgeMageClient("TestPlayer").getCallbackHandler();
        installPublishedSelectPrompt(firstHandler);
        ActionResult directChoices = firstHandler.getActionChoices(null);

        BridgeMageClient secondClient = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler secondHandler = secondClient.getCallbackHandler();
        installPublishedSelectPrompt(secondHandler);

        ChooseActionTool.Result error = secondHandler.chooseAction(
            null, "pxxx", null, null, null, null, null, null, null, null, null
        );
        assertThat(error.success).isFalse();
        assertThat(error.error_code).isEqualTo("invalid_choice");

        ActionResult afterError = secondHandler.getActionChoices(null);
        assertThat(afterError.board_cursor).isEqualTo(directChoices.board_cursor);
        assertThat(afterError.board).isEqualTo(directChoices.board);
        assertThat(afterError.context).isEqualTo(directChoices.context);
    }

    @Test
    void staleCallbackDoesNotRegressProjectedPublishedGameState() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        GameView newerView = gameView(120, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());
        GameView staleView = gameView(119, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            newerView,
            false
        ));
        handler.awaitProcessorIdle();

        GetGameStateTool.Result initial = handler.getGameState(null);

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_UPDATE,
            gameId,
            staleView,
            false
        ));
        handler.awaitProcessorIdle();

        GetGameStateTool.Result reread = handler.getGameState(null);
        assertThat(reread.snapshot_id).isEqualTo(initial.snapshot_id);
        assertThat(reread.game_seq).isEqualTo(initial.game_seq);
    }

    @Test
    void getGameStateWithSnapshotIdWaitsForQueuedCallbacksBeforeReportingUnchanged() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        GameView initialView = gameView(12, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());
        GameView queuedView = gameView(13, List.of(playerView(playerId, "TestPlayer", "p2")), new CardsView());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "joinGame" -> true;
                    case "getGameChatId" -> Optional.empty();
                    default -> defaultReturnValue(method.getReturnType());
                };
            }
        ));

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_INIT,
            gameId,
            initialView,
            false
        ));
        handler.awaitProcessorIdle();

        long initialSnapshotId = handler.getGameState(null).snapshot_id;
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");

        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockerFuture = executor.submit(() -> processor.submit(new BridgeCommand<Void>() {
                @Override
                public Void execute() {
                    blockerEntered.countDown();
                    try {
                        assertThat(releaseBlocker.await(1, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while blocking processor", e);
                    }
                    return null;
                }
            }));

            assertThat(blockerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(queuedView, Collections.<String, Serializable>emptyMap(), "Pass")
            );

            Future<GetGameStateTool.Result> future = executor.submit(() -> handler.getGameState(initialSnapshotId));

            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseBlocker.countDown();

            GetGameStateTool.Result result = future.get(1, TimeUnit.SECONDS);
            blockerFuture.get(1, TimeUnit.SECONDS);

            assertThat(result.available).isTrue();
            assertThat(result.unchanged).isNull();
            assertThat(result.game_seq).isEqualTo(13);
            assertThat(result.snapshot_id).isNotEqualTo(initialSnapshotId);
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void getActionChoicesWaitsForQueuedCallbacksBeforeReturningPublishedChoices() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");

        UUID gameId = UUID.randomUUID();
        GameView queuedView = gameView(13, 3, PhaseStep.PRECOMBAT_MAIN);
        addActiveGame(handler, gameId);

        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockerFuture = executor.submit(() -> processor.submit(new BridgeCommand<Void>() {
                @Override
                public Void execute() {
                    blockerEntered.countDown();
                    try {
                        assertThat(releaseBlocker.await(1, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while blocking processor", e);
                    }
                    return null;
                }
            }));

            assertThat(blockerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_SELECT,
                gameId,
                new GameClientMessage(queuedView, Collections.<String, Serializable>emptyMap(), "Pass")
            );

            Future<ActionResult> future = executor.submit(() -> handler.getActionChoices(null));

            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseBlocker.countDown();

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            blockerFuture.get(1, TimeUnit.SECONDS);

            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_SELECT");
            assertThat(result.response_type).isEqualTo("boolean");
            assertThat(result.game_seq).isEqualTo(13);
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedProcessorCommandStillPublishesUpdatedActionPendingSnapshot() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        UUID gameId = UUID.randomUUID();
        PendingAction pendingAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            7
        );

        processor.submit(BridgeCommand.of(() -> {
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));
        assertThat(handler.isActionPending()).isTrue();

        assertThatThrownBy(() -> processor.submit(new BridgeCommand<Void>() {
            @Override
            public Void execute() {
                decisionState.clearPendingActionIfCurrent(pendingAction);
                throw new IllegalStateException("boom");
            }
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(handler.isActionPending()).isFalse();
    }

    @Test
    void callbackHookFailureDoesNotKillProcessorThread() throws Exception {
        AtomicInteger hookCalls = new AtomicInteger();
        CountDownLatch callbackHandled = new CountDownLatch(1);
        BridgeProcessor processor = new BridgeProcessor(
            "TestPlayer",
            Logger.getLogger(BridgeCallbackHandlerTest.class),
            event -> callbackHandled.countDown()
        );
        processor.setAfterMessageHook(message -> {
            if (hookCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("hook failed");
            }
        });
        processor.start();

        try {
            processor.enqueueCallback(new BridgeCallbackEvent(
                UUID.randomUUID(),
                ClientCallbackMethod.GAME_SELECT,
                null,
                null
            ));

            assertThat(callbackHandled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(processor.submit(BridgeCommand.of(() -> "ok"))).isEqualTo("ok");
            assertThat(hookCalls.get()).isGreaterThanOrEqualTo(2);
        } finally {
            processor.shutdown("test");
        }
    }

    @Test
    void handleCallbackProcessesStartGameOnProcessorThread() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<String> joinGameThreadName = new AtomicReference<>();
        AtomicReference<String> listenerThreadName = new AtomicReference<>();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("joinGame".equals(method.getName())) {
                    joinGameThreadName.set(Thread.currentThread().getName());
                    assertThat(args[0]).isEqualTo(gameId);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ) {
            @Override
            public void decompressData() {
                listenerThreadName.set(Thread.currentThread().getName());
                super.decompressData();
            }
        };

        String callbackCallerThreadName = Thread.currentThread().getName();
        client.onCallback(callback);
        client.awaitCallbackListenerIdle();
        handler.awaitProcessorIdle();

        assertThat(joinGameThreadName.get()).startsWith("bridge-processor-TestPlayer");
        assertThat(listenerThreadName.get()).startsWith("bridge-listener-TestPlayer");
        assertThat(listenerThreadName.get()).isNotEqualTo(callbackCallerThreadName);
        assertThat(joinGameThreadName.get()).isNotEqualTo(listenerThreadName.get());
        assertThat(getField(handler, "currentGameId")).isEqualTo(gameId);
        assertThat(getField(handler, "currentPlayerId")).isEqualTo(playerId);
    }

    @Test
    void clientOnCallbackSerializesConcurrentIngressOnSingleListenerThread() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        Set<String> listenerThreads = Collections.synchronizedSet(new LinkedHashSet<>());

        ClientCallback first = new ClientCallback(
            ClientCallbackMethod.GAME_SELECT,
            gameId,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Pass"),
            false
        ) {
            @Override
            public void decompressData() {
                listenerThreads.add(Thread.currentThread().getName());
                super.decompressData();
            }
        };
        ClientCallback second = new ClientCallback(
            ClientCallbackMethod.GAME_SELECT,
            gameId,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Pass"),
            false
        ) {
            @Override
            public void decompressData() {
                listenerThreads.add(Thread.currentThread().getName());
                super.decompressData();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> client.onCallback(first));
            Future<?> secondFuture = executor.submit(() -> client.onCallback(second));

            firstFuture.get(1, TimeUnit.SECONDS);
            secondFuture.get(1, TimeUnit.SECONDS);
            client.awaitCallbackListenerIdle();
            handler.awaitProcessorIdle();
        } finally {
            executor.shutdownNow();
        }

        assertThat(listenerThreads).containsExactly("bridge-listener-TestPlayer");
    }

    @Test
    void awaitCallbackListenerIdleAfterShutdownWaitsForInFlightCallback() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);

        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.GAME_SELECT,
            UUID.randomUUID(),
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Pass"),
            false
        ) {
            @Override
            public void decompressData() {
                callbackStarted.countDown();
                try {
                    assertThat(releaseCallback.await(1, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                super.decompressData();
            }
        };

        client.onCallback(callback);
        assertThat(callbackStarted.await(1, TimeUnit.SECONDS)).isTrue();
        client.stop();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                client.awaitCallbackListenerIdle();
                return null;
            });

            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseCallback.countDown();
            future.get(1, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void joinNextTableWaitsForStartGameOnProcessorFlow() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        UUID gameId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<String> joinGameThreadName = new AtomicReference<>();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "joinGame" -> {
                        joinGameThreadName.set(Thread.currentThread().getName());
                        assertThat(args[0]).isEqualTo(gameId);
                        return true;
                    }
                    case "getGameChatId" -> {
                        return Optional.empty();
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        handler.setJoinHandler((deckPath, requestedTableId) -> {
            assertThat(requestedTableId).isEqualTo(tableId);
            Thread callbackThread = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                client.getCallbackHandler().handleCallback(new ClientCallback(
                    ClientCallbackMethod.START_GAME,
                    gameId,
                    new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
                    false
                ));
            }, "join-table-start-game-test");
            callbackThread.setDaemon(true);
            callbackThread.start();
            return tableId;
        });

        try {
            handler.joinNextTable(joinTableDeckPath(), tableId);

            BridgeCallbackHandler fresh = client.getCallbackHandler();
            fresh.awaitProcessorIdle();
            assertThat(fresh).isNotSameAs(handler);
            assertThat(joinGameThreadName.get()).startsWith("bridge-processor-TestPlayer");
            assertThat(getField(fresh, "currentGameId")).isEqualTo(gameId);
            assertThat(getField(fresh, "currentPlayerId")).isEqualTo(playerId);
        } finally {
            client.stop();
        }
    }

    @Test
    void joinNextTableIgnoresWrongStartGameTableWhileWaiting() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        UUID wrongGameId = UUID.randomUUID();
        UUID rightGameId = UUID.randomUUID();
        UUID wrongTableId = UUID.randomUUID();
        UUID rightTableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicInteger joinGameCalls = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "joinGame" -> {
                        joinGameCalls.incrementAndGet();
                        assertThat(args[0]).isEqualTo(rightGameId);
                        return true;
                    }
                    case "getGameChatId" -> {
                        return Optional.empty();
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        handler.setJoinHandler((deckPath, requestedTableId) -> {
            assertThat(requestedTableId).isEqualTo(rightTableId);
            Thread callbackThread = new Thread(() -> {
                client.getCallbackHandler().handleCallback(new ClientCallback(
                    ClientCallbackMethod.START_GAME,
                    wrongGameId,
                    new TableClientMessage().withTable(wrongTableId, null).withPlayer(playerId),
                    false
                ));
                client.getCallbackHandler().handleCallback(new ClientCallback(
                    ClientCallbackMethod.START_GAME,
                    rightGameId,
                    new TableClientMessage().withTable(rightTableId, null).withPlayer(playerId),
                    false
                ));
            }, "join-table-wrong-table-test");
            callbackThread.setDaemon(true);
            callbackThread.start();
            return rightTableId;
        });

        try {
            handler.joinNextTable(joinTableDeckPath(), rightTableId);

            BridgeCallbackHandler fresh = client.getCallbackHandler();
            fresh.awaitProcessorIdle();
            assertThat(joinGameCalls.get()).isEqualTo(1);
            assertThat(getField(fresh, "currentGameId")).isEqualTo(rightGameId);
            assertThat(getField(fresh, "currentPlayerId")).isEqualTo(playerId);
        } finally {
            client.stop();
        }
    }

    @Test
    void joinNextTableReturnsWhenProcessorStopsBeforeStartGame() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setKeepAliveAfterGame(true);

        UUID tableId = UUID.randomUUID();
        CountDownLatch joinReturned = new CountDownLatch(1);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("getGameChatId".equals(method.getName())) {
                    return Optional.empty();
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        handler.setJoinHandler((deckPath, requestedTableId) -> {
            assertThat(requestedTableId).isEqualTo(tableId);
            joinReturned.countDown();
            return tableId;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    handler.joinNextTable(joinTableDeckPath(), tableId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // generous: on a fresh checkout the deck load creates the card database first
            assertThat(joinReturned.await(30, TimeUnit.SECONDS)).isTrue();
            BridgeCallbackHandler fresh = client.getCallbackHandler();
            fresh.awaitProcessorIdle();
            client.stop();

            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .hasMessageContaining("Game did not start within 60s after joining table");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void queuedStartGameFlowFailsAfterShutdownInsteadOfCreatingOrphanedWaiter() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        BridgeCallbackHandler fresh = handler.createFreshForNextGame();
        BridgeProcessor processor = (BridgeProcessor) getDirectField(fresh, "processor");
        UUID tableId = UUID.randomUUID();

        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> blockerFuture = executor.submit(() -> processor.submit(new BridgeCommand<Void>() {
                @Override
                public Void execute() {
                    blockerEntered.countDown();
                    try {
                        assertThat(releaseBlocker.await(1, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while blocking processor", e);
                    }
                    return null;
                }
            }));

            assertThat(blockerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdownFuture = executor.submit(() -> {
                fresh.shutdownProcessor("test shutdown");
                return null;
            });
            Thread.sleep(50);
            assertThat(shutdownFuture.isDone()).isFalse();

            Future<?> startFuture = executor.submit(() -> invokeStartPendingStartGameFlow(fresh, tableId));

            releaseBlocker.countDown();

            ExecutionException failure = null;
            try {
                startFuture.get(1, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failure = e;
            }
            assertThat(failure).isNotNull();
            assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(failure.getCause().getMessage()).isIn(
                "START_GAME flow manager is shut down",
                "Bridge processor is shut down"
            );

            blockerFuture.get(1, TimeUnit.SECONDS);
            shutdownFuture.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void executeDefaultActionRunsOnProcessorThread() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        AtomicReference<String> sendThreadName = new AtomicReference<>();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendThreadName.set(Thread.currentThread().getName());
                    assertThat(args[0]).isEqualTo(gameId);
                    assertThat(args[1]).isEqualTo(false);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        GameView view = gameView(7);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(view, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            7
        ));

        String callerThreadName = Thread.currentThread().getName();
        Map<String, Object> result = handler.executeDefaultAction();

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("action_type", "GAME_SELECT");
        assertThat(result).containsEntry("action_taken", "passed_priority");
        assertThat(sendThreadName.get()).startsWith("bridge-processor-TestPlayer");
        assertThat(sendThreadName.get()).isNotEqualTo(callerThreadName);
    }

    @Test
    void passPriorityDoesNotMonopolizeProcessorThreadWhileWaiting() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch autoPassSent = new CountDownLatch(1);
        GameView initialView = gameView(90);
        GameView nextDecisionView = gameView(91);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    autoPassSent.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            90
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority(null, null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            handler.awaitProcessorIdle();

            enqueueCallback(
                handler,
                ClientCallbackMethod.GAME_ASK,
                gameId,
                new GameClientMessage(nextDecisionView, Collections.<String, Serializable>emptyMap(), "Mulligan hand?")
            );

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("non_priority_action");
            assertThat(result.action_pending).isTrue();
            assertThat(result.action_type).isEqualTo("GAME_ASK");
            assertThat(result.game_seq).isEqualTo(91);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void passPriorityReturnsAfterClientStopWithoutFollowupCallback() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(92);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    autoPassSent.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            92
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActionResult> future = executor.submit(() -> handler.passPriority(null, null));

            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            client.stop();

            ActionResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(result.stop_reason).isEqualTo("game_over");
            assertThat(result.action_pending).isFalse();
            assertThat(result.game_seq).isEqualTo(92);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void createFreshForNextGameCopiesPersistentConfigInsteadOfLiveProcessorState() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        try {
            handler.setKeepAliveAfterGame(true);
            handler.setMaxCallsWithoutProgress(7);
            handler.awaitProcessorIdle();

            BridgeGameState oldGameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
            BridgeInteractionState oldInteractionState = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
            oldGameState.setKeepAliveAfterGame(false);
            oldInteractionState.setMaxCallsWithoutProgress(99);

            BridgeCallbackHandler fresh = handler.createFreshForNextGame();
            fresh.awaitProcessorIdle();

            BridgeGameState freshGameState = (BridgeGameState) getProcessorStateField(fresh, "gameState");
            BridgeInteractionState freshInteractionState = (BridgeInteractionState) getProcessorStateField(fresh, "interactionState");
            assertThat(freshGameState.keepAliveAfterGame()).isTrue();
            assertThat(freshInteractionState.maxCallsWithoutProgress()).isEqualTo(7);
        } finally {
            client.stop();
        }
    }

    @Test
    void queryApisFailFastAfterProcessorShutdown() {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        DeckCardLists deck = new DeckCardLists();
        deck.setCards(List.of(new DeckCardInfo("Lightning Bolt", "150", "lea", 4)));
        handler.setDeckList(deck);

        handler.shutdownProcessor("test shutdown");

        assertThatThrownBy(handler::getMyDecklist)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Bridge processor is shut down");
        assertThatThrownBy(() -> handler.getOracleText("Lightning Bolt", null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Bridge processor is shut down");
    }

    @Test
    void passPriorityReturnsCancelledResultWhenCallerThreadIsInterrupted() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        CountDownLatch autoPassSent = new CountDownLatch(1);
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        GameView initialView = gameView(93);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    sendPlayerBooleanCalls.incrementAndGet();
                    autoPassSent.countDown();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        addActiveGame(handler, gameId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", initialView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(initialView, Collections.<String, Serializable>emptyMap(), "Pass"),
            "Pass",
            93
        ));

        AtomicReference<ActionResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean interruptFlagAfterReturn = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                resultRef.set(handler.passPriority(null, null));
                interruptFlagAfterReturn.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        }, "pass-priority-interrupt-test");

        worker.start();
        try {
            assertThat(autoPassSent.await(1, TimeUnit.SECONDS)).isTrue();

            worker.interrupt();

            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(errorRef.get()).isNull();
            assertThat(sendPlayerBooleanCalls.get()).isEqualTo(1);
            assertThat(resultRef.get()).isNotNull();
            assertThat(resultRef.get().stop_reason).isEqualTo("cancelled");
            assertThat(resultRef.get().action_pending).isFalse();
            assertThat(interruptFlagAfterReturn.get()).isTrue();
        } finally {
            worker.join(1000);
        }
    }

    @Test
    void passPriorityTickerFailsFlowWhenTickCommandThrowsIllegalStateException() throws Exception {
        AtomicBoolean failOnPendingRead = new AtomicBoolean(false);
        BridgeProcessor processor = new BridgeProcessor(
            "TestPlayer",
            Logger.getLogger(BridgePassPriorityFlowManager.class),
            event -> { }
        );
        BridgeDecisionState decisionState = new BridgeDecisionState();
        BridgePassPriorityFlowContext context = new BridgePassPriorityFlowContext() {
            @Override
            public String username() {
                return "TestPlayer";
            }

            @Override
            public PendingAction currentPendingAction() {
                if (failOnPendingRead.get()) {
                    throw new IllegalStateException("pass_priority tick failed");
                }
                return null;
            }

            @Override
            public PendingAction currentDecisionAction() {
                return null;
            }

            @Override
            public PendingAction resolvePassPriorityAction(PendingAction action) {
                return action;
            }

            @Override
            public GameView preparePassPriorityActionView(PendingAction action) {
                return null;
            }

            @Override
            public int callsWithoutProgress() {
                return 0;
            }

            @Override
            public boolean stuck() {
                return false;
            }

            @Override
            public void clearStuck() {
                throw new AssertionError("clearStuck should not run");
            }

            @Override
            public void executeDefaultAction() {
                throw new AssertionError("executeDefaultAction should not run");
            }

            @Override
            public String detectCombatSelect(PendingAction action) {
                throw new AssertionError("detectCombatSelect should not run");
            }

            @Override
            public ActionResult pendingActionResult(PendingAction action, String stopReason, Long boardCursorParam) {
                throw new AssertionError("pendingActionResult should not run");
            }

            @Override
            public ActionResult pendingActionResult(
                    PendingAction action,
                    String stopReason,
                    Long boardCursorParam,
                    java.util.function.Consumer<ActionResult> customizer) {
                throw new AssertionError("pendingActionResult should not run");
            }

            @Override
            public ActionResult stepYieldResult(PendingAction action, GameView gameView, String stopReason, Long boardCursorParam) {
                throw new AssertionError("stepYieldResult should not run");
            }

            @Override
            public ActionResult stackResolvedResult(PendingAction action, Long boardCursorParam) {
                throw new AssertionError("stackResolvedResult should not run");
            }

            @Override
            public UUID lowestStackObjectId(GameView gameView) {
                return null;
            }

            @Override
            public boolean stackContains(GameView gameView, UUID stackObjectId) {
                return false;
            }

            @Override
            public boolean clearPendingActionIfCurrent(PendingAction action) {
                throw new AssertionError("clearPendingActionIfCurrent should not run");
            }

            @Override
            public void sendBooleanOrDie(UUID gameId, boolean data, String sendContext) {
                throw new AssertionError("sendBooleanOrDie should not run");
            }

            @Override
            public UUID currentGameId() {
                return UUID.randomUUID();
            }

            @Override
            public GameView lastGameView() {
                return null;
            }

            @Override
            public int lastTurnNumber() {
                return 0;
            }

            @Override
            public boolean hasActiveGame() {
                return true;
            }

            @Override
            public boolean superseded() {
                return false;
            }

            @Override
            public boolean playerDead() {
                return false;
            }

            @Override
            public boolean gameEverStarted() {
                return false;
            }

            @Override
            public boolean clientRunning() {
                return true;
            }

            @Override
            public long lastActionableCallbackAt() {
                return 0;
            }

            @Override
            public long lastCallbackReceivedAt() {
                return 0;
            }

            @Override
            public void declareZombieGame(long absoluteIdleMs) {
                throw new AssertionError("declareZombieGame should not run");
            }

            @Override
            public boolean failedManaCast(UUID objectId) {
                return false;
            }

            @Override
            public void finalizePassPriorityResult(
                    BridgePassPriorityFlow flow,
                    String until,
                    int actionsPassed,
                    PendingAction action,
                    GameView view,
                    ActionResult result,
                    boolean actionPending) {
                decisionState.clearPendingPassPriorityFlowIfCurrent(flow);
            }
        };
        BridgePassPriorityFlowManager manager = new BridgePassPriorityFlowManager(
            processor,
            "TestPlayer",
            decisionState,
            context
        );
        processor.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BridgePassPriorityFlow flow = processor.submit(BridgeCommand.of(() -> manager.startPendingFlow(null, null)));

            Future<ActionResult> future = executor.submit(flow::awaitResult);
            failOnPendingRead.set(true);

            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> {
                    Throwable outerCause = ((ExecutionException) error).getCause();
                    assertThat(outerCause).isInstanceOf(ExecutionException.class);
                    assertThat(outerCause.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("pass_priority tick failed");
                });
            assertThat(decisionState.pendingPassPriorityFlow()).isNull();
        } finally {
            manager.shutdown();
            processor.shutdown("test done");
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void transitionToDecisionBoundaryTreatsReplacedTargetActionAsChanged() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID onlyTarget = UUID.randomUUID();
        GameView targetView = gameView(40);
        GameClientMessage targetMessage = new GameClientMessage(
            targetView,
            Collections.<String, Serializable>emptyMap(),
            "Choose a creature to copy",
            new CardsView(),
            Set.of(onlyTarget),
            true
        );
        PendingAction staleTargetAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_TARGET,
            targetMessage,
            "Choose a creature to copy",
            40
        );
        PendingAction replacementAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(gameView(41), Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            41
        );

        setField(handler, "pendingAction", replacementAction);

        assertThat(invokeDecisionBoundaryStatus(handler, staleTargetAction, "test"))
            .isEqualTo("CHANGED");
    }

    @Test
    void concedeFlowManagerReturnsTrueAfterKeepAliveTimeout() throws Exception {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<String> concedeThreadName = new AtomicReference<>();

        BridgeProcessor processor = new BridgeProcessor(
            "TestPlayer",
            Logger.getLogger(BridgeCallbackHandlerTest.class),
            ignored -> { }
        );
        BridgeProcessorState processorState = new BridgeProcessorState();
        BridgeGameState gameState = processorState.gameState();
        Session session = (Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerAction".equals(method.getName())) {
                    concedeThreadName.set(Thread.currentThread().getName());
                }
                return defaultReturnValue(method.getReturnType());
            }
        );
        BridgeConcedeFlowManager manager = new BridgeConcedeFlowManager(
            processor,
            processorState,
            () -> session,
            Logger.getLogger(BridgeCallbackHandlerTest.class),
            "TestPlayer",
            0
        );
        processor.start();

        try {
            BridgeConcedeFlow flow = processor.submit(BridgeCommand.of(() -> {
                gameState.setKeepAliveAfterGame(true);
                gameState.activateGame(gameId, playerId);
                return manager.startPendingFlow();
            }));

            assertThat(flow.awaitResult()).isTrue();
            assertThat(concedeThreadName.get()).startsWith("bridge-processor-TestPlayer");
        } finally {
            manager.shutdown();
            processor.shutdown("test done");
        }
    }

    @Test
    void handleCallbackStoresPendingManaAction() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();
        AtomicInteger sendPlayerManaTypeCalls = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        return true;
                    }
                    case "sendPlayerUUID" -> {
                        sendPlayerUuidCalls.incrementAndGet();
                        return true;
                    }
                    case "sendPlayerManaType" -> {
                        sendPlayerManaTypeCalls.incrementAndGet();
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        addActiveGame(handler, gameId, playerId);

        GameView manaView = gameView(77);
        ClientCallback callback = new ClientCallback(
            ClientCallbackMethod.GAME_PLAY_MANA,
            gameId,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay {1}"),
            false
        );

        handler.handleCallback(callback);
        handler.awaitProcessorIdle();

        PendingAction pending = (PendingAction) getField(handler, "pendingAction");
        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(0);
        assertThat(sendPlayerUuidCalls.get()).isEqualTo(0);
        assertThat(sendPlayerManaTypeCalls.get()).isEqualTo(0);
        assertThat(pending).isNotNull();
        assertThat(pending.method()).isEqualTo(ClientCallbackMethod.GAME_PLAY_MANA);
        assertThat(pending.message()).isEqualTo("Pay {1}");
        assertThat(pending.gameSeq()).isEqualTo(77);
    }

    @Test
    void transitionToDecisionBoundaryAutoHandlesStoredManaAction() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID forestId = UUID.randomUUID();
        AtomicInteger sendPlayerUuidCalls = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    sendPlayerUuidCalls.incrementAndGet();
                    assertThat(args[0]).isEqualTo(gameId);
                    assertThat(args[1]).isEqualTo(forestId);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        PermanentView forest = permanentView(forestId, "p1", "Forest", false);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> battlefield = (Map<UUID, Object>) getField(player, "battlefield");
        battlefield.put(forestId, forest);

        GameView manaView = gameView(60, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        setField(manaView, "canPlayObjects", playableObjects(Map.of(
            forestId, manaStats("{T}: Add {G}.")
        )));

        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay {G}"),
            "Pay {G}",
            60
        );
        setField(handler, "pendingAction", action);

        assertThat(invokeDecisionBoundaryStatus(handler, action, "test"))
            .isEqualTo("AUTO_HANDLED");
        assertThat(sendPlayerUuidCalls.get()).isEqualTo(1);
        assertThat(getField(handler, "pendingAction")).isNull();
    }

    @Test
    void aSpellTheServerCanPayForIsNotCancelledWhenNoSourceIsAPlainTap() throws Exception {
        // The board of a real game: after Queen Marchesa, Qarsi Revenant ({1}{B}{B}) was castable
        // with Desolate Mire, Orzhov Signet and two Gold tokens, and the bridge cancelled it.
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID mireId = UUID.randomUUID();
        UUID signetId = UUID.randomUUID();
        UUID goldId = UUID.randomUUID();
        UUID otherGoldId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger taps = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName()) && Boolean.FALSE.equals(args[1])) {
                    cancels.incrementAndGet();
                    return true;
                }
                if ("sendPlayerUUID".equals(method.getName())) {
                    taps.incrementAndGet();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        @SuppressWarnings("unchecked")
        Map<UUID, Object> battlefield = (Map<UUID, Object>) getField(player, "battlefield");
        battlefield.put(mireId, permanentView(mireId, "p14", "Desolate Mire", false));
        battlefield.put(signetId, permanentView(signetId, "p31", "Orzhov Signet", false));
        battlefield.put(goldId, permanentView(goldId, "p149", "Gold Token", false));
        battlefield.put(otherGoldId, permanentView(otherGoldId, "p174", "Gold Token", false));

        GameView manaView = gameView(60, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        Map<UUID, PlayableObjectStats> sources = new LinkedHashMap<>();
        sources.put(mireId, manaStats("{1}, {T}: Add {W}{B}."));
        sources.put(signetId, manaStats("{1}, {T}: Add {W}{B}."));
        sources.put(goldId, manaStats("Sacrifice this artifact: Add one mana of any color."));
        sources.put(otherGoldId, manaStats("Sacrifice this artifact: Add one mana of any color."));
        setField(manaView, "canPlayObjects", playableObjects(sources));

        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay {1}{B}{B}"),
            "Pay {1}{B}{B}",
            60
        );
        setField(handler, "pendingAction", action);

        invokeDecisionBoundaryStatus(handler, action, "test");
        assertThat(cancels.get()).as("spell cancelled although it could be paid").isEqualTo(0);
        assertThat(taps.get()).as("none of these sources is a plain tap").isEqualTo(0);
        assertThat(getField(handler, "pendingAction")).as("the payment is left to the model").isSameAs(action);
    }

    @Test
    void theAutoPayerTapsTheLandThatMakesTheNeededColor() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID mountainId = UUID.randomUUID();
        UUID forestId = UUID.randomUUID();
        List<Object> tapped = Collections.synchronizedList(new ArrayList<>());

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    tapped.add(args[1]);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        @SuppressWarnings("unchecked")
        Map<UUID, Object> battlefield = (Map<UUID, Object>) getField(player, "battlefield");
        battlefield.put(mountainId, permanentView(mountainId, "p1", "Mountain", false));
        battlefield.put(forestId, permanentView(forestId, "p2", "Forest", false));

        GameView manaView = gameView(60, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        Map<UUID, PlayableObjectStats> sources = new LinkedHashMap<>();
        sources.put(mountainId, manaStats("{T}: Add {R}."));
        sources.put(forestId, manaStats("{T}: Add {G}."));
        setField(manaView, "canPlayObjects", playableObjects(sources));

        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay {G}"),
            "Pay {G}",
            60
        );
        setField(handler, "pendingAction", action);

        assertThat(invokeDecisionBoundaryStatus(handler, action, "test")).isEqualTo("AUTO_HANDLED");
        assertThat(tapped).as("the Mountain cannot pay {G}").containsExactly(forestId);
    }

    @Test
    void aSpellThatCannotBePaidIsReportedToTheModelAndStaysCastable() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        CardsView hand = new CardsView();
        hand.put(spellId, cardView(spellId, "p50", "Qarsi Revenant"));

        GameView selectView = gameView(10, List.of(player), new CardsView());
        setField(selectView, "myPlayerId", playerId);
        setField(selectView, "myHand", hand);
        setField(selectView, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Qarsi Revenant"))));

        GameView manaView = gameView(11, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        setField(manaView, "canPlayObjects", playableObjects(Map.of()));
        String payPrompt = "Pay {1}{B}{B}<div><font object_id='" + spellId + "'>Qarsi Revenant</font></div>";
        GameClientMessage manaMessage = new GameClientMessage(
            manaView, Collections.<String, Serializable>emptyMap(), payPrompt);

        GameView nextView = gameView(12, List.of(player), new CardsView());
        setField(nextView, "myPlayerId", playerId);
        setField(nextView, "myHand", hand);
        setField(nextView, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Qarsi Revenant"))));
        GameClientMessage nextMessage = new GameClientMessage(
            nextView, Collections.<String, Serializable>emptyMap(), "Play spells and abilities");

        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", selectView);
        registerShortId(handler, spellId, "p50");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        assertThat(args[1]).isEqualTo(spellId);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_PLAY_MANA, gameId, manaMessage);
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        if (Boolean.FALSE.equals(args[1])) {
                            cancels.incrementAndGet();
                        }
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextMessage);
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(selectView, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            10
        ));

        var result = handler.chooseAction(null, "p50", null, null, null, null, null, null, null, null, null);

        assertThat(cancels.get()).as("no mana source at all, so the cast is cancelled").isEqualTo(1);
        assertThat(result.success).as("the spell was not cast").isFalse();
        assertThat(result.error_code).isEqualTo("spell_cancelled");
        assertThat(result.retryable).isTrue();
        assertThat(result.error).contains("Qarsi Revenant was not cast").contains("{1}{B}{B}");
        assertThat(result.action_type).isEqualTo("GAME_SELECT");
        assertThat(result.choices).as("the card can be tried again").anySatisfy(choice -> {
            assertThat(choice.get("id")).isEqualTo("p50");
            assertThat(choice.get("cast_cancelled_this_turn")).isEqualTo(true);
        });
    }

    @Test
    void payingByHandTapsTheSourceTheModelChose() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID goldId = UUID.randomUUID();
        List<Object> tapped = Collections.synchronizedList(new ArrayList<>());

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        @SuppressWarnings("unchecked")
        Map<UUID, Object> battlefield = (Map<UUID, Object>) getField(player, "battlefield");
        battlefield.put(goldId, permanentView(goldId, "p149", "Gold Token", false));

        GameView manaView = gameView(20, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        setField(manaView, "canPlayObjects", playableObjects(Map.of(
            goldId, manaStats("Sacrifice this artifact: Add one mana of any color."))));
        GameClientMessage nextMessage = new GameClientMessage(
            gameView(21, List.of(player), new CardsView()), Collections.<String, Serializable>emptyMap(),
            "Play spells and abilities");

        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", manaView);
        registerShortId(handler, goldId, "p149");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    tapped.add(args[1]);
                    enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, nextMessage);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay {B}"),
            "Pay {B}",
            20
        ));

        var result = handler.chooseAction(null, "p149", null, null, null, null, null, null, null, null, null);

        assertThat(result.success).isTrue();
        assertThat(tapped).containsExactly(goldId);
    }

    @Test
    void aBusyTurnWhereEveryCallMovesTheGameOnIsNeverCutOff() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();
        AtomicInteger seq = new AtomicInteger(100);
        AtomicInteger casts = new AtomicInteger();
        AtomicInteger passes = new AtomicInteger();

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        GameView first = castableSelectView(seq.get(), player, playerId, spellId);

        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", first);
        registerShortId(handler, spellId, "p50");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerUUID" -> {
                        casts.incrementAndGet();
                        GameView next = castableSelectView(seq.incrementAndGet(), player, playerId, spellId);
                        enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, new GameClientMessage(
                            next, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"));
                        return true;
                    }
                    case "sendPlayerBoolean" -> {
                        passes.incrementAndGet();
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(first, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            seq.get()
        ));

        for (int call = 1; call <= 40; call++) {
            var result = handler.chooseAction(null, "p50", null, null, null, null, null, null, null, null, null);
            assertThat(result.action_taken).as("call " + call).isNotEqualTo("auto_answered_stuck");
        }

        assertThat(casts.get()).as("all 40 casts reached the server").isEqualTo(40);
        assertThat(passes.get()).as("the bridge never answered for the model").isZero();
    }

    @Test
    void tenCallsInARowThatChangeNothingGetThePromptAnsweredForTheModel() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();
        AtomicInteger passes = new AtomicInteger();

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        GameView first = castableSelectView(10, player, playerId, spellId);
        GameView next = castableSelectView(11, player, playerId, spellId);

        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", first);
        registerShortId(handler, spellId, "p50");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    assertThat(args[1]).isEqualTo(false);
                    passes.incrementAndGet();
                    enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, new GameClientMessage(
                        next, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"));
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage(first, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            10
        ));

        for (int call = 1; call <= 10; call++) {
            var result = handler.chooseAction(null, "p404", null, null, null, null, null, null, null, null, null);
            assertThat(result.error_code).as("call " + call).isEqualTo("invalid_choice");
        }
        assertThat(passes.get()).as("ten calls that changed nothing are still the model's").isZero();

        var stuck = handler.chooseAction(null, "p404", null, null, null, null, null, null, null, null, null);

        assertThat(stuck.action_taken).isEqualTo("auto_answered_stuck");
        assertThat(stuck.warning).contains("did not change the game").contains("passed_priority");
        assertThat(passes.get()).as("the bridge passed the stuck prompt once").isEqualTo(1);

        var after = handler.chooseAction(null, "p404", null, null, null, null, null, null, null, null, null);
        assertThat(after.error_code).as("the next prompt is the model's again").isEqualTo("invalid_choice");
        assertThat(passes.get()).isEqualTo(1);
    }

    @Test
    void passPriorityAnswersAPromptTheModelKeptStoppingOn() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        setField(player, "manaPool", manaPoolView(1, 0, 1, 0, 0, 0));
        GameView manaView = gameView(61, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        setField(manaView, "canPlayObjects", new PlayableObjectsList());
        GameView askView = gameView(62, List.of(player), new CardsView());
        setField(askView, "myPlayerId", playerId);

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerBoolean".equals(method.getName())) {
                    assertThat(args[1]).isEqualTo(false);
                    cancels.incrementAndGet();
                    enqueueCallback(handler, ClientCallbackMethod.GAME_ASK, gameId, new GameClientMessage(
                        askView, Collections.<String, Serializable>emptyMap(), "Keep this card?"));
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", manaView);
        setField(handler, "pendingAction", new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay 1 mana"),
            "Pay 1 mana",
            61
        ));

        for (int call = 1; call <= 10; call++) {
            ActionResult result = handler.passPriority(null, null);
            assertThat(result.stop_reason).as("call " + call).isEqualTo("non_priority_action");
            assertThat(result.action_type).isEqualTo("GAME_PLAY_MANA");
        }
        assertThat(cancels.get()).isZero();

        ActionResult result = handler.passPriority(null, null);

        assertThat(cancels.get()).as("the bridge cancelled the payment the model kept stopping on").isEqualTo(1);
        assertThat(result.stop_reason).isEqualTo("non_priority_action");
        assertThat(result.action_type).as("the model gets the next prompt").isEqualTo("GAME_ASK");
    }

    @Test
    void aSpellsModeIsTheModelsChoiceEvenWhenItWasCastWithAManaPlan() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        AtomicInteger picks = new AtomicInteger();
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    picks.incrementAndGet();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        interactions.setManaPlan(List.of(new BridgeManaPlanEntry("tap", "p67"), new BridgeManaPlanEntry("tap", "p77")), true);

        Map<UUID, String> modes = new LinkedHashMap<>();
        modes.put(UUID.randomUUID(), "1. Boros Charm deals 4 damage to target player or planeswalker.");
        modes.put(UUID.randomUUID(), "2. Permanents you control gain indestructible until end of turn.");
        modes.put(UUID.randomUUID(), "3. Target creature gains double strike until end of turn.");
        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_CHOOSE_ABILITY,
            new AbilityPickerView(gameView(882), modes, "Choose mode (selected 0 of 1, min 1)<br>Boros Charm"),
            "Choose mode (selected 0 of 1, min 1)<br>Boros Charm",
            882
        );
        setField(handler, "pendingAction", action);

        assertThat(invokeDecisionBoundaryStatus(handler, action, "test")).isEqualTo("READY");
        assertThat(picks.get()).as("the bridge did not pick a mode").isZero();
        assertThat(getField(handler, "pendingAction")).as("the mode prompt waits for the model").isSameAs(action);
        assertThat(interactions.manaPlan()).as("the plan is kept for paying").hasSize(2);
    }

    @Test
    void aManaPlanStillPicksTheManaAbilityItNamed() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID colorless = UUID.randomUUID();
        UUID blackBlack = UUID.randomUUID();
        List<Object> picked = Collections.synchronizedList(new ArrayList<>());
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    picked.add(args[1]);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        interactions.setManaPlan(List.of(new BridgeManaPlanEntry("tap", "p13")), true);
        interactions.setManaPlanAbilityIndex(1);

        Map<UUID, String> abilities = new LinkedHashMap<>();
        abilities.put(colorless, "1. {T}: Add {C}.");
        abilities.put(blackBlack, "2. {B/R}, {T}: Add {B}{B}.");
        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_CHOOSE_ABILITY,
            new AbilityPickerView(gameView(887), abilities, "Choose spell or ability to play<br>Graven Cairns"),
            "Choose spell or ability to play<br>Graven Cairns",
            887
        );
        setField(handler, "pendingAction", action);

        assertThat(invokeDecisionBoundaryStatus(handler, action, "test")).isEqualTo("AUTO_HANDLED");
        assertThat(picked).containsExactly(blackBlack);
    }

    @Test
    void aTwoFacedLandTheServerListsTwiceIsCountedOnce() throws Exception {
        // Codex game, v0.7.9: Queen Marchesa ({1}{R}{W}{B}) with Mountain, Plains, Blightstep Pathway and Boros
        // Signet. The server lists the Pathway under its own id and under its main card's, so the bridge counted
        // four plain sources, tapped the three lands and left the Signet's {1} unpayable.
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID queenId = UUID.randomUUID();
        UUID mountainId = UUID.randomUUID();
        UUID plainsId = UUID.randomUUID();
        UUID pathwayId = UUID.randomUUID();
        UUID pathwayMainCardId = UUID.randomUUID();
        UUID signetId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();
        List<Object> tapped = Collections.synchronizedList(new ArrayList<>());
        client.setSession(recordingSession(tapped, cancels));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        putPermanents(player, mountainId, "p77", "Mountain", plainsId, "p22", "Plains",
            pathwayId, "p211", "Blightstep Pathway", signetId, "p43", "Boros Signet");
        Map<UUID, PlayableObjectStats> sources = new LinkedHashMap<>();
        sources.put(mountainId, manaStats("{T}: Add {R}."));
        sources.put(plainsId, manaStats("{T}: Add {W}."));
        sources.put(pathwayId, manaStats("{T}: Add {B}."));
        sources.put(pathwayMainCardId, manaStats("{T}: Add {B}."));
        sources.put(signetId, manaStats("{1}, {T}: Add {R}{W}."));

        PendingAction action = manaPrompt(gameId, player, playerId, 60, "Pay {1}{R}{W}{B}" + paidFor(queenId, "Queen Marchesa"), sources);
        setField(handler, "pendingAction", action);
        invokeDecisionBoundaryStatus(handler, action, "test");

        assertThat(tapped).as("three lands cannot pay four mana, so the payment is the model's").isEmpty();
        assertThat(cancels.get()).isZero();
        assertThat(getField(handler, "pendingAction")).isSameAs(action);
    }

    @Test
    void aManaPlanPaysASignetsOwnCostWithALandTheSpellNoLongerNeeds() throws Exception {
        // Codex game, v0.7.9: mana_plan "p77,p43,p211,p22" for Queen Marchesa. The Signet's {1} took the next
        // entry, the Pathway (the only black source), and {B} was left unpaid. The Plains pays it instead.
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID queenId = UUID.randomUUID();
        UUID mountainId = UUID.randomUUID();
        UUID signetId = UUID.randomUUID();
        UUID pathwayId = UUID.randomUUID();
        UUID plainsId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();
        List<Object> tapped = Collections.synchronizedList(new ArrayList<>());
        client.setSession(recordingSession(tapped, cancels));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        putPermanents(player, mountainId, "p77", "Mountain", signetId, "p43", "Boros Signet",
            pathwayId, "p211", "Blightstep Pathway", plainsId, "p22", "Plains");
        for (Object[] shortId : new Object[][]{{mountainId, "p77"}, {signetId, "p43"}, {pathwayId, "p211"}, {plainsId, "p22"}}) {
            registerShortId(handler, (UUID) shortId[0], (String) shortId[1]);
        }
        Map<UUID, PlayableObjectStats> untapped = new LinkedHashMap<>();
        untapped.put(mountainId, manaStats("{T}: Add {R}."));
        untapped.put(signetId, manaStats("{1}, {T}: Add {R}{W}."));
        untapped.put(pathwayId, manaStats("{T}: Add {B}."));
        untapped.put(plainsId, manaStats("{T}: Add {W}."));
        String queen = paidFor(queenId, "Queen Marchesa");

        BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        interactions.advanceTurn(gameView(70, List.of(player), new CardsView()));
        interactions.setManaPlan(List.of(new BridgeManaPlanEntry("tap", "p77"), new BridgeManaPlanEntry("tap", "p43"),
            new BridgeManaPlanEntry("tap", "p211"), new BridgeManaPlanEntry("tap", "p22")), true);

        payAutomatically(handler, manaPrompt(gameId, player, playerId, 70, "Pay {1}{R}{W}{B}" + queen, untapped));
        untapped.remove(mountainId);
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 71, "Pay {1}{W}{B}" + queen, untapped));
        untapped.remove(signetId);
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 72, "Pay {1}" + paidFor(signetId, "Boros Signet"), untapped));
        untapped.remove(plainsId);
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 73, "Pay {B}" + queen, untapped));

        assertThat(tapped).containsExactly(mountainId, signetId, plainsId, pathwayId);
        assertThat(cancels.get()).isZero();
    }

    @Test
    void aSignetTheModelTapsIsPaidWithALandTheSpellNoLongerNeeds() throws Exception {
        // The same board paid by hand: the Mountain already paid {R} and the model taps the Signet. Auto-tap pays
        // the Signet's {1} with the Plains, not the Pathway it would have taken first, and then {B} with the Pathway.
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID queenId = UUID.randomUUID();
        UUID mountainId = UUID.randomUUID();
        UUID signetId = UUID.randomUUID();
        UUID pathwayId = UUID.randomUUID();
        UUID plainsId = UUID.randomUUID();
        List<Object> tapped = Collections.synchronizedList(new ArrayList<>());

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        putPermanents(player, mountainId, "p77", "Mountain", signetId, "p43", "Boros Signet",
            pathwayId, "p211", "Blightstep Pathway", plainsId, "p22", "Plains");
        Map<UUID, PlayableObjectStats> untapped = new LinkedHashMap<>();
        untapped.put(signetId, manaStats("{1}, {T}: Add {R}{W}."));
        untapped.put(pathwayId, manaStats("{T}: Add {B}."));
        untapped.put(plainsId, manaStats("{T}: Add {W}."));
        String queen = paidFor(queenId, "Queen Marchesa");
        PendingAction first = manaPrompt(gameId, player, playerId, 80, "Pay {1}{W}{B}" + queen, untapped);
        Map<UUID, PlayableObjectStats> landsLeft = new LinkedHashMap<>(untapped);
        landsLeft.remove(signetId);
        GameClientMessage signetCost = (GameClientMessage) manaPrompt(
            gameId, player, playerId, 81, "Pay {1}" + paidFor(signetId, "Boros Signet"), landsLeft).data();
        GameClientMessage black = (GameClientMessage) manaPrompt(
            gameId, player, playerId, 82, "Pay {B}" + queen, Map.of(pathwayId, manaStats("{T}: Add {B}."))).data();
        GameClientMessage priority = new GameClientMessage(gameView(83, List.of(player), new CardsView()),
            Collections.<String, Serializable>emptyMap(), "Play spells and abilities");

        addActiveGame(handler, gameId, playerId);
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", ((GameClientMessage) first.data()).getGameView());
        registerShortId(handler, signetId, "p43");
        registerShortId(handler, pathwayId, "p211");
        registerShortId(handler, plainsId, "p22");
        BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        interactions.advanceTurn(((GameClientMessage) first.data()).getGameView());
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    tapped.add(args[1]);
                    GameClientMessage next = switch (tapped.size()) {
                        case 1 -> signetCost;
                        case 2 -> black;
                        default -> priority;
                    };
                    enqueueCallback(handler, next == priority ? ClientCallbackMethod.GAME_SELECT
                        : ClientCallbackMethod.GAME_PLAY_MANA, gameId, next);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        setField(handler, "pendingAction", first);

        var result = handler.chooseAction(null, "p43", null, null, null, null, null, null, null, null, null);

        assertThat(result.success).isTrue();
        assertThat(tapped).containsExactly(signetId, plainsId, pathwayId);
    }

    @Test
    void aManaPlanWithoutAbilityNumbersPicksThePainlandColorsTheSpellNeeds() throws Exception {
        // Claude game, v0.7.9: mana_plan "p24,p19,p39,p23" (Sulfurous Springs, Caves of Koilos, Plains, Pillarverge
        // Pathway) for Queen Marchesa {1}{R}{W}{B}, auto_tap=false. Both painlands made {C} and {R}{B} was left.
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID queenId = UUID.randomUUID();
        UUID springsId = UUID.randomUUID();
        UUID cavesId = UUID.randomUUID();
        UUID plainsId = UUID.randomUUID();
        UUID pathwayId = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();
        List<Object> sent = Collections.synchronizedList(new ArrayList<>());
        client.setSession(recordingSession(sent, cancels));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        putPermanents(player, springsId, "p24", "Sulfurous Springs", cavesId, "p19", "Caves of Koilos",
            plainsId, "p39", "Plains", pathwayId, "p23", "Pillarverge Pathway");
        for (Object[] shortId : new Object[][]{{springsId, "p24"}, {cavesId, "p19"}, {plainsId, "p39"}, {pathwayId, "p23"}}) {
            registerShortId(handler, (UUID) shortId[0], (String) shortId[1]);
        }
        String[] springs = {"{T}: Add {C}.", "{T}: Add {B}. Sulfurous Springs deals 1 damage to you.",
            "{T}: Add {R}. Sulfurous Springs deals 1 damage to you."};
        String[] caves = {"{T}: Add {C}.", "{T}: Add {W}. Caves of Koilos deals 1 damage to you.",
            "{T}: Add {B}. Caves of Koilos deals 1 damage to you."};
        Map<UUID, PlayableObjectStats> untapped = new LinkedHashMap<>();
        untapped.put(springsId, manaStats(springs));
        untapped.put(cavesId, manaStats(caves));
        untapped.put(plainsId, manaStats("{T}: Add {W}."));
        untapped.put(pathwayId, manaStats("{T}: Add {W}."));
        String queen = paidFor(queenId, "Queen Marchesa");

        BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
        interactions.advanceTurn(gameView(90, List.of(player), new CardsView()));
        interactions.setManaPlan(List.of(new BridgeManaPlanEntry("tap", "p24"), new BridgeManaPlanEntry("tap", "p19"),
            new BridgeManaPlanEntry("tap", "p39"), new BridgeManaPlanEntry("tap", "p23")), false);

        payAutomatically(handler, manaPrompt(gameId, player, playerId, 90, "Pay {1}{R}{W}{B}" + queen, untapped));
        untapped.remove(springsId);
        Map<UUID, String> springsChoices = numberedChoices(springs);
        payAutomatically(handler, abilityPrompt(gameId, 91, springsId, "Sulfurous Springs", springsChoices));
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 92, "Pay {1}{W}{B}" + queen, untapped));
        untapped.remove(cavesId);
        Map<UUID, String> cavesChoices = numberedChoices(caves);
        payAutomatically(handler, abilityPrompt(gameId, 93, cavesId, "Caves of Koilos", cavesChoices));
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 94, "Pay {1}{W}" + queen, untapped));
        untapped.remove(plainsId);
        payAutomatically(handler, manaPrompt(gameId, player, playerId, 95, "Pay {1}" + queen, untapped));

        List<UUID> springsIds = new ArrayList<>(springsChoices.keySet());
        List<UUID> cavesIds = new ArrayList<>(cavesChoices.keySet());
        assertThat(sent).containsExactly(springsId, springsIds.get(2), cavesId, cavesIds.get(2), plainsId, pathwayId);
        assertThat(cancels.get()).isZero();
    }

    private static Session recordingSession(List<Object> sent, AtomicInteger cancels) {
        return (Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("sendPlayerUUID".equals(method.getName())) {
                    sent.add(args[1]);
                    return true;
                }
                if ("sendPlayerBoolean".equals(method.getName()) && Boolean.FALSE.equals(args[1])) {
                    cancels.incrementAndGet();
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        );
    }

    /** Puts permanents on the player's battlefield, in order: id, short id and name for each. */
    private static void putPermanents(PlayerView player, Object... idShortIdName) throws Exception {
        @SuppressWarnings("unchecked")
        Map<UUID, Object> battlefield = (Map<UUID, Object>) getField(player, "battlefield");
        for (int i = 0; i < idShortIdName.length; i += 3) {
            UUID id = (UUID) idShortIdName[i];
            battlefield.put(id, permanentView(id, (String) idShortIdName[i + 1], (String) idShortIdName[i + 2], false));
        }
    }

    /** The part of a server prompt that names what it is for, as in "Pay {1}<div>...Boros Signet...</div>". */
    private static String paidFor(UUID objectId, String name) {
        return "<div style='font-size:11pt'><font color='#B0C4DE' object_id='" + objectId + "'>" + name
            + "</font> [" + objectId.toString().substring(0, 3) + "]</div>";
    }

    private static PendingAction manaPrompt(UUID gameId, PlayerView player, UUID playerId, int seq, String prompt,
            Map<UUID, PlayableObjectStats> untapped) throws Exception {
        GameView view = gameView(seq, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);
        setField(view, "canPlayObjects", playableObjects(untapped));
        return new PendingAction(gameId, ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(view, Collections.<String, Serializable>emptyMap(), prompt), prompt, seq);
    }

    private static PendingAction abilityPrompt(UUID gameId, int seq, UUID sourceId, String name,
            Map<UUID, String> choices) throws Exception {
        String message = "Choose spell or ability to play<br><font color='#B0C4DE' object_id='" + sourceId + "'>"
            + name + "</font> [" + sourceId.toString().substring(0, 3) + "]";
        return new PendingAction(gameId, ClientCallbackMethod.GAME_CHOOSE_ABILITY,
            new AbilityPickerView(gameView(seq), choices, message), message, seq);
    }

    private static Map<UUID, String> numberedChoices(String... abilities) {
        Map<UUID, String> choices = new LinkedHashMap<>();
        for (int i = 0; i < abilities.length; i++) {
            choices.put(UUID.randomUUID(), (i + 1) + ". " + abilities[i]);
        }
        return choices;
    }

    private static void payAutomatically(BridgeCallbackHandler handler, PendingAction action) throws Exception {
        setField(handler, "pendingAction", action);
        assertThat(invokeDecisionBoundaryStatus(handler, action, "test"))
            .as("the bridge answers \"" + action.message() + "\" itself").isEqualTo("AUTO_HANDLED");
    }

    @Test
    void aPriorityPromptClearsALeftoverManaPlan() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();
        try {
            UUID gameId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            PlayerView player = playerView(playerId, "TestPlayer", "p99");
            GameView view = gameView(891, List.of(player), new CardsView());
            setField(view, "myPlayerId", playerId);
            addActiveGame(handler, gameId, playerId);
            setField(handler, "currentGameId", gameId);
            setField(handler, "lastGameView", view);
            BridgeInteractionState interactions = (BridgeInteractionState) getProcessorStateField(handler, "interactionState");
            interactions.advanceTurn(view); // same turn as the prompt below, so no turn change clears the plan
            interactions.setManaPlan(List.of(), true);

            enqueueCallback(handler, ClientCallbackMethod.GAME_SELECT, gameId, new GameClientMessage(
                view, Collections.<String, Serializable>emptyMap(), "Play instants and activated abilities"));
            handler.awaitProcessorIdle();

            assertThat(interactions.manaPlan()).as("the used-up plan cannot pick a later trigger's modes").isNull();
        } finally {
            client.stop();
        }
    }

    private static GameView castableSelectView(int seq, PlayerView player, UUID playerId, UUID spellId) throws Exception {
        CardsView hand = new CardsView();
        hand.put(spellId, cardView(spellId, "p50", "Lightning Bolt"));
        GameView view = gameView(seq, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);
        setField(view, "myHand", hand);
        setField(view, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Lightning Bolt"))));
        return view;
    }

    @Test
    void passPriorityReturnsManualManaChoiceWhenPoolSelectionIsAmbiguous() throws Exception {
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        BridgeCallbackHandler handler = client.getCallbackHandler();

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicInteger sendPlayerBooleanCalls = new AtomicInteger();
        AtomicInteger sendPlayerManaTypeCalls = new AtomicInteger();

        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendPlayerBoolean" -> {
                        sendPlayerBooleanCalls.incrementAndGet();
                        return true;
                    }
                    case "sendPlayerManaType" -> {
                        sendPlayerManaTypeCalls.incrementAndGet();
                        return true;
                    }
                    default -> {
                        return defaultReturnValue(method.getReturnType());
                    }
                }
            }
        ));

        PlayerView player = playerView(playerId, "TestPlayer", "p99");
        setField(player, "manaPool", manaPoolView(1, 0, 1, 0, 0, 0));

        GameView manaView = gameView(61, List.of(player), new CardsView());
        setField(manaView, "myPlayerId", playerId);
        setField(manaView, "canPlayObjects", new PlayableObjectsList());

        PendingAction action = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_PLAY_MANA,
            new GameClientMessage(manaView, Collections.<String, Serializable>emptyMap(), "Pay 1 mana"),
            "Pay 1 mana",
            61
        );
        setField(handler, "currentGameId", gameId);
        setField(handler, "lastGameView", manaView);
        setField(handler, "pendingAction", action);

        ActionResult result = handler.passPriority(null, null);

        assertThat(sendPlayerBooleanCalls.get()).isEqualTo(0);
        assertThat(sendPlayerManaTypeCalls.get()).isEqualTo(0);
        assertThat(result.stop_reason).isEqualTo("non_priority_action");
        assertThat(result.action_pending).isTrue();
        assertThat(result.action_type).isEqualTo("GAME_PLAY_MANA");
        assertThat(result.response_type).isEqualTo("select");
        assertThat(result.choices).hasSize(2);
        assertThat(result.choices)
            .extracting(choice -> choice.get("choice_type"))
            .containsExactly("pool_mana", "pool_mana");
        assertThat(result.choices)
            .extracting(choice -> choice.get("name"))
            .containsExactly("Blue", "Red");
    }

    private static GameClientMessage multiAmountMessage(List<MultiAmountMessage> items, int min, int max) {
        return new GameClientMessage(null, Collections.<String, Serializable>emptyMap(), items, min, max);
    }

    private static List<BridgeLogEntry> sampleBridgeLogEvents() {
        return List.of(
            bridgeLogEntry(0, "BEGIN_TURN", 1, "Alice", "Alice", null, null),
            bridgeLogEntry(1, "LAND_PLAYED", 1, "Alice", "Alice", "Island", null),
            bridgeLogEntry(2, "BEGIN_TURN", 2, "Bob", "Bob", null, null),
            bridgeLogEntry(3, "LAND_PLAYED", 2, "Bob", "Bob", "Swamp", null),
            bridgeLogEntry(4, "BEGIN_TURN", 3, "Alice", "Alice", null, null),
            bridgeLogEntry(5, "SPELL_CAST", 3, "Alice", "Alice", "Lightning Bolt", "Bob")
        );
    }

    private static String sampleBridgeLogText() {
        return String.join("\n",
            "Alice turn 1:",
            "Alice played Island",
            "Bob turn 1:",
            "Bob played Swamp",
            "Alice turn 2:",
            "Alice cast Lightning Bolt targeting Bob"
        );
    }

    private static BridgeLogEntry bridgeLogEntry(
            int index,
            String type,
            int turn,
            String activePlayer,
            String player,
            String cardName,
            String targetName) {
        return new BridgeLogEntry(
            index,
            index,
            type,
            turn,
            "PRECOMBAT_MAIN",
            "PRECOMBAT_MAIN",
            activePlayer,
            player,
            cardName,
            targetName,
            0,
            true
        );
    }

    @SuppressWarnings("removal")
    @Test
    void sideboardingSubmitsTheUnchangedDeckLikeTheComputerPlayer() throws Exception {
        UUID tableId = UUID.randomUUID();
        List<Object[]> submitted = new ArrayList<>();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> {
                if ("submitDeck".equals(method.getName())) {
                    submitted.add(args);
                    return true;
                }
                return defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        DeckCardLists deck = new DeckCardLists();
        deck.getCards().add(new DeckCardInfo("Island", "1", "M21", 40));
        handler.setDeckList(deck);

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.SIDEBOARD,
            tableId,
            new TableClientMessage().withTable(tableId, null).withTime(180),
            false
        ));
        handler.awaitProcessorIdle();

        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0)[0]).isEqualTo(tableId);
        assertThat(submitted.get(0)[1]).isSameAs(deck);
    }

    @Test
    void matchModeStaysForTheNextGameWithFreshStateAndStopsWhenTheMatchEnds() throws Exception {
        UUID tableId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID firstGame = UUID.randomUUID();
        UUID secondGame = UUID.randomUUID();
        BridgeMageClient client = new BridgeMageClient("TestPlayer");
        client.setSession((Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "joinGame" -> true;
                case "getGameChatId" -> Optional.empty();
                default -> defaultReturnValue(method.getReturnType());
            }
        ));
        BridgeCallbackHandler handler = client.getCallbackHandler();
        handler.setContinueMatchAfterGame(true);
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");

        startGame(handler, firstGame, tableId, playerId);
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_OVER,
            firstGame,
            new GameClientMessage(gameView(9), Collections.<String, Serializable>emptyMap(), "Player Opponent is the winner"),
            false
        ));
        handler.handleCallback(new ClientCallback(ClientCallbackMethod.END_GAME_INFO, firstGame, gameEnd(false), false));
        handler.awaitProcessorIdle();
        assertThat(client.isRunning()).isTrue();
        assertThat(hasActiveGame(handler, firstGame)).isFalse();

        gameState.markPlayerDead();
        startGame(handler, secondGame, tableId, playerId);
        handler.awaitProcessorIdle();
        assertThat(hasActiveGame(handler, secondGame)).isTrue();
        assertThat(gameState.playerDead()).isFalse();
        assertThat(handler.getGameState(null).available).isFalse();

        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.GAME_OVER,
            secondGame,
            new GameClientMessage(gameView(4), Collections.<String, Serializable>emptyMap(), "Player TestPlayer is the winner"),
            false
        ));
        handler.handleCallback(new ClientCallback(ClientCallbackMethod.END_GAME_INFO, secondGame, gameEnd(true), false));
        long deadline = System.currentTimeMillis() + 5000;
        while (client.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(client.isRunning()).isFalse();
    }

    private static void startGame(BridgeCallbackHandler handler, UUID gameId, UUID tableId, UUID playerId) {
        handler.handleCallback(new ClientCallback(
            ClientCallbackMethod.START_GAME,
            gameId,
            new TableClientMessage().withTable(tableId, null).withPlayer(playerId),
            false
        ));
    }

    private static mage.view.GameEndView gameEnd(boolean matchOver) throws Exception {
        mage.view.MatchView match = (mage.view.MatchView) UNSAFE.allocateInstance(mage.view.MatchView.class);
        setDeclaredField(match, "endTime", matchOver ? new java.util.Date() : null);
        mage.view.GameEndView end = (mage.view.GameEndView) UNSAFE.allocateInstance(mage.view.GameEndView.class);
        setDeclaredField(end, "matchView", match);
        setDeclaredField(end, "matchInfo", matchOver ? "You won the match!" : "You need one more win to win the match.");
        return end;
    }

    private static void setDeclaredField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final Unsafe UNSAFE = initUnsafe();

    @SuppressWarnings("removal")
    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to access Unsafe", e);
        }
    }

    private static void addActiveGame(BridgeCallbackHandler handler, UUID gameId) throws Exception {
        addActiveGame(handler, gameId, UUID.randomUUID());
    }

    private static void addActiveGame(BridgeCallbackHandler handler, UUID gameId, UUID playerId) throws Exception {
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        gameState.activateGame(gameId, playerId);
    }

    /** Publishes this view and decision, as a server update followed by a prompt would. */
    private static void projectDecision(
            BridgeCallbackHandler handler,
            UUID gameId,
            UUID playerId,
            GameView view,
            PendingAction pendingAction) throws Exception {
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");
        processor.submit(BridgeCommand.of(() -> {
            gameState.activateGame(gameId, playerId);
            publishedQueryState.projectGameState(view, 1, "test_init");
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));
    }

    private static RevealedView revealedView(String name, CardView... cards) throws Exception {
        RevealedView view = (RevealedView) UNSAFE.allocateInstance(RevealedView.class);
        CardsView cardsView = new CardsView();
        for (CardView card : cards) {
            cardsView.put(card.getId(), card);
        }
        setField(view, "name", name);
        setField(view, "cards", cardsView);
        return view;
    }

    private static void setCurrentChatId(BridgeCallbackHandler handler, UUID gameId, UUID chatId) throws Exception {
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        gameState.setCurrentChatId(gameId, chatId);
    }

    private static boolean hasActiveGame(BridgeCallbackHandler handler, UUID gameId) throws Exception {
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        return gameState.isCurrentActiveGame(gameId);
    }

    private static void registerShortId(BridgeCallbackHandler handler, UUID uuid, String shortId) throws Exception {
        BridgeProcessorServices processorServices = (BridgeProcessorServices) getField(handler, "processorServices");
        processorServices.shortIds().register(uuid, shortId);
    }

    private static void enqueueCallback(
            BridgeCallbackHandler handler,
            ClientCallbackMethod method,
            UUID gameId,
            Object data) {
        handler.handleCallback(new ClientCallback(method, gameId, data, false));
    }

    private static GameView gameView(int gameSeq, UUID... stackObjectIds) throws Exception {
        return gameView(gameSeq, 1, null, stackObjectIds);
    }

    private static GameView gameView(int gameSeq, int turn, PhaseStep step, UUID... stackObjectIds) throws Exception {
        GameView view = (GameView) UNSAFE.allocateInstance(GameView.class);
        CardsView stack = new CardsView();
        for (UUID stackObjectId : stackObjectIds) {
            stack.put(stackObjectId, null);
        }
        setField(view, "players", List.of());
        setField(view, "myHand", new CardsView());
        setField(view, "exiles", List.of());
        setField(view, "lookedAt", List.of());
        setField(view, "stack", stack);
        setField(view, "activePlayerName", "TestPlayer");
        setField(view, "step", step);
        setField(view, "priorityPlayerName", "TestPlayer");
        setIntField(view, "turn", turn);
        setIntField(view, "gameSeq", gameSeq);
        return view;
    }

    private static GameView gameView(int gameSeq, List<PlayerView> players, CardsView stack) throws Exception {
        GameView view = (GameView) UNSAFE.allocateInstance(GameView.class);
        setField(view, "players", players);
        setField(view, "myHand", new CardsView());
        setField(view, "stack", stack);
        setField(view, "exiles", List.of());
        setField(view, "lookedAt", List.of());
        setField(view, "activePlayerName", "TestPlayer");
        setField(view, "priorityPlayerName", "TestPlayer");
        setIntField(view, "turn", 1);
        setIntField(view, "gameSeq", gameSeq);
        return view;
    }

    private static PlayerView playerView(UUID playerId, String name, String shortId) throws Exception {
        PlayerView view = (PlayerView) UNSAFE.allocateInstance(PlayerView.class);
        setField(view, "playerId", playerId);
        setField(view, "name", name);
        setField(view, "shortId", shortId);
        setField(view, "life", 20);
        setField(view, "libraryCount", 53);
        setField(view, "handCount", 0);
        setField(view, "isActive", true);
        setField(view, "battlefield", new LinkedHashMap<UUID, Object>());
        setField(view, "graveyard", new CardsView());
        setField(view, "exile", new CardsView());
        setField(view, "commandList", List.of());
        setField(view, "counters", List.of());
        setField(view, "manaPool", null);
        return view;
    }

    private static PermanentView permanentView(UUID id, String shortId, String name, boolean tapped) throws Exception {
        PermanentView view = (PermanentView) UNSAFE.allocateInstance(PermanentView.class);
        setField(view, "id", id);
        setField(view, "shortId", shortId);
        setField(view, "name", name);
        setField(view, "displayName", name);
        setField(view, "manaCostLeftStr", List.of());
        setField(view, "manaCostRightStr", List.of());
        setField(view, "rules", List.of());
        setField(view, "cardTypes", List.of(CardType.LAND));
        setField(view, "subTypes", new SubTypes());
        setField(view, "superTypes", List.of());
        setField(view, "tapped", tapped);
        return view;
    }

    private static CardView cardView(UUID id, String shortId, String name) throws Exception {
        CardView view = (CardView) UNSAFE.allocateInstance(CardView.class);
        setField(view, "id", id);
        setField(view, "shortId", shortId);
        setField(view, "name", name);
        setField(view, "displayName", name);
        setField(view, "manaCostLeftStr", List.of());
        setField(view, "manaCostRightStr", List.of());
        setField(view, "rules", List.of());
        setField(view, "cardTypes", List.of());
        setField(view, "subTypes", new SubTypes());
        setField(view, "superTypes", List.of());
        return view;
    }

    private static ManaPoolView manaPoolView(int red, int green, int blue, int white, int black, int colorless)
            throws Exception {
        ManaPoolView view = (ManaPoolView) UNSAFE.allocateInstance(ManaPoolView.class);
        setField(view, "red", red);
        setField(view, "green", green);
        setField(view, "blue", blue);
        setField(view, "white", white);
        setField(view, "black", black);
        setField(view, "colorless", colorless);
        return view;
    }

    private static PlayableObjectsList playableObjects(Map<UUID, PlayableObjectStats> objects) throws Exception {
        PlayableObjectsList list = new PlayableObjectsList();
        setField(list, "objects", new LinkedHashMap<>(objects));
        return list;
    }

    private static PlayableObjectStats manaStats(String... manaAbilities) throws Exception {
        PlayableObjectStats stats = new PlayableObjectStats();
        List<Object> records = new java.util.ArrayList<>();
        for (int i = 0; i < manaAbilities.length; i++) {
            records.add(playableObjectRecord(UUID.randomUUID(), manaAbilities[i]));
        }
        setField(stats, "allManaAbilities", records);
        setField(stats, "basicManaAbilities", records);
        return stats;
    }

    private static PlayableObjectStats playStats(String... playAbilities) throws Exception {
        PlayableObjectStats stats = new PlayableObjectStats();
        List<Object> records = new java.util.ArrayList<>();
        for (int i = 0; i < playAbilities.length; i++) {
            records.add(playableObjectRecord(UUID.randomUUID(), playAbilities[i]));
        }
        setField(stats, "basicPlayAbilities", records);
        return stats;
    }

    private static Object playableObjectRecord(UUID id, String value) throws Exception {
        Class<?> recordClass = Class.forName("mage.players.PlayableObjectRecord");
        var ctor = recordClass.getDeclaredConstructor(UUID.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, value);
    }

    private static StackAbilityView stackAbilityView(UUID id, CardView sourceCard, String rule, UUID targetId)
            throws Exception {
        StackAbilityView view = (StackAbilityView) UNSAFE.allocateInstance(StackAbilityView.class);
        setField(view, "id", id);
        setField(view, "name", "Ability");
        setField(view, "displayName", "Ability");
        setField(view, "sourceCard", sourceCard);
        setField(view, "rules", List.of(rule));
        setField(view, "targets", List.of(targetId));
        setField(view, "cardTypes", List.of());
        return view;
    }

    private static BridgeCardFormatter cardFormatter() {
        ShortIdRegistry shortIds = new ShortIdRegistry("l");
        BridgeViewLocator viewLocator = new BridgeViewLocator(shortIds, ignored -> {
        });
        return new BridgeCardFormatter(viewLocator);
    }

    private static Session sessionProxy(CountDownLatch autoPassSent, AtomicInteger sendPlayerBooleanCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("sendPlayerBoolean".equals(method.getName())) {
                sendPlayerBooleanCalls.incrementAndGet();
                autoPassSent.countDown();
                return true;
            }
            return defaultReturnValue(method.getReturnType());
        };
        return (Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[]{Session.class},
            handler
        );
    }

    private static String invokeDecisionBoundaryStatus(
            BridgeCallbackHandler handler,
            PendingAction action,
            String source
    ) throws Exception {
        Object decisionFlowService = getField(handler, "decisionFlowService");
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        Method method = decisionFlowService.getClass().getDeclaredMethod(
            "transitionToDecisionBoundary",
            PendingAction.class,
            String.class
        );
        method.setAccessible(true);
        Object transition = processor.submit(BridgeCommand.of(() -> {
            try {
                return method.invoke(decisionFlowService, action, source);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException invocationTargetException
                    ? invocationTargetException.getCause()
                    : e;
                throw new IllegalStateException("Failed to invoke transitionToDecisionBoundary", cause);
            }
        }));
        Method statusMethod = transition.getClass().getDeclaredMethod("status");
        statusMethod.setAccessible(true);
        Object status = statusMethod.invoke(transition);
        return status.toString();
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void setCachedBridgeEvents(BridgeCallbackHandler handler, List<BridgeLogEntry> events)
            throws Exception {
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeGameLogState gameLogState = (BridgeGameLogState) getProcessorStateField(handler, "gameLogState");
        BridgePublishedQueryState publishedQueryState = (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");
        processor.submit(BridgeCommand.of(() -> {
            gameLogState.recordFetchedBridgeEvents(events);
            publishedQueryState.publishProcessorState();
            return null;
        }));
    }

    private static void publishProcessorState(BridgeCallbackHandler handler) throws Exception {
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgePublishedQueryState publishedQueryState = (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");
        processor.submit(BridgeCommand.of(() -> {
            publishedQueryState.publishProcessorState();
            return null;
        }));
    }

    private static void installPublishedSelectPrompt(BridgeCallbackHandler handler) throws Exception {
        BridgeProcessor processor = (BridgeProcessor) getDirectField(handler, "processor");
        BridgeDecisionState decisionState = (BridgeDecisionState) getProcessorStateField(handler, "decisionState");
        BridgeGameState gameState = (BridgeGameState) getProcessorStateField(handler, "gameState");
        BridgePublishedQueryState publishedQueryState =
            (BridgePublishedQueryState) getDirectField(handler, "publishedQueryState");

        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();

        PlayerView player = playerView(playerId, "TestPlayer", "p2");
        CardView spell = cardView(spellId, "p13", "Memnite");
        setField(spell, "manaCostLeftStr", List.of());
        setField(spell, "manaCostRightStr", List.of());

        CardsView hand = new CardsView();
        hand.put(spellId, spell);

        GameView view = gameView(12, List.of(player), new CardsView());
        setField(view, "myPlayerId", playerId);
        setField(view, "myHand", hand);
        setField(view, "canPlayObjects", playableObjects(Map.of(spellId, playStats("Cast Memnite"))));

        PendingAction pendingAction = new PendingAction(
            gameId,
            ClientCallbackMethod.GAME_SELECT,
            new GameClientMessage((GameView) null, Collections.<String, Serializable>emptyMap(), "Play spells and abilities"),
            "Play spells and abilities",
            12
        );

        processor.submit(BridgeCommand.of(() -> {
            gameState.activateGame(gameId, playerId);
            publishedQueryState.projectGameState(view, 1, "test_init");
            decisionState.replacePendingAction(pendingAction);
            return null;
        }));
    }

    private static Long publishedGameStateSnapshotId(BridgeCallbackHandler handler) throws Exception {
        Object publishedQueryState = getDirectField(handler, "publishedQueryState");
        Object snapshot = invokeNoArg(publishedQueryState, "snapshot");
        Object gameState = invokeNoArg(snapshot, "gameState");
        return (Long) invokeNoArg(gameState, "snapshotId");
    }

    private static void waitForCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static String joinTableDeckPath() {
        return Path.of("src", "test", "resources", "decks", "filler_opponent.dck")
            .toAbsolutePath()
            .normalize()
            .toString();
    }

    private static Object invokeStartPendingStartGameFlow(BridgeCallbackHandler handler, UUID expectedTableId) {
        try {
            Method method = BridgeCallbackHandler.class.getDeclaredMethod("startPendingStartGameFlow", UUID.class);
            method.setAccessible(true);
            return method.invoke(handler, expectedTableId);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException invocationTargetException
                ? invocationTargetException.getCause()
                : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to invoke startPendingStartGameFlow", cause);
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Object owner = resolveFieldOwner(target, name);
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Object owner = resolveFieldOwner(target, name);
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Object owner = resolveFieldOwner(target, name);
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        field.setInt(owner, value);
    }

    private static SubTypes subTypes(SubType... values) {
        SubTypes subTypes = new SubTypes();
        for (SubType value : values) {
            subTypes.add(value);
        }
        return subTypes;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object resolveFieldOwner(Object target, String name) throws Exception {
        try {
            findField(target.getClass(), name);
            return target;
        } catch (NoSuchFieldException ignored) {
            if (!(target instanceof BridgeCallbackHandler handler)) {
                throw ignored;
            }
            Object processorState = getDirectField(handler, "processorState");
            try {
                findField(processorState.getClass(), name);
                return processorState;
            } catch (NoSuchFieldException ignoredProcessorState) {
                // Keep searching through the extracted state holders below.
            }
            Object decisionState = getDirectField(processorState, "decisionState");
            try {
                findField(decisionState.getClass(), name);
                return decisionState;
            } catch (NoSuchFieldException ignoredDecisionState) {
                for (String ownerField : List.of("gameState", "interactionState", "gameLogState", "cursorState")) {
                    Object owner = getDirectField(processorState, ownerField);
                    try {
                        findField(owner.getClass(), name);
                        return owner;
                    } catch (NoSuchFieldException ignoredOwner) {
                        // Keep searching the remaining extracted state holders.
                    }
                }
                throw ignoredDecisionState;
            }
        }
    }

    private static Object getProcessorStateField(BridgeCallbackHandler handler, String name) throws Exception {
        Object processorState = getDirectField(handler, "processorState");
        return getDirectField(processorState, name);
    }

    private static Object getDirectField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
