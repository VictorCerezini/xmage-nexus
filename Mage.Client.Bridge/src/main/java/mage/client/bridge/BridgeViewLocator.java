package mage.client.bridge;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.util.ShortIdRegistry;
import mage.view.CardView;
import mage.view.CommandObjectView;
import mage.view.CommanderView;
import mage.view.ExileView;
import mage.view.GameView;
import mage.view.LookedAtView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.RevealedView;
import mage.view.SimpleCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class BridgeViewLocator {

    private static final int LOOKED_AT_CARDS_KEPT = 64;

    private final ShortIdRegistry shortIds;
    private final Consumer<String> errorLogger;
    private final Map<UUID, CardView> lookedAtCards = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, CardView> eldest) {
            return size() > LOOKED_AT_CARDS_KEPT;
        }
    };

    public BridgeViewLocator(
            ShortIdRegistry shortIds,
            Consumer<String> errorLogger
    ) {
        this.shortIds = shortIds;
        this.errorLogger = errorLogger;
    }

    public String getStableShortId(UUID objectId, CardView cardView, GameView gameView) {
        Objects.requireNonNull(objectId, "objectId");
        if (cardView != null) {
            String serverShortId = cardView.getShortId();
            if (serverShortId != null && !serverShortId.isBlank()) {
                UUID existing = shortIds.tryResolve(serverShortId);
                if (existing != null && !existing.equals(objectId)) {
                    errorLogger.accept("Server short ID collision: " + serverShortId
                        + " was mapped to " + existing + " but server now says " + objectId);
                }
                shortIds.register(objectId, serverShortId);
                return serverShortId;
            }
        }
        String found = findNonCardViewShortId(objectId, gameView);
        if (found != null) {
            shortIds.register(objectId, found);
            return found;
        }
        return shortIds.getOrAssign(objectId);
    }

    public int getStableShortIdSequence(UUID objectId, CardView cardView, GameView gameView) {
        return parseShortIdSequence(getStableShortId(objectId, cardView, gameView));
    }

    public CardView findCardViewById(UUID objectId, GameView gameView) {
        if (gameView == null) {
            return null;
        }

        CardView found = gameView.getMyHand().get(objectId);
        if (found != null) {
            return found;
        }

        found = gameView.getStack().get(objectId);
        if (found != null) {
            return found;
        }

        for (PlayerView player : gameView.getPlayers()) {
            PermanentView permanent = player.getBattlefield().get(objectId);
            if (permanent != null) {
                return permanent;
            }

            found = player.getGraveyard().get(objectId);
            if (found != null) {
                return found;
            }

            found = player.getExile().get(objectId);
            if (found != null) {
                return found;
            }

            for (CommandObjectView cmd : player.getCommandObjectList()) {
                if (cmd instanceof CommanderView cv && cmd.getId().equals(objectId)) {
                    return cv;
                }
            }
        }

        for (ExileView exileZone : gameView.getExile()) {
            for (CardView card : exileZone.values()) {
                if (card.getId().equals(objectId)) {
                    return card;
                }
            }
        }

        for (CardView card : gameView.getMyHand().values()) {
            CardView secondFace = card.getSecondCardFace();
            if (secondFace != null && secondFace.getId().equals(objectId)) {
                return secondFace;
            }
        }

        // a library's top card, while it is revealed (Future Sight, Courser of Kruphix)
        for (PlayerView player : gameView.getPlayers()) {
            CardView topCard = player.getTopCard();
            if (topCard != null && objectId.equals(topCard.getId())) {
                return topCard;
            }
        }

        found = findRevealedCard(gameView.getRevealed(), objectId);
        if (found != null) {
            return found;
        }
        found = findRevealedCard(gameView.getCompanion(), objectId);
        if (found != null) {
            return found;
        }

        return findLookedAtCard(objectId, gameView);
    }

    private static CardView findRevealedCard(List<RevealedView> revealedViews, UUID objectId) {
        if (revealedViews == null) {
            return null;
        }
        for (RevealedView revealed : revealedViews) {
            CardView card = revealed.getCards().get(objectId);
            if (card != null) {
                return card;
            }
        }
        return null;
    }

    /**
     * A card this seat looked at (the top of its library with Bolas's Citadel, say) comes as its printing only,
     * so it is read from the card database, as the desktop client does.
     */
    private CardView findLookedAtCard(UUID objectId, GameView gameView) {
        if (gameView.getLookedAt() == null) {
            return null;
        }
        for (LookedAtView lookedAt : gameView.getLookedAt()) {
            SimpleCardView simple = lookedAt.getCards().get(objectId);
            if (simple != null) {
                return lookedAtCardView(simple);
            }
        }
        return null;
    }

    private CardView lookedAtCardView(SimpleCardView simple) {
        synchronized (lookedAtCards) {
            CardView known = lookedAtCards.get(simple.getId());
            if (known != null) {
                return known;
            }
        }
        CardView view = null;
        try {
            CardInfo info = CardRepository.instance.findCard(simple.getExpansionSetCode(), simple.getCardNumber());
            Card card = info != null ? info.createMockCard() : null;
            view = card != null ? new CardView(card, simple) : null;
        } catch (RuntimeException e) {
            errorLogger.accept("Could not read looked-at card " + simple.getExpansionSetCode() + "/"
                + simple.getCardNumber() + ": " + e.getMessage());
        }
        if (view != null) {
            synchronized (lookedAtCards) {
                lookedAtCards.put(simple.getId(), view);
            }
        }
        return view;
    }

    /**
     * The cards a player can see outside the usual zones: revealed library tops, revealed and companion cards,
     * and cards this seat looked at.
     */
    public List<CardView> revealedCards(GameView gameView) {
        var cards = new ArrayList<CardView>();
        if (gameView == null) {
            return cards;
        }
        for (PlayerView player : gameView.getPlayers()) {
            if (player.getTopCard() != null) {
                cards.add(player.getTopCard());
            }
        }
        for (List<RevealedView> revealedViews : Arrays.asList(gameView.getRevealed(), gameView.getCompanion())) {
            if (revealedViews == null) {
                continue;
            }
            for (RevealedView revealed : revealedViews) {
                cards.addAll(revealed.getCards().values());
            }
        }
        if (gameView.getLookedAt() != null) {
            for (LookedAtView lookedAt : gameView.getLookedAt()) {
                for (SimpleCardView simple : lookedAt.getCards().values()) {
                    CardView card = lookedAtCardView(simple);
                    if (card != null) {
                        cards.add(card);
                    }
                }
            }
        }
        return cards;
    }

    public PermanentView findPermanentViewById(UUID objectId, GameView gameView) {
        if (gameView == null) {
            return null;
        }
        for (PlayerView player : gameView.getPlayers()) {
            PermanentView perm = player.getBattlefield().get(objectId);
            if (perm != null) {
                return perm;
            }
        }
        return null;
    }

    public static int parseShortIdSequence(String shortId) {
        if (shortId == null || shortId.length() < 2 || (shortId.charAt(0) != 'p' && shortId.charAt(0) != 'l')) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(shortId.substring(1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String findNonCardViewShortId(UUID objectId, GameView gameView) {
        if (gameView == null) {
            return null;
        }
        for (PlayerView pv : gameView.getPlayers()) {
            if (pv.getPlayerId().equals(objectId)) {
                String sid = pv.getShortId();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            }
        }
        for (LookedAtView lv : gameView.getLookedAt()) {
            for (SimpleCardView sv : lv.getCards().values()) {
                if (sv.getId().equals(objectId)) {
                    String sid = sv.getShortId();
                    if (sid != null && !sid.isBlank()) {
                        return sid;
                    }
                }
            }
        }
        return null;
    }
}
