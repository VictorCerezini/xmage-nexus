package mage.client.bridge.processor;

import mage.client.bridge.tools.ActionResult;
import mage.game.BridgeLogEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BridgeGameLogState {
    /**
     * -Dxmage.bridge.tableTalk=true lets the model read what the other players say in the game chat and
     * send its own messages. Off by default: table talk may try to give the model instructions.
     */
    public static final String TABLE_TALK_PROPERTY = "xmage.bridge.tableTalk";

    public static boolean tableTalkOn() {
        return Boolean.getBoolean(TABLE_TALK_PROPERTY);
    }

    /**
     * A model waiting for its next decision is woken by what another player says, this long after the first
     * message it has not read: a few lines typed in a row reach it together.
     */
    public static final long CHAT_WAKE_DELAY_MS = 1500;

    private record PendingOutgoingChat(String message, long sentAtMs) {
    }

    private final List<String> unseenChat = new ArrayList<>();
    // When the first player message the model has not read came in; 0 when there is none
    private long firstUnseenTalkAtMs = 0;
    private final List<BridgePublishedLogEntry> publishedLog = new ArrayList<>();
    private final Set<Integer> publishedBridgeEventIndexes = new HashSet<>();
    private final ArrayDeque<PendingOutgoingChat> pendingOutgoingChatEchoes = new ArrayDeque<>();
    private String lastChatMessage = null;
    private long lastChatTimeMs = 0;
    private int nextPublishedCursor = 0;
    private int lastPublishedBridgeEventIndex = -1;

    public void reset() {
        unseenChat.clear();
        firstUnseenTalkAtMs = 0;
        publishedLog.clear();
        publishedBridgeEventIndexes.clear();
        pendingOutgoingChatEchoes.clear();
        lastChatMessage = null;
        lastChatTimeMs = 0;
        nextPublishedCursor = 0;
        lastPublishedBridgeEventIndex = -1;
    }

    public void recordOutgoingChatMessage(
            String username,
            String message,
            long nowMs,
            long echoDedupWindowMs) {
        if (message == null || message.isEmpty()) {
            return;
        }
        prunePendingOutgoingChatEchoes(nowMs, echoDedupWindowMs);
        pendingOutgoingChatEchoes.addLast(new PendingOutgoingChat(message, nowMs));
        appendRenderedEntry("[Chat] " + username + ": " + message);
    }

    public void recordTalkMessage(String username, String user, String msg, long nowMs, long echoDedupWindowMs) {
        if (user == null || msg == null || msg.isEmpty()) {
            return;
        }
        prunePendingOutgoingChatEchoes(nowMs, echoDedupWindowMs);
        if (user.equals(username) && consumePendingOutgoingEcho(msg)) {
            return;
        }
        appendRenderedEntry("[Chat] " + user + ": " + msg);
        if (!user.equals(username)) {
            unseenChat.add(user + ": " + msg);
            if (firstUnseenTalkAtMs == 0) {
                firstUnseenTalkAtMs = Math.max(nowMs, 1);
            }
        }
    }

    /**
     * True when another player said something the model has not read, at least CHAT_WAKE_DELAY_MS ago: a model
     * waiting for its next decision is then woken to read it. The bridge's own notes never wake it.
     */
    public boolean talkToWakeFor(long nowMs) {
        return firstUnseenTalkAtMs != 0 && nowMs - firstUnseenTalkAtMs >= CHAT_WAKE_DELAY_MS;
    }

    public void addSystemMessage(String message) {
        appendRenderedEntry(message);
        unseenChat.add(message);
    }

    /** Adds a line to the game log only, for news the model already gets in a tool result. */
    public void addSystemLogEntry(String message) {
        appendRenderedEntry(message);
    }

    public void attachUnseenChat(Map<String, Object> result, boolean playerDead, boolean gameOver) {
        if (playerDead) {
            result.put("player_dead", true);
        }
        if (gameOver) {
            result.put("game_over", true);
        }
        List<String> recentChat = drainUnseenChat();
        if (recentChat != null) {
            result.put("recent_chat", recentChat);
        }
    }

    public void attachUnseenChat(ActionResult result, boolean playerDead, boolean gameOver) {
        if (playerDead) {
            result.player_dead = true;
        }
        if (gameOver) {
            result.game_over = true;
        }
        List<String> recentChat = drainUnseenChat();
        if (recentChat != null) {
            result.recent_chat = recentChat;
        }
    }

    public boolean shouldSuppressOutgoingChat(String message, long nowMs, long dedupWindowMs) {
        if (message.equals(lastChatMessage) && (nowMs - lastChatTimeMs) < dedupWindowMs) {
            return true;
        }
        lastChatMessage = message;
        lastChatTimeMs = nowMs;
        return false;
    }

    public BridgePublishedGameLog publishedGameLog() {
        int size = publishedLog.size();
        if (size != nextPublishedCursor) {
            throw new IllegalStateException(
                "Published log size (" + size + ") != nextPublishedCursor (" + nextPublishedCursor + ")"
            );
        }
        return new BridgePublishedGameLog(List.copyOf(publishedLog), nextPublishedCursor);
    }

    public void recordFetchedBridgeEvents(List<BridgeLogEntry> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (BridgeLogEntry event : events) {
            if (event == null) {
                continue;
            }
            if (publishedBridgeEventIndexes.contains(event.index())) {
                continue;
            }
            if (event.index() <= lastPublishedBridgeEventIndex) {
                throw new IllegalStateException(
                    "Bridge event fetch returned out-of-order unseen index " + event.index()
                        + " after " + lastPublishedBridgeEventIndex
                );
            }
            publishedBridgeEventIndexes.add(event.index());
            lastPublishedBridgeEventIndex = event.index();
            publishedLog.add(new BridgePublishedLogEntry(nextPublishedCursor++, event, null));
        }
    }

    private void appendRenderedEntry(String rendered) {
        publishedLog.add(new BridgePublishedLogEntry(nextPublishedCursor++, null, rendered));
    }

    private void prunePendingOutgoingChatEchoes(long nowMs, long echoDedupWindowMs) {
        while (!pendingOutgoingChatEchoes.isEmpty()) {
            PendingOutgoingChat oldest = pendingOutgoingChatEchoes.peekFirst();
            if ((nowMs - oldest.sentAtMs()) < echoDedupWindowMs) {
                return;
            }
            pendingOutgoingChatEchoes.removeFirst();
        }
    }

    private boolean consumePendingOutgoingEcho(String message) {
        for (var iterator = pendingOutgoingChatEchoes.iterator(); iterator.hasNext(); ) {
            PendingOutgoingChat pending = iterator.next();
            if (pending.message().equals(message)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private List<String> drainUnseenChat() {
        firstUnseenTalkAtMs = 0;
        if (unseenChat.isEmpty()) {
            return null;
        }
        List<String> recentChat = new ArrayList<>(unseenChat);
        unseenChat.clear();
        return recentChat;
    }
}
