import { fakeOnly } from './support/fake-mode'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect } from './fixtures'
import type { Page } from '@playwright/test'
import { quickStartGame } from './support/start-game'
import { withFakeServer } from './support/fake-backend'
import { spellsScenario } from '../fixtures/scenarios/spells'
fakeOnly()

const SHOTS_DIR = process.env.FAST_CAST_SHOTS ?? path.join(path.dirname(fileURLToPath(import.meta.url)), 'shots')
/** The server keys a spell on the stack by its spell ability's id; the permanent it becomes keeps the card's id. */
const SPELL_ID = '3f0c2a71-5b8e-4d0a-9c3e-7a1b2c3d4e5f'
const CARD_ID = '8621623b-7964-49d6-a7c1-1c23b2e2080b'

interface FrameLook {
  t: number
  flights: Array<{ id: string; cls: string; x: number; y: number; w: number; h: number; opacity: number }>
  slots: Array<{ zone: string; visibility: string; opacity: number }>
}

/**
 * Replays an opponent's spell the way a model seat casts it: the spell shows up on the stack while it
 * is paid for, two mana updates follow within milliseconds, and `resolveAt` ms after it first showed
 * the spell has already resolved onto the battlefield (the human's side passed at once).
 * Every animation frame records the flying ghosts and the card's own slots.
 */
async function replayCast(page: Page, kind: 'creature' | 'instant', resolveAt: number, recordMs: number): Promise<FrameLook[]> {
  return page.evaluate(async ({ spellId, cardId, kind, resolveAt, recordMs }) => {
    const w = window as unknown as {
      __mageStore: { getState: () => { game: any; gameId: string }; handleMessage: (m: unknown) => void }
    }
    const st = w.__mageStore
    const gameId = st.getState().gameId
    const base = structuredClone(st.getState().game)
    const opp = base.players.find((p: any) => !p.controlled)
    const template: any = Object.values(base.myHand)[0]
    const instant = kind === 'instant'
    const card = {
      ...template,
      id: spellId,
      name: instant ? 'Brainstorm' : 'Goldspan Dragon',
      displayName: instant ? 'Brainstorm' : 'Goldspan Dragon',
      expansionSetCode: instant ? 'ICE' : 'KHM',
      cardNumber: instant ? '61' : '139',
      manaCost: instant ? ['{U}'] : ['{3}', '{R}', '{R}'],
      manaValue: instant ? 1 : 5,
      power: instant ? '0' : '4',
      toughness: instant ? '0' : '4',
      cardTypes: [instant ? 'INSTANT' : 'CREATURE'],
      subTypes: instant ? [] : ['Dragon'],
      superTypes: [],
      rarity: instant ? 'COMMON' : 'MYTHIC',
      rules: [instant ? 'Draw three cards, then put two cards from your hand on top of your library in any order.' : 'Flying, haste'],
      controllerId: opp.playerId,
      controllerName: opp.name,
      ownerId: opp.playerId,
      ownerName: opp.name,
      targets: [],
    }
    const send = (method: string, mutate: (v: any) => void) => {
      const next = structuredClone(base)
      mutate(next)
      st.handleMessage({ type: 'event', method, objectId: gameId, data: { gameView: next, message: `Waiting for ${opp.name}` } })
    }
    const onStack = (v: any) => {
      const o = v.players.find((p: any) => p.playerId === opp.playerId)
      o.handCount = Math.max(0, (o.handCount ?? 1) - 1)
      v.stack = { [spellId]: { ...card, mageObjectType: 'SPELL' } }
    }
    const resolved = (v: any) => {
      const o = v.players.find((p: any) => p.playerId === opp.playerId)
      o.handCount = Math.max(0, (o.handCount ?? 1) - 1)
      v.stack = {}
      if (instant) o.graveyard = { ...(o.graveyard ?? {}), [cardId]: { ...card, id: cardId } }
      else o.battlefield = { ...(o.battlefield ?? {}), [cardId]: { ...card, id: cardId, tapped: false, summoningSickness: false } }
    }

    const frames: Array<{ t: number; flights: unknown[]; slots: unknown[] }> = []
    const t0 = performance.now()
    let stop = false
    const sample = () => {
      const t = Math.round(performance.now() - t0)
      const flights = [...document.querySelectorAll<HTMLElement>('.flying-card-item')].map((el) => {
        const r = el.getBoundingClientRect()
        return { id: el.dataset.flightId ?? '', cls: el.className, x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), opacity: Math.round(Number(getComputedStyle(el).opacity) * 100) / 100 }
      })
      const slots = [...document.querySelectorAll<HTMLElement>(`[data-card-id="${spellId}"], [data-card-id="${cardId}"]`)].map((el) => {
        let opacity = 1
        let visibility = 'visible'
        for (let node: HTMLElement | null = el; node; node = node.parentElement) {
          const cs = getComputedStyle(node)
          opacity *= Number(cs.opacity)
          if (cs.visibility === 'hidden') visibility = 'hidden'
          if (cs.display === 'none') visibility = 'none'
        }
        const zone = el.closest('.stack-zone, .stack-deck, .stack-list') ? 'stack' : el.closest('.creatures-band, .permanents-band') ? 'battlefield' : el.closest('.graveyard-stack, .graveyard-chip') ? 'graveyard' : el.closest('.opponent-zone, .player-zone') ? 'zone' : 'other'
        return { zone: `${el.dataset.cardId === spellId ? 'spell' : 'card'}@${zone}`, visibility, opacity: Math.round(opacity * 100) / 100 }
      })
      frames.push({ t, flights, slots })
      if (!stop) requestAnimationFrame(sample)
    }
    requestAnimationFrame(sample)

    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))
    ;(window as unknown as { __fcT0?: number }).__fcT0 = Date.now()
    send('GAME_UPDATE_AND_INFORM', onStack)
    await wait(12)
    send('GAME_UPDATE_AND_INFORM', onStack)
    await wait(13)
    send('GAME_UPDATE_AND_INFORM', onStack)
    await wait(Math.max(0, resolveAt - 25))
    send('GAME_UPDATE', resolved)
    await wait(12)
    send('GAME_UPDATE', resolved)
    await wait(Math.max(0, recordMs - resolveAt - 12))
    stop = true
    return frames as FrameLook[]
  }, { spellId: SPELL_ID, cardId: CARD_ID, kind, resolveAt, recordMs }) as Promise<FrameLook[]>
}

function summarize(frames: FrameLook[]): string[] {
  const out: string[] = []
  const byFlight = new Map<string, FrameLook['flights'][number][] & { first?: number }>()
  const seen = new Map<string, { first: number; last: number; maxH: number; cls: Set<string> }>()
  for (const f of frames) {
    for (const g of f.flights) {
      const s = seen.get(g.id) ?? { first: f.t, last: f.t, maxH: 0, cls: new Set<string>() }
      s.last = f.t
      s.maxH = Math.max(s.maxH, g.h)
      s.cls.add(g.cls)
      seen.set(g.id, s)
      if (!byFlight.has(g.id)) byFlight.set(g.id, [])
    }
  }
  for (const [id, s] of seen) out.push(`ghost ${id}: from ${s.first}ms to ${s.last}ms, tallest ${s.maxH}px, classes ${[...s.cls].join(' | ')}`)
  let prev = ''
  for (const f of frames) {
    const line = `${f.flights.map((g) => `ghost(${g.x},${g.y} ${g.w}x${g.h} o${g.opacity})`).join(' ')} slots[${f.slots.map((s) => `${s.zone}:${s.visibility}:${s.opacity}`).join(' ')}]`
    if (line !== prev) out.push(`${String(f.t).padStart(5)}ms ${line}`)
    prev = line
  }
  return out
}

/** Each ghost seen, in the order it first showed: when it showed, how tall it got and how opaque it was last. */
function ghostLives(frames: FrameLook[]): Array<{ id: string; first: number; last: number; maxH: number; lastOpacity: number }> {
  const lives = new Map<string, { id: string; first: number; last: number; maxH: number; lastOpacity: number }>()
  for (const f of frames) {
    for (const g of f.flights) {
      const life = lives.get(g.id) ?? { id: g.id, first: f.t, last: f.t, maxH: 0, lastOpacity: 1 }
      life.last = f.t
      life.maxH = Math.max(life.maxH, g.h)
      life.lastOpacity = g.opacity
      lives.set(g.id, life)
    }
  }
  return [...lives.values()]
}

/** Opt in with FAST_CAST_SHEETS=1: a contact sheet of the screen every 100 ms, written next to the frame log. */
async function recordSheet(page: Page, label: string, resolveAt: number, run: () => Promise<FrameLook[]>): Promise<FrameLook[]> {
  if (!process.env.FAST_CAST_SHEETS) return run()
  const cdp = await page.context().newCDPSession(page)
  const shots: Array<{ ts: number; data: string }> = []
  cdp.on('Page.screencastFrame', (f) => {
    shots.push({ ts: (f.metadata.timestamp ?? 0) * 1000, data: f.data })
    void cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {})
  })
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 55, maxWidth: 800, maxHeight: 450, everyNthFrame: 1 })
  const frames = await run()
  await cdp.send('Page.stopScreencast')
  const t0 = await page.evaluate(() => (window as unknown as { __fcT0?: number }).__fcT0 ?? 0)
  const from = resolveAt > 1000 ? resolveAt - 200 : 0
  const picks: Array<{ t: number; real: number; data: string }> = []
  for (let t = 0; t <= 1700; t += 100) {
    const at = (resolveAt > 1000 && t >= 900 ? from + t - 900 : t)
    const f = shots.filter((x) => x.ts - t0 <= at).pop()
    if (f) picks.push({ t: at, real: Math.round(f.ts - t0), data: f.data })
  }
  const sheet = await page.context().newPage()
  await sheet.setViewportSize({ width: 1640, height: 900 })
  await sheet.setContent(`<html><body style="margin:0;background:#111;color:#fff;font:14px sans-serif;display:grid;grid-template-columns:repeat(4,404px);gap:6px">${picks.map((x) => `<div><img src="data:image/jpeg;base64,${x.data}" style="width:404px;display:block"><div>${x.t}ms after the cast (frame from ${x.real}ms)</div></div>`).join('')}</body></html>`)
  await sheet.screenshot({ path: path.join(SHOTS_DIR, `opponent-fast-cast-${label}-sheet.png`), fullPage: true })
  await sheet.close()
  return frames
}

// A model seat's spell resolves as soon as the human's side auto-passes, often 50-150 ms after it showed
// on the stack: its cast must still play out whole, once, and not be cut off halfway or crossed by a copy.
for (const [label, kind, resolveAt] of [['fast', 'creature', 60], ['slow', 'creature', 3000], ['fast-instant', 'instant', 60], ['slow-instant', 'instant', 3000]] as const) {
  test(`an opponent's spell that resolves ${resolveAt}ms after it is cast still shows its cast (${label})`, { tag: '@flights' }, async ({ page }) => {
    fs.mkdirSync(SHOTS_DIR, { recursive: true })
    await withFakeServer(() => spellsScenario('blaze'), async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: `fc${label.replace(/[^a-z]/g, '').slice(0, 4)}`, skipAsks: true })
      await expect(page.locator('.game-board')).toBeVisible({ timeout: 20_000 })
      await page.waitForTimeout(1500)
      await page.evaluate(() => (window as unknown as { __mageFlights?: { clear: () => void } }).__mageFlights?.clear())
      const frames = await recordSheet(page, label, resolveAt, () => replayCast(page, kind, resolveAt, resolveAt + 2500))
      const diag = await page.evaluate(() => (window as unknown as { __mageFlights?: { log: () => unknown[] } }).__mageFlights?.log() ?? [])
      const lines = [...summarize(frames), '--- flight log ---', ...diag.map((d) => JSON.stringify(d))]
      fs.writeFileSync(path.join(SHOTS_DIR, `opponent-fast-cast-${label}.log`), lines.join('\n') + '\n')
      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])

      const ghosts = ghostLives(frames)
      const cast = ghosts[0]
      expect(cast, 'the cast shows a flying card').toBeDefined()
      expect(cast.maxH, 'the cast is shown large in the middle of the screen').toBeGreaterThanOrEqual(300)
      expect(cast.last - cast.first, 'the cast plays its whole flight').toBeGreaterThanOrEqual(800)
      expect(cast.lastOpacity, 'the cast fades out where it lands instead of vanishing mid-air').toBeLessThan(0.6)
      const stackShown = frames.filter((f) => f.flights.some((g) => g.id === cast.id)
        && f.slots.some((s) => s.zone === 'spell@stack' && s.visibility === 'visible' && s.opacity > 0.5)).map((f) => f.t)
      expect(stackShown, 'the stack entry stays hidden while the cast is still flying').toEqual([])
      if (kind === 'creature' && resolveAt < 1000) {
        expect(ghosts.map((g) => g.id), 'one card goes from the hand to the battlefield, not two crossing').toHaveLength(1)
      }
    })
  })
}
