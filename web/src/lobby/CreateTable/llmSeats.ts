import type { LlmCatalog, LlmModel, LlmProvider } from '../../net/types'
import { isHumanSeatType, type SeatConfig } from './constants'

/** Seat-type select value of a model seat: `llm:<provider id>`. The server seat stays HUMAN. */
export const LLM_SEAT_PREFIX = 'llm:'

export interface LlmSeatChoice {
  provider: string
  model: string
  effort: string
  /** Provider of the backup model, any signed-in one; absent with no backup (or `provider`, from 0.6.7). */
  backupProvider?: string
  /** Takes over when `model` reaches its plan limit or keeps failing through every retry; absent for none. */
  backupModel?: string
  /** The backup's reasoning level: `effort` when the backup has it. Set by `resolveLlmChoice`. */
  backupEffort?: string
}

/** A model seat ready for `createTable`: `seat` is 1-based into `playerTypes`, `idx` indexes `seatConfigs`. */
export interface PlannedLlmSeat extends LlmSeatChoice {
  seat: number
  idx: number
}

export function llmSeatValue(providerId: string): string {
  return LLM_SEAT_PREFIX + providerId
}

export function llmProviderOf(value: string): string | null {
  return value.startsWith(LLM_SEAT_PREFIX) ? value.slice(LLM_SEAT_PREFIX.length) : null
}

export function llmProviders(catalog: LlmCatalog | null | undefined): LlmProvider[] {
  return catalog?.enabled ? (catalog.providers ?? []).filter((p) => p.models.length > 0) : []
}

function effortFor(model: LlmModel, wanted?: string): string {
  if (wanted && model.efforts.includes(wanted)) return wanted
  if (model.efforts.includes(model.defaultEffort)) return model.defaultEffort
  return model.efforts[0] ?? ''
}

export function defaultLlmChoice(provider: LlmProvider): LlmSeatChoice | null {
  const model = provider.models.find((m) => m.isDefault) ?? provider.models[0]
  if (!model) return null
  return { provider: provider.id, model: model.id, effort: effortFor(model) }
}

/** Switches the model and keeps the reasoning level when the new model has it. */
export function chooseLlmModel(provider: LlmProvider, choice: LlmSeatChoice, modelId: string): LlmSeatChoice {
  const model = provider.models.find((m) => m.id === modelId)
  if (!model) return choice
  const { provider: _provider, model: _model, effort: _effort, ...backup } = choice
  const next: LlmSeatChoice = { ...backup, provider: provider.id, model: model.id, effort: effortFor(model, choice.effort) }
  return isOwnModel(next, next.backupProvider ?? next.provider, next.backupModel) ? withoutBackup(next) : next
}

/** Backup dropdown value of one provider's model; '' means no backup. */
export function llmBackupValue(providerId: string, modelId: string): string {
  return `${providerId}|${modelId}`
}

/** Sets the backup from its dropdown value ('' for none). Any signed-in provider's model may back up the seat,
 *  even one on the same plan as the chosen model; only the chosen model itself is refused. */
export function chooseLlmBackup(providers: LlmProvider[], choice: LlmSeatChoice, value: string): LlmSeatChoice {
  const split = value.indexOf('|')
  if (split < 0) return withoutBackup(choice)
  return withBackup(providers, choice, value.slice(0, split), value.slice(split + 1))
}

function isOwnModel(choice: LlmSeatChoice, providerId: string, modelId: string | undefined): boolean {
  return providerId === choice.provider && modelId === choice.model
}

function withoutBackup(choice: LlmSeatChoice): LlmSeatChoice {
  const { backupProvider: _provider, backupModel: _model, backupEffort: _effort, ...rest } = choice
  return rest
}

function withBackup(providers: LlmProvider[], choice: LlmSeatChoice, providerId: string, modelId: string | undefined): LlmSeatChoice {
  const rest = withoutBackup(choice)
  if (!modelId || isOwnModel(choice, providerId, modelId)) return rest
  const backup = providers.find((p) => p.id === providerId)?.models.find((m) => m.id === modelId)
  return backup ? { ...rest, backupProvider: providerId, backupModel: backup.id, backupEffort: effortFor(backup, choice.effort) } : rest
}

/** The choice as the catalog can serve it now, or null when its provider is gone. A backup whose provider
 *  or model is gone is dropped. */
export function resolveLlmChoice(catalog: LlmCatalog | null | undefined, choice: LlmSeatChoice): LlmSeatChoice | null {
  const providers = llmProviders(catalog)
  const provider = providers.find((p) => p.id === choice.provider)
  if (!provider) return null
  if (!provider.models.some((m) => m.id === choice.model)) return defaultLlmChoice(provider)
  const resolved = chooseLlmModel(provider, choice, choice.model)
  return withBackup(providers, resolved, choice.backupProvider ?? choice.provider, choice.backupModel)
}

/** The model seats that apply: allowed for this table, on a HUMAN seat, with a provider the catalog has. */
export function activeLlmChoices(args: {
  catalog: LlmCatalog | null | undefined
  choices: Record<number, LlmSeatChoice>
  seatConfigs: SeatConfig[]
  allowed: boolean
}): Record<number, LlmSeatChoice> {
  const out: Record<number, LlmSeatChoice> = {}
  if (!args.allowed) return out
  for (const [key, choice] of Object.entries(args.choices)) {
    const idx = Number(key)
    const cfg = args.seatConfigs[idx]
    if (!cfg || !isHumanSeatType(cfg.type)) continue
    const resolved = resolveLlmChoice(args.catalog, choice)
    if (resolved) out[idx] = resolved
  }
  return out
}

/** Seat numbers as `computeMatchSeats` lays the table out: my seat first when I play. */
export function planLlmSeats(args: {
  choices: Record<number, LlmSeatChoice>
  humanSeat: boolean
  numPlayers: number
}): PlannedLlmSeat[] {
  const offset = args.humanSeat ? 2 : 1
  return Object.entries(args.choices)
    .map(([key, choice]) => ({ ...choice, idx: Number(key), seat: Number(key) + offset }))
    .filter((s) => s.seat <= args.numPlayers)
    .sort((a, b) => a.seat - b.seat)
}

/** Model seat choices read back from the persisted form. */
export function readSavedLlmChoices(raw: unknown): Record<number, LlmSeatChoice> {
  const out: Record<number, LlmSeatChoice> = {}
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const idx = Number(key)
    if (!Number.isInteger(idx) || idx < 0 || !value || typeof value !== 'object') continue
    const { provider, model, effort, backupProvider, backupModel } = value as Record<string, unknown>
    if (typeof provider === 'string' && typeof model === 'string' && typeof effort === 'string') {
      out[idx] = { provider, model, effort }
      if (typeof backupModel === 'string' && backupModel) {
        out[idx] = { ...out[idx], backupModel, ...(typeof backupProvider === 'string' && backupProvider ? { backupProvider } : {}) }
      }
    }
  }
  return out
}
