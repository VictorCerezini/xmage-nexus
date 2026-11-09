package mage.game.turn;

import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.events.GameEvent.EventType;
import mage.players.Player;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author BetaSteward_at_googlemail.com
 */
public abstract class Phase implements Serializable {

    protected TurnPhase type;
    protected List<Step> steps = new ArrayList<>();
    protected EventType event;
    protected EventType preEvent;
    protected EventType postEvent;

    protected UUID activePlayerId;
    protected Step currentStep;
    protected int count;
    // where currentStep is, so a copy can go on from it (see resumeAfterUndo): the index in steps of the
    // step being played, or of the step the extra steps being played come after
    protected int currentStepIndex = -1;
    protected boolean playingExtraStep = false;

    public abstract Phase copy();

    public Phase() {
    }

    protected Phase(final Phase phase) {
        this.type = phase.type;
        this.event = phase.event;
        this.preEvent = phase.preEvent;
        this.postEvent = phase.postEvent;
        this.activePlayerId = phase.activePlayerId;
        if (phase.currentStep != null) {
            this.currentStep = phase.currentStep.copy();
        }
        this.count = phase.count;
        this.currentStepIndex = phase.currentStepIndex;
        this.playingExtraStep = phase.playingExtraStep;
        for (Step step : phase.steps) {
            this.steps.add(step.copy());
        }
    }

    public TurnPhase getType() {
        return type;
    }

    public Step getStep() {
        return currentStep;
    }

    public void setStep(Step step) {
        this.currentStep = step;
    }

    public void resetCount() {
        count = 0;
    }

    public int getCount() {
        return count;
    }

    public boolean play(Game game, UUID activePlayerId) {
        if (game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }

        this.activePlayerId = activePlayerId;

        if (beginPhase(game, activePlayerId)) {
            Boolean result = playStepsFrom(game, 0);
            if (result != null) {
                return result;
            }
            return finishPhase(game);
        }
        return false;
    }

    /**
     * Play the steps from the given index to the end, as play() does
     *
     * @return null to go on and end the phase, or what play() must return right now
     */
    private Boolean playStepsFrom(Game game, int fromIndex) {
        for (int i = fromIndex; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (game.isPaused() || game.checkIfGameIsOver()) {
                return false;
            }
            if (game.getTurn().isEndTurnRequested() && step.getType() != PhaseStep.CLEANUP) {
                continue;
            }
            currentStep = step;
            currentStepIndex = i;
            playingExtraStep = false;
            TurnMod skipStepMod = game.getState().getTurnMods().useNextSkipStep(activePlayerId, getStep().getType());
            if (skipStepMod == null) {
                playStep(game);
                if (game.executingRollback()) {
                    return true;
                }
            } else {
                Player player = game.getPlayer(skipStepMod.getPlayerId());
                if (player != null) {
                    game.informPlayers(String.format("%s skips %s step%s",
                            player.getLogName(),
                            skipStepMod.getSkipStep().toString(),
                            skipStepMod.getInfo()
                    ));
                }
            }
            if (!game.isSimulation() && checkStopOnStepOption(game)) {
                return false;
            }
        }
        return null;
    }

    private boolean finishPhase(Game game) {
        if (game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }
        count++;
        endPhase(game, activePlayerId);
        return true;
    }

    /**
     * Go on with this phase from the priority its current step was in when the game was copied,
     * after a player went back to that priority (see Game.undoLastPriority). The phase must be the
     * turn's current phase, restored from that copy.
     *
     * @return same as play()
     */
    public boolean resumeAfterUndo(Game game) {
        if (game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()
                || currentStep == null || currentStep.getStepPart() != Step.StepPart.PRIORITY) {
            throw new IllegalStateException("Can't go back to a priority outside a step's priority: "
                    + type + ", step " + currentStepIndex + ", " + currentStep);
        }
        int index = currentStepIndex;
        boolean extraStep = playingExtraStep;
        if (!extraStep) {
            // the copy of the current step is a separate object from the copy in the list, use the list's one
            currentStep = steps.get(index);
        }

        currentStep.resumePriorityAfterUndo(game, activePlayerId);
        if (game.executingRollback()) {
            return true;
        }
        if (!game.isPaused() && !game.checkIfGameIsOver()) {
            postPriority(game, activePlayerId);
            if (game.executingRollback()) {
                return true;
            }
        }
        if (extraStep) {
            // that extra step came after this step, other extra steps can follow it
            playExtraSteps(game, steps.get(index).getType());
            if (game.executingRollback()) {
                return true;
            }
        }
        if (!game.isSimulation() && checkStopOnStepOption(game)) {
            return false;
        }

        Boolean result = playStepsFrom(game, index + 1);
        if (result != null) {
            return result;
        }
        return finishPhase(game);
    }

    private boolean checkStopOnStepOption(Game game) {
        if (game.getOptions().stopOnTurn != null
                && game.getOptions().stopOnTurn <= game.getState().getTurnNum()
                && game.getOptions().stopAtStep == getStep().getType()) {
            game.pause();
            return true;
        }
        return false;
    }

    public boolean resumePlay(Game game, PhaseStep stepType, boolean wasPaused) {
        if (game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }

        this.activePlayerId = game.getActivePlayerId();
        Iterator<Step> it = steps.iterator();
        Step step;
        do {
            step = it.next();
            currentStep = step;
        } while (step.getType() != stepType);
        currentStepIndex = steps.indexOf(step);
        playingExtraStep = false;
        resumeStep(game, wasPaused);
        while (it.hasNext()) {
            step = it.next();
            if (game.isPaused() || game.checkIfGameIsOver()) {
                return false;
            }
            currentStep = step;
            currentStepIndex = steps.indexOf(step);
            playingExtraStep = false;
            TurnMod skipStepMod = game.getState().getTurnMods().useNextSkipStep(activePlayerId, currentStep.getType());
            if (skipStepMod == null) {
                playStep(game);
                if (game.executingRollback()) {
                    return true;
                }
            } else {
                Player player = game.getPlayer(skipStepMod.getPlayerId());
                if (player != null) {
                    game.informPlayers(String.format("%s skips %s step%s",
                            player.getLogName(),
                            skipStepMod.getSkipStep().toString(),
                            skipStepMod.getInfo()
                    ));
                }
            }
        }

        if (game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }
        count++;
        endPhase(game, activePlayerId);
        return true;
    }

    public boolean beginPhase(Game game, UUID activePlayerId) {
        if (!game.replaceEvent(new GameEvent(event, null, null, activePlayerId))) {
            game.fireEvent(new GameEvent(preEvent, null, null, activePlayerId));
            return true;
        }
        return false;
    }

    public void endPhase(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(postEvent, null, null, activePlayerId));
        game.getState().getTriggers().removeAbilitiesOfNonExistingSources(game); // e.g. tokens that left the battlefield
    }

    public void prePriority(Game game, UUID activePlayerId) {
        currentStep.beginStep(game, activePlayerId);
    }

    public void postPriority(Game game, UUID activePlayerId) {
        currentStep.endStep(game, activePlayerId);
        //20091005 - 500.4/703.4n
        game.emptyManaPools(null);
        //20091005 - 500.9
        playExtraSteps(game, currentStep.getType());
    }

    protected void playStep(Game game) {
        if (!currentStep.skipStep(game, activePlayerId)) {
            game.getState().increaseStepNum();
            prePriority(game, activePlayerId);
            if (!game.isPaused() && !game.checkIfGameIsOver() && !game.executingRollback()) {
                currentStep.priority(game, activePlayerId, false);
                if (game.executingRollback()) {
                    return;
                }
            }
            if (!game.isPaused() && !game.checkIfGameIsOver() && !game.executingRollback()) {
                postPriority(game, activePlayerId);
            }
        }
    }

    protected void resumeStep(Game game, boolean wasPaused) {
        boolean resuming = true;
        if (currentStep == null || currentStep.getStepPart() == null) {
            game.end();
            return;
        }
        switch (currentStep.getStepPart()) {
            case PRE:
                if (wasPaused) {
                    currentStep.resumeBeginStep(game, activePlayerId);
                    resuming = false;
                } else {
                    prePriority(game, activePlayerId);
                }
            case PRIORITY:
                if (!game.isPaused() && !game.checkIfGameIsOver()) {
                    currentStep.priority(game, activePlayerId, resuming);
                }
            case POST:
                if (!game.isPaused() && !game.checkIfGameIsOver()) {
                    postPriority(game, activePlayerId);
                }
        }
    }

    private void playExtraSteps(Game game, PhaseStep afterStep) {
        try {
            while (true) {
                TurnMod extraStepMod = game.getState().getTurnMods().useNextExtraStep(activePlayerId, afterStep);
                if (extraStepMod == null) {
                    return;
                }
                currentStep = extraStepMod.getExtraStep();
                playingExtraStep = true;
                Player player = game.getPlayer(extraStepMod.getPlayerId());
                if (player != null && player.canRespond()) {
                    game.informPlayers(String.format("%s takes an extra %s step%s",
                            player.getLogName(),
                            extraStepMod.getExtraStep().toString(),
                            extraStepMod.getInfo()
                    ));
                    playStep(game);
                    if (game.executingRollback()) {
                        return;
                    }
                } else {
                    return;
                }
            }
        } finally {
            playingExtraStep = false;
        }
    }

    /**
     * One card [[Okea, Splitter of Seconds]], adds extra beginning phases with
     * all but upkeep steps skipped.
     * To achieve that, this function is called right after creating the extra Phase,
     * before running it.
     */
    public void keepOnlyStep(PhaseStep step) {
        if (count != 0) {
            throw new IllegalStateException("Wrong code usage: illegal Phase modification once it started running");
        }
        this.steps = this.steps.stream().filter(s -> s.getType().equals(step)).collect(Collectors.toList());
        if (this.steps.isEmpty()) {
            throw new IllegalStateException("Wrong code usage: keepOnlyStep should not remove all the steps in a phase - " + step);
        }
    }

    public String toString() {
        return type.toString();
    }
}
