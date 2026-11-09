package mage.game;

import mage.cards.decks.DeckCardInfo;
import mage.constants.PhaseStep;
import mage.util.Copyable;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Game options for Mage game. Mainly used in tests to configure
 * {@link GameImpl} with specific params.
 *
 * @author ayratn
 */
public class GameOptions implements Serializable, Copyable<GameOptions> {

    private static final GameOptions deinstance = new GameOptions();

    public static GameOptions getDefault() {
        return deinstance;
    }

    /**
     * Defines the running mode. There are some exclusions made for test mode.
     */
    public boolean testMode = false;

    /**
     * Defines the turn number game should stop on. By default, is null meaning
     * that game shouldn't stop on any specific turn.
     */
    public Integer stopOnTurn = null;

    /**
     * Stop at the end of the turn if true otherwise stop at the beginning
     */
    public PhaseStep stopAtStep = PhaseStep.UNTAP;

    /**
     * If true, library won't be shuffled at the beginning of the game
     */
    public boolean skipInitShuffling = false;

    /**
     * If true (test mode), the starting player is chosen deterministically
     * (first player in table order) without the random toss and without the
     * "Select a starting player" GAME_TARGET ask. Makes test games fully
     * deterministic.
     */
    public boolean skipStartingPlayerChoice = false;

    /**
     * If true, players can rollback turn if all players agree
     */
    public boolean rollbackTurnsAllowed = true;

    public static final int UNDO_PRIORITY_STEPS_DEFAULT = 10;
    public static final int UNDO_PRIORITY_STEPS_MAX = 50;

    /**
     * How many priorities in a row a player can go back (Game.undoPriorities), if rollbacks are
     * allowed. Each one keeps a copy of the game for each human player.
     */
    public int undoPrioritySteps = UNDO_PRIORITY_STEPS_DEFAULT;

    public static int clampUndoPrioritySteps(int steps) {
        return Math.max(1, Math.min(UNDO_PRIORITY_STEPS_MAX, steps));
    }

    /**
     * Names of users banned from participating in the game
     */
    public Set<String> bannedUsers = new HashSet<>();

    /**
     * Cards to be given to each player as emblems
     */
    public Collection<DeckCardInfo> perPlayerEmblemCards = new HashSet<>();
    /**
     * Cards to be given to the starting player as emblems
     */
    public Collection<DeckCardInfo> globalEmblemCards = new HashSet<>();


    // PLANECHASE game mode
    public boolean planeChase = false;
    // xmage uses increased by 1/3 chances (2/2/9) for chaos/planar result, see 1a9f12f5767ce0beeed26a8ff5c8a8f9490c9c47
    // if you need combo support with 6-sides rolls then it can be reset to original values
    public static final int PLANECHASE_PLANAR_DIE_CHAOS_SIDES = 2; // original: 1
    public static final int PLANECHASE_PLANAR_DIE_PLANAR_SIDES = 2; // original: 1
    public static final int PLANECHASE_PLANAR_DIE_TOTAL_SIDES = 9; // original: 6

    public GameOptions() {
        super();
    }

    private GameOptions(final GameOptions options) {
        this.testMode = options.testMode;
        this.stopOnTurn = options.stopOnTurn;
        this.stopAtStep = options.stopAtStep;
        this.skipInitShuffling = options.skipInitShuffling;
        this.skipStartingPlayerChoice = options.skipStartingPlayerChoice;
        this.rollbackTurnsAllowed = options.rollbackTurnsAllowed;
        this.undoPrioritySteps = options.undoPrioritySteps;
        this.bannedUsers.addAll(options.bannedUsers);
        this.planeChase = options.planeChase;
        this.perPlayerEmblemCards = new HashSet<>(options.perPlayerEmblemCards);
        this.globalEmblemCards = new HashSet<>(options.globalEmblemCards);
    }

    @Override
    public GameOptions copy() {
        return new GameOptions(this);
    }
}
