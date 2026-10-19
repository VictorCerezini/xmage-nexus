import { describe, expect, it } from 'vitest'
import type { LlmCatalog, LlmProvider } from '../../net/types'
import {
  activeLlmChoices,
  chooseLlmModel,
  defaultLlmChoice,
  llmProviderOf,
  llmProviders,
  llmSeatValue,
  planLlmSeats,
  readSavedLlmChoices,
  resolveLlmChoice,
} from './llmSeats'

const chatgpt: LlmProvider = {
  id: 'chatgpt_subscription',
  playerType: 'ChatGPT subscription',
  playerName: 'ChatGPT',
  auth: 'existing Codex ChatGPT login',
  models: [
    { id: 'gpt-a', name: 'GPT A', efforts: ['low', 'medium', 'high'], defaultEffort: 'medium', isDefault: false },
    { id: 'gpt-b', name: 'GPT B', efforts: ['low', 'xhigh'], defaultEffort: 'low', isDefault: true },
  ],
}
const empty: LlmProvider = { id: 'nothing', playerType: 'x', playerName: 'X', auth: '', models: [] }
const catalog: LlmCatalog = { enabled: true, providers: [chatgpt, empty], unavailable: [] }
const human = (deckName = '') => ({ type: 'HUMAN', deckName, skill: 2 })

describe('model seat helpers', () => {
  it('encodes the provider in the seat-type value', () => {
    expect(llmSeatValue('chatgpt_subscription')).toBe('llm:chatgpt_subscription')
    expect(llmProviderOf('llm:chatgpt_subscription')).toBe('chatgpt_subscription')
    expect(llmProviderOf('COMPUTER_MAD')).toBeNull()
  })

  it('lists only providers with models from an enabled catalog', () => {
    expect(llmProviders(catalog).map((p) => p.id)).toEqual(['chatgpt_subscription'])
    expect(llmProviders({ enabled: false })).toEqual([])
    expect(llmProviders(null)).toEqual([])
  })

  it('defaults to the default model and its default reasoning', () => {
    expect(defaultLlmChoice(chatgpt)).toEqual({ provider: 'chatgpt_subscription', model: 'gpt-b', effort: 'low' })
    expect(defaultLlmChoice(empty)).toBeNull()
  })

  it('keeps the reasoning across a model switch only when the new model has it', () => {
    const start = { provider: 'chatgpt_subscription', model: 'gpt-b', effort: 'low' }
    expect(chooseLlmModel(chatgpt, start, 'gpt-a')).toEqual({ provider: 'chatgpt_subscription', model: 'gpt-a', effort: 'low' })
    const xhigh = { ...start, effort: 'xhigh' }
    expect(chooseLlmModel(chatgpt, xhigh, 'gpt-a').effort).toBe('medium')
    expect(chooseLlmModel(chatgpt, start, 'missing')).toBe(start)
  })

  it('resolves a saved choice against the current catalog', () => {
    expect(resolveLlmChoice(catalog, { provider: 'chatgpt_subscription', model: 'gone', effort: 'max' }))
      .toEqual({ provider: 'chatgpt_subscription', model: 'gpt-b', effort: 'low' })
    expect(resolveLlmChoice(catalog, { provider: 'chatgpt_subscription', model: 'gpt-a', effort: 'ultra' })?.effort).toBe('medium')
    expect(resolveLlmChoice(catalog, { provider: 'claude_subscription', model: 'x', effort: 'low' })).toBeNull()
    expect(resolveLlmChoice({ enabled: false }, { provider: 'chatgpt_subscription', model: 'gpt-a', effort: 'low' })).toBeNull()
  })

  it('applies a choice only on an allowed HUMAN seat whose provider is in the catalog', () => {
    const choice = { provider: 'chatgpt_subscription', model: 'gpt-a', effort: 'high' }
    const choices = { 0: choice, 1: choice, 2: { ...choice, provider: 'gone' }, 5: choice }
    const seatConfigs = [human(), { type: 'COMPUTER_MAD', deckName: '', skill: 2 }, human()]
    expect(activeLlmChoices({ catalog, choices, seatConfigs, allowed: true })).toEqual({ 0: choice })
    expect(activeLlmChoices({ catalog, choices, seatConfigs, allowed: false })).toEqual({})
  })

  it('numbers seats after mine when I play, and drops seats past the player count', () => {
    const c = { provider: 'p', model: 'm', effort: 'e' }
    expect(planLlmSeats({ choices: { 2: c, 0: c }, humanSeat: true, numPlayers: 4 }).map((s) => [s.idx, s.seat]))
      .toEqual([[0, 2], [2, 4]])
    expect(planLlmSeats({ choices: { 0: c, 1: c }, humanSeat: false, numPlayers: 2 }).map((s) => s.seat)).toEqual([1, 2])
    expect(planLlmSeats({ choices: { 1: c }, humanSeat: true, numPlayers: 2 })).toEqual([])
  })

  it('reads back only well-formed saved choices', () => {
    expect(readSavedLlmChoices({
      0: { provider: 'p', model: 'm', effort: 'e' },
      1: { provider: 'p', model: 3, effort: 'e' },
      x: { provider: 'p', model: 'm', effort: 'e' },
      '-1': { provider: 'p', model: 'm', effort: 'e' },
    })).toEqual({ 0: { provider: 'p', model: 'm', effort: 'e' } })
    expect(readSavedLlmChoices(null)).toEqual({})
    expect(readSavedLlmChoices([1, 2])).toEqual({})
  })
})
