/**
 * TS types of the proxy protocol (Mage.Proxy).
 *
 * View types (mage.view.*) are generated from schema/contract.schema.json.
 * Run: node scripts/gen-types.mjs (or npm run gen-types).
 *
 * Proxy-specific types (envelopes, deck, events) stay here.
 * The Java↔TS contract is JsonUtil: field names are camelCase (Java reflection),
 * UUID/enums are strings, maps have string keys, dates are epoch millis.
 */

export type UUID = string

// ─── Generated view types (from JsonUtil wire format) ────────────────────────
export type {
  GameView, PlayerView, CardView, PermanentView,
  MutateView,
  CardsView, SimpleCardsView, SimpleCardView,
  CounterView, ManaPoolView,
  CombatGroupView, ExileView, RevealedView,
  TableView, SeatView,
  RoomUsersView, UsersView, UserView,
  ChatMessage, GameEndInfo,
  PlayableObjectsList, PlayableObjectStats, PlayableObjectRecord,
  DraftView, DraftPickView, DraftClientMessage, DeckView, TableClientMessage,
  TournamentView, TournamentPlayerView, TournamentGameView, RoundView,
} from './types.generated'

// ─── Proxy envelope types (proxy → client) ──────────────────────────────────

export type ProxyMessage =
  | { type: 'connected'; message?: string; seq?: number }
  | { type: 'disconnected'; reason?: string; seq?: number }
  | { type: 'info'; message: string; seq?: number }
  | { type: 'error'; message: string; seq?: number }
  | ServerLinkEnvelope
  | LlmSeatEnvelope
  | LobbyEnvelope
  | ResultEnvelope
  | EventEnvelope

/** The proxy lost its link to the XMage server and logs the session in again
 *  (`lost` → `retrying` per attempt → `restored` | `failed`). */
export interface ServerLinkEnvelope {
  type: 'serverLink'
  state: 'lost' | 'retrying' | 'restored' | 'failed'
  attempt?: number
  /** `superseded`: another login of the same account took the session over. */
  reason?: 'superseded'
  seq?: number
}

/** Lobby broadcast, every ~2 s. */
export interface LobbyEnvelope {
  type: 'lobby'
  roomId?: UUID
  tables: import('./types.generated').TableView[]
  users:
    | import('./types.generated').RoomUsersView
    | import('./types.generated').RoomUsersView[]
    | import('./types.generated').UsersView[]
  serverMessages: string[]
}

/** Answer to an action (one promise per action+requestId in the client). */
export interface ResultEnvelope {
  type: 'result'
  action: string
  requestId?: string | number
  ok: boolean
  data?: unknown
  error?: string
  errorCode?: string
}

/** XMage server callback passed through (method = name of the MageClient method). */
export interface EventEnvelope {
  type: 'event'
  /** Position in the proxy session's stream (absent on replays sent to one connection). */
  seq?: number
  method: string
  messageId: number
  objectId?: UUID | null
  data?: unknown
}

// ─── Model seats (proxy `--llm`) ─────────────────────────────────────────────

export type LlmSeatStatus =
  | 'starting'
  | 'joined'
  | 'playing'
  | 'waiting_for_next_game'
  | 'finished'
  | 'stopped'
  | 'failed'

/** One model seat of this session, as `getLlmSeats` lists it. `seat` is 1-based. */
export interface LlmSeatInfo {
  tableId: UUID
  seat: number
  username: string
  provider: string
  model: string
  effort: string
  status: LlmSeatStatus
  reason?: string
}

/** A model seat changed status. */
export interface LlmSeatEnvelope extends LlmSeatInfo {
  type: 'llmSeat'
  seq?: number
}

export interface LlmModel {
  id: string
  name: string
  efforts: string[]
  defaultEffort: string
  isDefault: boolean
  contextLength?: number
  maxOutput?: number
}

export interface LlmProvider {
  id: string
  playerType: string
  /** Base of the seat's login name (`ChatGPT` → `ChatGPT 2`). */
  playerName: string
  auth: string
  freeRequestsToday?: { used?: number; limit?: number; remaining?: number }
  models: LlmModel[]
}

/** `getLlmProviders`: `enabled` is false when the proxy runs without `--llm`. */
export interface LlmCatalog {
  enabled: boolean
  providers?: LlmProvider[]
  unavailable?: { id: string; reason?: string }[]
}

// ─── Chat ────────────────────────────────────────────────────────────────────

export interface ChatMessageEvent {
  chatId: UUID
  username: string
  message: string
  messageType?: string
  soundToPlay?: string
  /** Server epoch ms (may be missing on local or old messages: use the receive time) */
  time?: number
}

// ─── Deck JSON (proxy) ───────────────────────────────────────────────────────

export interface DeckJson {
  name: string
  cards: DeckCardEntry[]
  sideboard: DeckCardEntry[]
  /** Designated commanders (1-2): the proxy moves them to the sideboard XMage expects. */
  commanders?: DeckCardEntry[]
}

export interface DeckCardEntry {
  cardName: string
  setCode: string
  cardNumber: string
  amount: number
}

// ─── Deck validation (the proxy's validateDeck) ─────────────────────────────

/** commanderEligibility report (the proxy's real XMage classes). ready=false => DB not available. */
export interface CommanderEligibilityResult {
  ready: boolean
  results: { name: string; eligible: boolean }[]
}

/** Raw error of XMage's official DeckValidator (official text, in English). */
export interface XmageFormatError {
  type: 'PRIMARY' | 'DECK_SIZE' | 'BANNED' | 'WRONG_SET' | 'OTHER' | string
  group?: string
  message?: string
  cardName?: string
}

/**
 * validateDeckFormat report (the proxy's official XMage DeckValidator).
 * ready=false => card DB not available; supported=false => no validator
 * for that format (the client keeps its local validation).
 */
export interface DeckFormatValidationResult {
  ready: boolean
  supported: boolean
  valid: boolean
  validator: string
  errors: XmageFormatError[]
}

/** The printing XMage picks for a name (the proxy's resolvePrintings). */
export interface XmagePrintingResolution {
  name: string
  found: boolean
  /** Canonical name in XMage (e.g. "Fire // Ice") */
  cardName?: string
  setCode?: string
  cardNumber?: string
}

/** resolvePrintings report. ready=false => the proxy's card DB is not available. */
export interface ResolvePrintingsResult {
  ready: boolean
  results: XmagePrintingResolution[]
}

/** cardPrintings report: printings implemented in XMage (empty => not implemented). */
export interface CardPrintingsResult {
  ready: boolean
  results: { name: string; printings: { setCode: string; cardNumber: string }[] }[]
}

/** Why the server would reject a card ("Card not found"). */
export type DeckIssueReason = 'UNIMPLEMENTED' | 'OUTDATED_PRINTING'

/** Alternative printing implemented on the server (one-click repair). */
export interface DeckPrintingSuggestion {
  cardName: string
  setCode: string
  cardNumber: string
}

/** Raw printing the client sent that the proxy normalized before validating (e.g. PWOE #242s → WOE #242s). */
export interface DeckPrintingSource {
  setCode: string
  cardNumber: string
}

/** Card the server will reject on join ("Card not found"). */
export interface DeckMissingCard extends DeckCardEntry {
  reason: DeckIssueReason
  suggestions?: DeckPrintingSuggestion[]
  sources?: DeckPrintingSource[]
}

/** Card accepted but loaded by the server as ANOTHER card (set/number of another one). */
export interface DeckMismatchCard extends DeckCardEntry {
  resolvedName: string
  suggestions?: DeckPrintingSuggestion[]
  sources?: DeckPrintingSource[]
}

/** validateDeck report. ready=false => the proxy's card DB is not available. */
export interface DeckValidationResult {
  ready: boolean
  missing: DeckMissingCard[]
  mismatches: DeckMismatchCard[]
  /** the deck without the rejected cards (only when missing > 0) */
  fixedDeck?: DeckJson
}

// ─── Game / match end (the server's GameEndView) ─────────────────────────────

/** The server's SIDEBOARD event (best-of-N match between games). */
export interface SideboardEvent {
  deck?: {
    name?: string
    cards?: Record<string, unknown>
    sideboard?: Record<string, unknown>
  }
  currentTableId?: string
  parentTableId?: string
  roomId?: string
  time?: number
  flag?: boolean
}

// ─── Finished matches (the server's MatchView) ───────────────────────────────

export interface MatchView {
  tableId: UUID
  matchId?: UUID
  matchName?: string
  gameType?: string
  deckType?: string
  games?: UUID[]
  result?: string
  players?: string
  startTime?: number | string
  endTime?: number | string
  replayAvailable?: boolean
  isTournament?: boolean
  rated?: boolean
}

// ─── Most common server event methods (MageClient) ──────────────────────────

export const EVENT_METHODS = {
  gameInit: 'GAME_INIT',
  gameUpdate: 'GAME_UPDATE',
  gameUpdateAndInform: 'GAME_UPDATE_AND_INFORM',
  gameAsk: 'GAME_ASK',
  gameSelect: 'GAME_SELECT',
  gameTarget: 'GAME_TARGET',
  gameTargetPlayer: 'GAME_TARGET_PLAYER',
  gameTargetAmount: 'GAME_TARGET_AMOUNT',
  gamePlayMana: 'GAME_PLAY_MANA',
  gamePlayXMana: 'GAME_PLAY_XMANA',
  gameChooseAbility: 'GAME_CHOOSE_ABILITY',
  gameChooseMode: 'GAME_CHOOSE_MODE',
  gameChoosePile: 'GAME_CHOOSE_PILE',
  gameChooseCards: 'GAME_CHOOSE_CARDS',
  gameChooseColor: 'GAME_CHOOSE_COLOR',
  gameSelectCards: 'GAME_SELECT_CARDS',
  gameSelectTargets: 'GAME_SELECT_TARGETS',
  gameGetAmount: 'GAME_GET_AMOUNT',
  gameGetMultiAmount: 'GAME_GET_MULTI_AMOUNT',
  gameSelectPlayer: 'GAME_SELECT_PLAYER',
  gameChooseOne: 'GAME_CHOOSE_ONE',
  gameChooseNumber: 'GAME_CHOOSE_NUMBER',
  gameChooseString: 'GAME_CHOOSE_STRING',
  gameChooseBetween: 'GAME_CHOOSE_BETWEEN',
  gameChooseCardsOrder: 'GAME_CHOOSE_CARDS_ORDER',
  gameEnd: 'GAME_END',
  gameOver: 'GAME_OVER',
  endGameInfo: 'END_GAME_INFO',
  sideboard: 'SIDEBOARD',
  chat: 'CHATMESSAGE',
  serverMessage: 'SERVER_MESSAGE',
  joinedTable: 'JOINED_TABLE',
  startGame: 'START_GAME',
  replayGame: 'REPLAY_GAME',
  replayInit: 'REPLAY_INIT',
  replayUpdate: 'REPLAY_UPDATE',
  replayDone: 'REPLAY_DONE',
} as const
