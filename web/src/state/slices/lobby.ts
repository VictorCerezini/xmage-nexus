import type { ChatMessageEvent, LlmCatalog, LlmSeatInfo, LobbyEnvelope, TableView } from '../../net/types'
import type { DeepLink } from '../../lobby/deepLink'

export type LogChannel = 'game' | 'chat' | 'system'

export interface LogEntry {
  id: number
  time: number
  from: string
  text: string
  gameId?: string
  channel?: LogChannel
  fromRoom?: boolean
}

export interface LobbySlice {
  lobby: LobbyEnvelope | null
  roomChatId: string | null
  /** chat of the staging table (U4-11); null outside the waiting room */
  tableChatId: string | null
  /** table tableChatId belongs to (avoids re-joins and mix-ups when the table changes) */
  tableChatTableId: string | null
  /** tournament chat in the game panel (T4, like the TournamentPanel's chatPanel) */
  tournamentChatId: string | null
  /** tournament tournamentChatId belongs to (avoids re-joins and mix-ups) */
  tournamentChatTournamentId: string | null
  chatMessages: ChatMessageEvent[]
  watchingTable: TableView | null
  /** tableId→tournamentId of a tournament watch (SHOW_TOURNAMENT callback):
   *  the lobby uses it to open the bracket of someone else's or a live tournament
   *  (the server does not take the table id in getTournament). */
  spectateTournament: { tournamentId: string; tableId: string } | null
  stagingTableId: string | null
  /** isTournament flag of JOINED_TABLE (the lobby may lag behind on entry) */
  stagingIsTournament: boolean
  /** pending invitation from a deep link (#join= / #watch=); the lobby consumes it */
  pendingDeepLink: DeepLink | null
  /** game being rejoined (joinGame) whose replay has not arrived yet */
  resumingGameId: string | null
  /** the proxy's model catalog (`getLlmProviders`); null until asked */
  llmCatalog: LlmCatalog | null
  llmCatalogLoading: boolean
  /** why the last catalog refresh failed */
  llmCatalogError: string | null
  /** latest status of each model seat this session started, one entry per table and seat */
  llmSeats: LlmSeatInfo[]
  log: LogEntry[]
  events: { method: string; time: number }[]
}

export const initialLobby: LobbySlice = {
  lobby: null,
  roomChatId: null,
  tableChatId: null,
  tableChatTableId: null,
  tournamentChatId: null,
  tournamentChatTournamentId: null,
  chatMessages: [],
  watchingTable: null,
  spectateTournament: null,
  stagingTableId: null,
  stagingIsTournament: false,
  pendingDeepLink: null,
  resumingGameId: null,
  llmCatalog: null,
  llmCatalogLoading: false,
  llmCatalogError: null,
  llmSeats: [],
  log: [],
  events: [],
}
