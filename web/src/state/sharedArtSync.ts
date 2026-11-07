import * as cmds from '../net/commands'
import type { GameView } from '../net/types'
import {
  forgetAnnounced, ingestSharedArt, isSharedArtLine, linesToAnnounce, linesToResend, noteGameView,
} from '../cards/sharedArt'
import { getState } from './state'

/**
 * Glue between the shared-art bookkeeping (`cards/sharedArt.ts`) and the game chat: announces this
 * player's chosen art for cards that just became public, and answers other clients' requests.
 * A spectator posts one line only: the request, so that the players post again what they shared
 * before it started watching.
 */

const RESEND_DELAY_MS = 400
let resendTimer: ReturnType<typeof setTimeout> | null = null
/** The last game view seen, for `syncSharedArtOnChatReady`. */
let lastView: { gameId: string; game: GameView } | null = null

async function post(chatId: string, lines: string[]): Promise<boolean> {
  let ok = true
  for (const text of lines) {
    try {
      const res = await cmds.sendChatMessage(chatId, text)
      if (res && (res as { ok?: boolean }).ok === false) ok = false
    } catch {
      ok = false
    }
  }
  return ok
}

/** Call with every game view this client receives. */
export function syncSharedArt(gameId: string | null | undefined, game: GameView | null | undefined): void {
  if (!gameId || !game) return
  lastView = { gameId, game }
  const entries = noteGameView(gameId, game)
  const chatId = getState().gameChatId
  const isPlayer = (game.players ?? []).some((p) => p.controlled)
  if (!chatId) return
  const { lines, request } = linesToAnnounce(isPlayer ? entries : [])
  if (lines.length === 0) return
  void post(chatId, lines).then((ok) => {
    if (!ok) forgetAnnounced(entries, request)
  })
}

/**
 * Call once the game chat id is known: the first game view can arrive before it, and what that view
 * had to post then waits for it (not for the next view, which can take a while).
 */
export function syncSharedArtOnChatReady(): void {
  const s = getState()
  if (lastView && lastView.gameId === s.gameId) syncSharedArt(lastView.gameId, lastView.game)
}

/**
 * Handles a game chat line. Returns true when it was a hidden art line, which the caller must keep
 * out of the chat and the log.
 */
export function handleSharedArtChat(username: string | undefined, message: string): boolean {
  if (!isSharedArtLine(message)) return false
  const s = getState()
  if (!s.gameId || !username) return true
  const { resendRequested } = ingestSharedArt(s.gameId, s.game, username, message)
  const isPlayer = (s.game?.players ?? []).some((p) => p.controlled)
  if (resendRequested && isPlayer && !resendTimer) {
    resendTimer = setTimeout(() => {
      resendTimer = null
      const chatId = getState().gameChatId
      const lines = linesToResend()
      if (chatId && lines.length > 0) void post(chatId, lines)
    }, RESEND_DELAY_MS)
  }
  return true
}
