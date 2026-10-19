import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { eventSchema, gameViewFromAndValidate, validateMessage } from './schema'
import { fullFlowScenario, type FullFlowOptions } from './scenarios/fullFlow'
import type { FakeConn } from './fake'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const RECORDED = path.join(__dirname, 'recorded', 'mutate.json')

class Recorder implements FakeConn {
  readonly id = 1
  emitted: unknown[] = []
  event(method: string, data: unknown, objectId?: string | null): void {
    this.emitted.push({ type: 'event', method, messageId: this.emitted.length + 1, objectId, data })
  }
  ok(): void {}
  fail(): void {}
  lobby(): void {}
  raw(obj: unknown): void {
    this.emitted.push(obj)
  }
  isOpen(): boolean {
    return true
  }
  close(): void {}
}

async function driveScenario(opts: FullFlowOptions = {}) {
  const rec = new Recorder()
  const scenario = fullFlowScenario(opts)
  scenario.onConnect?.(rec)
  const cleanup = scenario.onStart?.(rec)
  scenario.onAction?.(rec, 'watchGame', {}, 1)
  // let the timeline (updateMs) emit a few updates before reading
  await new Promise((r) => setTimeout(r, 25))
  cleanup?.()
  return { rec }
}

const llmSeat = {
  type: 'llmSeat', seq: 4, tableId: 't1', seat: 2, username: 'ChatGPT 2',
  provider: 'chatgpt_subscription', model: 'gpt-6-luna', effort: 'low', status: 'starting',
}

describe('contract schema (drift guard)', () => {
  it('accepts the proxy envelopes', () => {
    expect(validateMessage({ type: 'connected', message: 'hi' }).ok).toBe(true)
    expect(validateMessage({ type: 'result', action: 'connect', requestId: 1, ok: true }).ok).toBe(true)
    expect(validateMessage({ type: 'event', method: 'GAME_SELECT', messageId: 3, objectId: 'g1' }).ok).toBe(true)
    expect(validateMessage({ type: 'lobby', roomId: 'r', tables: [], users: {}, serverMessages: [] }).ok).toBe(true)
    expect(validateMessage(llmSeat).ok).toBe(true)
    expect(validateMessage({ ...llmSeat, status: 'failed', reason: 'gateway closed' }).ok).toBe(true)
  })

  it('rejects malformed envelopes (drift tampering)', () => {
    expect(validateMessage({ type: 'result', ok: 'yes' }).ok).toBe(false)
    expect(validateMessage({ type: 'event', method: 'GAME_SELECT' }).ok).toBe(false) // missing messageId
    expect(validateMessage({ type: 'lobby', tables: 'nope' }).ok).toBe(false)
    expect(validateMessage(null).ok).toBe(false)
    expect(validateMessage({ type: 'unknown', x: 1 }).ok).toBe(false)
    expect(validateMessage({ ...llmSeat, status: 'thinking' }).ok).toBe(false)
    expect(validateMessage({ ...llmSeat, seat: 0 }).ok).toBe(false)
  })

  it('FixtureServer events pass validation', async () => {
    const { rec } = await driveScenario({ updateMs: 5, maxUpdates: 5 })
    const events = rec.emitted.filter((e) => (e as { type?: string }).type === 'event')
    expect(events.length).toBeGreaterThan(0)
    for (const ev of events) {
      const parsed = eventSchema.safeParse(ev)
      expect(parsed.success, `invalid event: ${JSON.stringify(ev).slice(0, 140)}`).toBe(true)
    }
  })

  it('the scenario GameViews pass view validation', async () => {
    const { rec } = await driveScenario({ updateMs: 5, maxUpdates: 3 })
    const events = rec.emitted.filter((e) => (e as { type?: string }).type === 'event') as Array<{
      method: string
      data: unknown
    }>
    for (const ev of events) {
      if (ev.method === 'GAME_INIT' || ev.method === 'GAME_UPDATE' || ev.method === 'GAME_UPDATE_AND_INFORM') {
        const res = gameViewFromAndValidate(ev.data)
        expect(res.ok, `invalid ${ev.method}: ${JSON.stringify(res.errors)}`).toBe(true)
      }
    }
  })

  it('rejects a GameView without a turn (protocol drift)', () => {
    const res = gameViewFromAndValidate({ gameView: { phase: 'MAIN', step: 'PRECOMBAT_MAIN' } })
    expect(res.ok).toBe(false)
  })

  it('the captured real gameView (mutate) passes view validation', () => {
    // Drift guard: the frame was recorded by scripts/record-mutate.mjs against a
    // real XMage server. If the proxy stops forwarding mutateView (or changes its
    // shape), this test fails and flags that the contract moved.
    const raw = JSON.parse(fs.readFileSync(RECORDED, 'utf8')) as { gameView: unknown }
    const res = gameViewFromAndValidate(raw.gameView)
    expect(res.ok, `invalid mutate.json: ${JSON.stringify(res.errors)}`).toBe(true)
  })
})
