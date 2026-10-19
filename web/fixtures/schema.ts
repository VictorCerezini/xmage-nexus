/**
 * Runtime schema of the proxy contract (a validatable mirror of src/net/types.ts).
 * It is the anti-drift net: any frame (real or from the FixtureServer) that
 * does not match the schema breaks the test instead of spreading silently.
 * Single source of truth for the TYPES: types.ts (typecheck). This file only
 * adds RUNTIME validation of the fields the client actually reads.
 */

import { z } from 'zod'
import { gameViewSchema } from './schema.generated'

export { gameViewSchema }

export const resultSchema = z.object({
  type: z.literal('result'),
  action: z.string(),
  requestId: z.union([z.string(), z.number()]).optional(),
  ok: z.boolean(),
  data: z.unknown().optional(),
  error: z.string().optional(),
  errorCode: z.string().optional(),
})

export const eventSchema = z.object({
  type: z.literal('event'),
  seq: z.number().optional(),
  method: z.string(),
  messageId: z.number(),
  objectId: z.union([z.string(), z.null()]).optional(),
  data: z.unknown().optional(),
})

export const lobbySchema = z.object({
  type: z.literal('lobby'),
  roomId: z.string().optional(),
  tables: z.array(z.record(z.string(), z.unknown())),
  users: z.record(z.string(), z.unknown()),
  serverMessages: z.array(z.string()),
})

export const connectedSchema = z.object({
  type: z.literal('connected'),
  message: z.string().optional(),
})

export const infoSchema = z.object({
  type: z.literal('info'),
  message: z.string(),
})

export const serverLinkSchema = z.object({
  type: z.literal('serverLink'),
  state: z.enum(['lost', 'retrying', 'restored', 'failed']),
  attempt: z.number().optional(),
  reason: z.literal('superseded').optional(),
  seq: z.number().optional(),
})

export const llmSeatSchema = z.object({
  type: z.literal('llmSeat'),
  seq: z.number().optional(),
  tableId: z.string(),
  seat: z.number().int().min(1),
  username: z.string(),
  provider: z.string(),
  model: z.string(),
  effort: z.string(),
  status: z.enum(['starting', 'joined', 'playing', 'waiting_for_next_game', 'finished', 'stopped', 'failed']),
  reason: z.string().optional(),
})

/** Game event: the payload must carry the gameView (directly or in data).
 *  (gameViewSchema is generated from the contract in ./schema.generated.ts.) */
export const gameEventSchema = z.object({
  method: z.string().startsWith('GAME_'),
  objectId: z.union([z.string(), z.null()]).optional(),
  data: z.unknown().optional(),
})

export type ParsedMessage =
  | z.infer<typeof resultSchema>
  | z.infer<typeof eventSchema>
  | z.infer<typeof lobbySchema>
  | z.infer<typeof connectedSchema>
  | z.infer<typeof infoSchema>
  | z.infer<typeof serverLinkSchema>
  | z.infer<typeof llmSeatSchema>

const baseSchemas: Record<string, z.ZodTypeAny> = {
  result: resultSchema,
  event: eventSchema,
  lobby: lobbySchema,
  connected: connectedSchema,
  info: infoSchema,
  serverLink: serverLinkSchema,
  llmSeat: llmSeatSchema,
}

/** Validates an incoming message; ok is true when it conforms. */
export function validateMessage(raw: unknown): { ok: boolean; errors?: string[] } {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return { ok: false, errors: ['not an object'] }
  }
  const type = (raw as { type?: unknown }).type
  if (typeof type !== 'string' || !(type in baseSchemas)) {
    return { ok: false, errors: [`unknown type: ${String(type)}`] }
  }
  const parsed = baseSchemas[type].safeParse(raw)
  if (!parsed.success) {
    return { ok: false, errors: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`) }
  }
  return { ok: true }
}

/** Validates a GameView taken from a game event. */
export function validateGameView(view: unknown): { ok: boolean; errors?: string[] } {
  const parsed = gameViewSchema.safeParse(view)
  if (!parsed.success) {
    return { ok: false, errors: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`) }
  }
  return { ok: true }
}

/** Takes the GameView out of an event payload (data.gameView or data itself),
 *  like store.gameViewFrom, and validates it. */
export function gameViewFromAndValidate(data: unknown): { ok: boolean; errors?: string[] } {
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return { ok: false, errors: ['data is not an object'] }
  }
  const record = data as Record<string, unknown>
  const embedded = record.gameView
  const view = embedded && typeof embedded === 'object' && !Array.isArray(embedded) ? embedded : 'myHand' in record && 'phase' in record ? record : null
  if (!view) return { ok: false, errors: ['no gameView in the payload'] }
  return validateGameView(view)
}
